package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import io.github.sirp00m.npcortex.agent.SafeRetreatPolicy;
import io.github.sirp00m.npcortex.agent.GoalCompletionLedger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

/** v0.8.2 generalized overworld pilot: persistent task ledger and bounded Observe->Qwen->Skill->Verify->Replan.
 * No Java branch chooses which skill the agent SHOULD perform next.
 * Physical safety, authority, termination and result verification remain deterministic.
 * The server thread exclusively reads/writes world state; HTTP is always asynchronous.
 */
public final class AgenticTaskController {
    private static final String KEY = "NPCortexAgenticV085";
    private static final Map<UUID, UUID> IN_FLIGHT = new HashMap<>(); // npc -> request nonce; server thread only
    private static final int MAX_CALLS = AgenticTaskPolicy.MAX_CALLS;
    private static final int MAX_FAILURES = AgenticTaskPolicy.MAX_FAILURES;
    private static final int MAX_STALLED = AgenticTaskPolicy.MAX_STALLED;
    private static final int MAX_SAME_FAILURES = AgenticTaskPolicy.MAX_SAME_FAILURES;
    private static final int MAX_TICKS = 24000, NAV_TIMEOUT = 160;
    private static final int POLL_TICKS = 10;
    private AgenticTaskController() {}

    public static boolean isBusy(CompanionEntity npc) { return npc.getPersistentData().contains(KEY); }
    private static CompoundTag task(CompanionEntity npc) { return npc.getPersistentData().getCompound(KEY); }
    static UUID currentTaskId(CompanionEntity npc) {
        return isBusy(npc) && task(npc).hasUUID("taskId") ? task(npc).getUUID("taskId") : null;
    }
    static void save(CompanionEntity npc, CompoundTag t) {
        AgentRuntime.savePhase(npc, t);
        npc.getPersistentData().put(KEY, t);
    }
    public static void clearTransient() { IN_FLIGHT.clear(); }
    /** Surface a bounded, non-sensitive cause instead of hiding all planner failures. */
    static String brainFailure(Throwable error) {
        if (error == null) return "empty brain decision; inspect backend output";
        Throwable root=error;
        while ((root instanceof java.util.concurrent.CompletionException
                || root instanceof java.util.concurrent.ExecutionException)
                && root.getCause()!=null) root=root.getCause();
        String message=root.getMessage()==null ? "" : root.getMessage();
        if (message.contains("HTTP 400"))
            return "backend rejected world observation (HTTP 400); inspect Backend console";
        if (message.contains("HTTP 502"))
            return "Ollama inference/decision validation failed (HTTP 502); inspect Backend console";
        if (message.contains("HTTP 500"))
            return "backend error (HTTP 500); inspect Backend console";
        if (root instanceof java.net.http.HttpTimeoutException
                || root instanceof java.net.SocketTimeoutException)
            return "Ollama/Backend request timed out; inspect Backend console";
        if (root instanceof java.net.ConnectException)
            return "cannot connect to NPCortex Backend; verify dev-backend.ps1";
        if (message.contains("Invalid registry tool/target response"))
            return "invalid tool/target JSON received from Backend; inspect Backend console";
        return "brain request failed ("+root.getClass().getSimpleName()+"); inspect Backend console";
    }
    private static int itemCount(CompanionEntity npc, String id) {
        int n = 0;
        for (int i = 0; i < npc.bag().getContainerSize(); i++) {
            ItemStack stack = npc.bag().getItem(i);
            if (!stack.isEmpty() && id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
                n += stack.getCount();
        }
        return n;
    }
    private static boolean hasScaffoldMaterial(CompanionEntity npc) {
        for (int i = 0; i < npc.bag().getContainerSize(); i++) {
            ItemStack stack = npc.bag().getItem(i);
            if (!stack.isEmpty() && ScaffoldPolicy.validMaterial(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) return true;
        }
        return false;
    }
    static int collected(CompanionEntity npc, CompoundTag t) {
        return Math.max(0, itemCount(npc, t.getString("item")) - t.getInt("initial"));
    }
    static void tell(ServerPlayer owner, CompanionEntity npc, String msg) {
        owner.sendSystemMessage(Component.literal("NPCortex " + npc.getName().getString() + ": " + msg));
    }
    public static String status(CompanionEntity npc) {
        if (!isBusy(npc)) return "[Agentic task: idle.]";
        CompoundTag t = task(npc);
        return "[Agentic task " + t.getString("state") + ": " + t.getString("item")
                + " " + collected(npc, t) + "/" + t.getInt("goal")
                + " stored; mined=" + t.getInt("mined") + "; AI requests=" + t.getInt("calls")
                + " total; failures=" + t.getInt("failures") + "/" + MAX_FAILURES
                + "; stalls=" + t.getInt("stalls") + "/" + MAX_STALLED
                + "; lifecycle=" + t.getString("goalState")
                + "; search=" + t.getString("accessSearch") + "; expanded=" + t.getInt("accessExpanded")
                + "; explored=" + t.getInt("exploredSegments")
                + "; decisionsWithoutGoalProgress=" + t.getInt("decisionsWithoutGoalProgress")
                + "; missingLogs=" + GoalCompletionLedger.missing(t.getInt("mined"),collected(npc,t),Navigation3DAccess.borrowed(t,t.getString("item")))
                + "; goalPhase=" + GoalCompletionLedger.phase(WholeTreeTracker.isCommitted(t),
                        npc.level() instanceof ServerLevel phaseLevel ? WholeTreeTracker.pending(phaseLevel,t) : 0,
                        t.getInt("completedTrees"),t.getInt("mined"),collected(npc,t),
                        Navigation3DAccess.borrowed(t,t.getString("item")),t.getInt("goal"),
                        Navigation3DAccess.size(t)+TreeScaffold.size(t)+WorldTerrainEdits.size(t))
                + "; harvestNavBlocked=" + t.getBoolean("harvestNavBlocked")
                + "; retreatsWithoutMine=" + t.getInt("retreatWithoutMine")
                + "; accessBlocks=" + Navigation3DAccess.size(t)
                + "; scaffold=" + TreeScaffold.size(t)
                + "; scaffoldMaterial=" + (BuildMaterials.available(npc,t)>0)
                + "; usableBlocks=" + BuildMaterials.available(npc,t)
                + "; materialMined=" + t.getInt("materialMined")
                + "; observedDrops=" + t.getInt("observedDrops")
                + "; pickupReady=" + t.getInt("observedPickupReady")
                + "; ownDropsNearby=" + TaskDropNavigator.nearbyTracked(npc,t)
                + "; leafClearable=" + t.getInt("leafClearable")
                + "; groundStands=" + t.getInt("groundStandCandidates")
                + "; treeRemaining=" + (npc.level() instanceof ServerLevel sl
                    ? WholeTreeTracker.pending(sl, t) : -1)
                + "; treesDone=" + t.getInt("completedTrees")
                + "; terrainEdits=" + WorldTerrainEdits.size(t)
                + "; chosen=" + t.getString("lastChoice")
                + "; reason=" + t.getString("lastBrainReason")
                + "; options=" + t.getString("lastOffered")
                + "; last=" + t.getString("lastResult") + "]";
    }
    public static String trace(CompanionEntity npc) {
        CompoundTag t = isBusy(npc) ? task(npc) : npc.getPersistentData().getCompound("NPCortexLastGoal");
        var journal = t.getList("runtimeEvents", net.minecraft.nbt.Tag.TAG_COMPOUND);
        StringBuilder out = new StringBuilder("[Runtime Sprint2 Fix5: " + t.getString("goalState") + "]");
        for (int n=Math.max(0,journal.size()-10); n<journal.size(); n++) {
            var row=journal.getCompound(n);
            out.append("\n").append(row.getLong("tick")).append(" ").append(row.getString("type"))
                .append(": ").append(row.getString("detail"));
        }
        return out.toString();
    }
    public static String start(ServerPlayer owner, CompanionEntity npc, String message, String item, int goal) {
        if (!BrainSkills.authorized(owner, npc) || owner.distanceToSqr(npc) > 64.0D)
            return "[Agentic task blocked: nearby owner, cheats and world-edits opt-in required.]";
        if (!GatherTaskPolicy.valid(item, goal) || !TreeSpecies.supportedLog(item))
            return "[Agentic task blocked: supported vanilla overworld wood log and quantity 1–16 required.]";
        if (AgentRuntime.isBusy(npc))
            return "[Agentic task busy: cancel current NPC work first.]";
        CompoundTag t = new CompoundTag();
        t.putString("goalType", "harvest_logs");
        t.putLong("workAnchor",npc.blockPosition().asLong());
        t.putString("originDimension", npc.level().dimension().location().toString());
        t.putString("goalState", "READY");
        t.putUUID("taskId", UUID.randomUUID()); // forbid stale answers crossing task restarts
        t.putString("item", item); t.putInt("goal", goal); t.putInt("initial", itemCount(npc, item));
        t.putInt("calls", 0); t.putInt("failures", 0); t.putInt("sameFailures", 0);
        t.putInt("mined", 0); t.putInt("stalls", 0); t.putString("state", "ready");
        t.putInt("completedTrees", 0);
        t.putString("request", message.substring(0, Math.min(message.length(), 400)));
        t.putString("lastResult", "[Task accepted by server; no tool executed yet.]");
        t.putInt("homeX", npc.blockPosition().getX());
        t.putInt("homeY", npc.blockPosition().getY());
        t.putInt("homeZ", npc.blockPosition().getZ());
        t.putLong("started", owner.level().getGameTime());
        t.putLong("nextAt", owner.level().getGameTime() + 1);
        t.putBoolean("previousFollow", npc.isFollowing());
        npc.setFollowing(false);
        npc.clearRetreat();
        save(npc, t);
        return "[Agentic whole-tree task started: collect at least " + goal + " " + item
                + ", finish each committed verified tree, restore task terrain and keep ALL logs in NPC bag.]";
    }
    public static String cancel(CompanionEntity npc) {
        if (!isBusy(npc)) return "[Agentic task: none active.]";
        CompoundTag t = task(npc);
        IN_FLIGHT.remove(npc.getUUID());
        npc.getNavigation().stop();
        if (AgenticTaskPolicy.mustCleanBeforeCancel(TreeScaffold.size(t))
                || WorldTerrainEdits.size(t) > 0 || Navigation3DAccess.needsCleanup(t)
                || "access_3d".equals(t.getString("state")) || "scaffold_jump".equals(t.getString("state"))) {
            t.putBoolean("cancelRequested", true);
            Navigation3DAccess.abortRoute(npc, t);
            t.putLong("cleanupStarted", npc.level().getGameTime());
            t.putString("state", "cleanup");
            save(npc, t);
            return "[Agentic task cancelled: guarded scaffold cleanup pending; bag retained.]";
        }
        t.putBoolean("cancelRequested", true);
        finish(npc, t);
        return "[Agentic task cancelled: collected items retained in NPC bag.]";
    }
    public static String pause(CompanionEntity npc, String reason) {
        if (!isBusy(npc)) return "[Agentic task: none active.]";
        CompoundTag t = task(npc);
        IN_FLIGHT.remove(npc.getUUID());
        npc.getNavigation().stop();
        if (!"paused".equals(t.getString("state"))) {
            t.putString("pausedFrom", t.getString("state"));
            t.putLong("pausedAt", npc.level().getGameTime());
        }
        t.putString("state", "paused"); t.putString("lastResult", reason);
        save(npc, t);
        return "[Agentic task paused: " + reason + "; progress preserved.]";
    }
    public static String resume(CompanionEntity npc) {
        if (!isBusy(npc)) return "[Agentic task: no saved work.]";
        CompoundTag t = task(npc);
        if (!"paused".equals(t.getString("state"))) return status(npc);
        boolean cleanup = t.getBoolean("cancelRequested") || "cleanup".equals(t.getString("pausedFrom"));
        // A paused Fix8 world has no harvestNavBlocked field yet. Recover
        // only an actual failed committed-log search from preserved evidence,
        // so the new strategy is available WITHOUT restarting the whole tree.
        if (!t.contains("harvestNavBlocked") && t.getString("lastChoice").startsWith("navigate_3d(")
                && t.getString("lastResult").contains("bounded 3D search found no safe supported route"))
            t.putBoolean("harvestNavBlocked",true);
        // Explicit resume grants a fresh liveness window, not a new goal or
        // permission to forget mutations. Stable failure evidence is retained.
        t.putInt("failures",0); t.putInt("sameFailures",0); t.putInt("stalls",0);
        if (t.contains("pausedAt")) {
            t.putLong("started", t.getLong("started") + Math.max(0,npc.level().getGameTime()-t.getLong("pausedAt")));
            if(t.contains("lastProgressAt")) t.putLong("lastProgressAt",t.getLong("lastProgressAt")
                    +Math.max(0,npc.level().getGameTime()-t.getLong("pausedAt")));
            t.remove("pausedAt");
        }
        t.putLong("lastProgressAt",npc.level().getGameTime());
        if (!cleanup) Navigation3DAccess.abortRoute(npc,t);
        t.putString("state", cleanup ? "cleanup" : "ready");
        if (cleanup) {
            Navigation3DAccess.abortRoute(npc, t);
            t.putLong("cleanupStarted", npc.level().getGameTime());
        }
        t.putLong("nextAt", npc.level().getGameTime() + 1);
        t.putString("lastResult", "[Task resumed after explicit owner approval.]");
        save(npc, t);
        return "[Agentic task resumed.]";
    }
    static void finish(CompanionEntity npc, CompoundTag t) {
        IN_FLIGHT.remove(npc.getUUID());
        npc.getNavigation().stop();
        if (!t.getBoolean("cancelRequested"))
            AgentRuntime.transition(npc, t, io.github.sirp00m.npcortex.agent.GoalState.VERIFYING);
        AgentRuntime.transition(npc, t, t.getBoolean("cancelRequested")
                ? io.github.sirp00m.npcortex.agent.GoalState.CANCELLED
                : io.github.sirp00m.npcortex.agent.GoalState.SUCCEEDED);
        npc.getPersistentData().put("NPCortexLastGoal", t.copy());
        npc.getPersistentData().remove(KEY);
        npc.setFollowing(t.getBoolean("previousFollow"));
    }
    static void result(CompanionEntity npc, CompoundTag t, String tool, String ref,
            String feedback, boolean actualProgress) {
        // A tool may have its precondition already satisfied without changing the
        // world or NPC position. This MUST NOT reset the no-progress watchdog.
        // Qwen still chooses the next skill; Java only enforces liveness budgets.
        boolean okay = feedback.startsWith("[Action confirmed:")
                || feedback.startsWith("[Action started:")
                || feedback.startsWith("[Action unchanged:");
        String signature = t.getString("activeSkillKey");
        if (signature.isEmpty()) signature = tool + "/" + ref;
        // A newly verified log is physical evidence that the last route worked.
        // Permit one more bounded retreat/replan cycle after making such progress.
        if (actualProgress && "mine".equals(tool)) {
            t.putInt("retreatWithoutMine", 0);
            t.putBoolean("harvestNavBlocked", false);
        }
        t.putBoolean("verifiedEffect", actualProgress);
        ServerPlayer owner = npc.getOwnerUUID() == null ? null : npc.getServer().getPlayerList().getPlayer(npc.getOwnerUUID());
        var context = new BrainSkillRegistry.Context(owner, npc, t, new JsonObject(), npc.blockPosition());
        if (actualProgress && !BrainSkillRegistry.REGISTRY.verify(tool, context, ref)) {
            actualProgress = false; okay = false;
            feedback = "[Action failed: registry could not verify executor effect.]";
        }
        t.putString("lastResult", feedback.substring(0, Math.min(350, feedback.length())));
        TaskContext.record(npc,t,tool,feedback);
        // A verified work position is a better return anchor than the original
        // task-start position, particularly after moving to another work site.
        if(actualProgress && "mine".equals(tool) && owner!=null
                && Navigation3DAccess.safeStand(owner,t,npc.blockPosition()))
            t.putLong("workAnchor",npc.blockPosition().asLong());
        AgentRuntime.transition(npc, t, io.github.sirp00m.npcortex.agent.GoalState.VERIFYING);
        AgentRuntime.event(npc, t, actualProgress ? "skill_verified" : "skill_no_progress", tool + ": " + feedback);
        if (!okay || !actualProgress) BrainSkillRegistry.REGISTRY.recover(tool, context, ref);
        if (!okay) {
            t.putInt("failures", t.getInt("failures") + 1);
            int same = AgenticTaskPolicy.nextSameFailures(t.getString("lastFailedTool"),
                    signature, true, t.getInt("sameFailures"));
            t.putInt("sameFailures", same);
            t.putString("lastFailedTool", signature);
        } else {
            t.putInt("sameFailures", 0);
            t.putString("lastFailedTool", "");
        }
        if (actualProgress) {
            t.putInt("failures",0);
            t.putLong("lastProgressAt",npc.level().getGameTime());
        }
        // A refused physical route counts toward the failure watchdog, not the
        // no-op watchdog as well. Qwen now gets a chance to select an alternative.
        t.putInt("stalls", AgenticTaskPolicy.nextStalledSteps(
                t.getInt("stalls"), actualProgress, !okay));
        t.putString("lastResult", feedback.substring(0, Math.min(350, feedback.length())));
        t.putLong("nextAt", npc.level().getGameTime() + POLL_TICKS);
        t.putString("state", "ready");
        if (!AgenticTaskPolicy.canContinue(t.getInt("calls"), t.getInt("failures"),
                t.getInt("sameFailures"), t.getInt("stalls")))
            t.putString("state", "paused");
        save(npc, t);
    }
    private static void action(ServerPlayer owner, CompanionEntity npc, CompoundTag t,
            String tool, String ref, JsonObject snapshot, BlockPos origin) {
        AgentRuntime.transition(npc, t, io.github.sirp00m.npcortex.agent.GoalState.EXECUTING);
        BlockPos target = tool.equals("explore") ? ExplorationSkills.resolve(snapshot,ref,origin) : tool.endsWith("_material")
                ? BrainObservation.resolveMaterial(snapshot,ref,origin)
                : BrainObservation.resolveBlock(snapshot, ref, origin, t.getString("item"));
        t.putString("activeSkill", tool); t.putString("activeRef", ref);
        t.putString("activeSkillKey", FailureMemory.identity(tool, ref, snapshot, origin, t.getString("item")));
        t.putLong("activeFingerprint", FailureMemory.fingerprint(npc, t, target));
        t.remove("activeBlock");
        if (target != null) t.putLong("activeBlock", target.asLong());
        AgentRuntime.event(npc, t, "skill_started", tool + ":" + ref + "; reason=" + t.getString("lastBrainReason"));
        try {
            if (!BrainSkillRegistry.REGISTRY.execute(tool,
                    new BrainSkillRegistry.Context(owner,npc,t,snapshot,origin),ref))
                result(npc,t,tool,ref,"[Action blocked: skill/target not offered by server registry.]",false);
        } catch (RuntimeException error) {
            AgentRuntime.event(npc,t,"executor_exception",tool + ": " + error.getClass().getSimpleName());
            tell(owner,npc,pause(npc,"executor exception; inspect task trace before resuming"));
        }
    }
    static void recordNavOrigin(CompanionEntity npc, CompoundTag t) {
        t.putDouble("navOriginX", npc.getX());
        t.putDouble("navOriginY", npc.getY());
        t.putDouble("navOriginZ", npc.getZ());
    }
    private static boolean navigationMoved(CompanionEntity npc, CompoundTag t) {
        return npc.position().distanceToSqr(t.getDouble("navOriginX"),
                t.getDouble("navOriginY"), t.getDouble("navOriginZ")) > 0.09D;
    }
    private static void request(ServerPlayer owner, CompanionEntity npc, CompoundTag t) {
        UUID requestNonce = UUID.randomUUID();
        if (IN_FLIGHT.putIfAbsent(npc.getUUID(), requestNonce) != null) return;
        UUID taskNonce = t.getUUID("taskId");
        t.putUUID("pendingRequest", requestNonce);
        // The snapshot + target origin are immutable for this particular Qwen response.
        JsonObject world = BrainObservation.capture(owner, npc, t.getString("item"), t);
        int readyDrops = 0;
        int clearableLeaves = 0;
        int groundStandCandidates = 0;
        var observed = world.getAsJsonArray("drops");
        for (var entry : observed) {
            if (entry.getAsJsonObject().get("pickup_reachable").getAsBoolean()) readyDrops++;
        }
        for (var entry : world.getAsJsonArray("targets")) {
            if (entry.getAsJsonObject().get("leaf_clearable").getAsBoolean()) clearableLeaves++;
            if (entry.getAsJsonObject().get("ground_stand_possible").getAsBoolean()) groundStandCandidates++;
        }
        t.putInt("leafClearable", clearableLeaves);
        t.putInt("groundStandCandidates", groundStandCandidates);
        t.putInt("observedDrops", observed.size());
        t.putInt("observedPickupReady", readyDrops);
        BlockPos origin = npc.blockPosition().immutable();
        world.addProperty("goal_item", t.getString("item"));
        world.addProperty("goal_count", t.getInt("goal"));
        world.addProperty("collected", collected(npc, t));
        world.addProperty("mined", t.getInt("mined"));
        world.addProperty("calls", t.getInt("calls"));
        world.addProperty("failures", t.getInt("failures"));
        world.addProperty("stalls", t.getInt("stalls"));
        world.addProperty("same_failures", t.getInt("sameFailures"));
        world.addProperty("last_result", t.getString("lastResult"));
        world.addProperty("scaffold", TreeScaffold.size(t));
        world.addProperty("paused", false);
        BrainSkillRegistry.attach(new BrainSkillRegistry.Context(owner,npc,t,world,origin));
        var recovery=world.getAsJsonObject("task_context").getAsJsonObject("goal_completion");
        if (GoalCompletionLedger.recoveryExhausted(
                GoalCompletionLedger.Phase.valueOf(recovery.get("phase").getAsString()
                        .toUpperCase(java.util.Locale.ROOT)),
                t.getInt("decisionsWithoutGoalProgress"))) {
            AgentRuntime.event(npc,t,"recovery_exhausted","Missing "
                    + recovery.get("missing_logs").getAsInt() + " log(s) after bounded attempts; no item created.");
            tell(owner,npc,pause(npc,"recovery budget exhausted; "
                    + recovery.get("missing_logs").getAsInt()
                    + " mined log(s) unaccounted for; inspect dropped items and task trace"));
            IN_FLIGHT.remove(npc.getUUID(),requestNonce);
            return;
        }
        // Keep the actual options shown to Qwen; a pause without options is a
        // planner dead end, whereas pause with alternatives is a model choice.
        StringBuilder offered = new StringBuilder();
        for (var entry : world.getAsJsonObject("skills").getAsJsonArray("choices")) {
            if (!offered.isEmpty()) offered.append(",");
            if (offered.length() >= 180) { offered.append("..."); break; }
            offered.append(entry.getAsString());
        }
        t.putString("lastOffered", offered.toString());
        int generation = t.getInt("calls") + 1;
        t.putInt("calls", generation);
        t.putString("state", "thinking");
        t.putLong("nextAt", npc.level().getGameTime() + POLL_TICKS);
        save(npc, t);
        UUID id = npc.getUUID();
        UUID ownerId = owner.getUUID();
        ChatBridge.brainStep(npc.getCharacterId(), ownerId.toString(), t.getString("request"),
                id.toString(), world).whenComplete((decision, error) -> owner.getServer().execute(() -> {
            IN_FLIGHT.remove(id, requestNonce); // an old completion cannot clear a newer request
            ServerPlayer online = owner.getServer().getPlayerList().getPlayer(ownerId);
            if (online == null || online.level() != npc.level() || npc.isRemoved()
                    || !online.getUUID().equals(npc.getOwnerUUID()) || !isBusy(npc)) return;
            CompoundTag now = task(npc);
            if (!now.hasUUID("taskId") || !taskNonce.equals(now.getUUID("taskId"))
                    || !now.hasUUID("pendingRequest") || !requestNonce.equals(now.getUUID("pendingRequest"))
                    || now.getInt("calls") != generation || !"thinking".equals(now.getString("state"))
                    || !now.getString("originDimension").equals(npc.level().dimension().location().toString())) return;
            if (!BrainSkills.authorized(online, npc)) {
                tell(online, npc, pause(npc, "owner permissions or world-edit safeguard changed")); return;
            }
            if (error != null || decision == null) {
                String reason = brainFailure(error);
                AgentRuntime.event(npc, now, "brain_error", reason);
                tell(online, npc, pause(npc, reason)); return;
            }
            now.putString("lastBrainReason",decision.reason());
            if ("pause".equals(decision.tool())) {
                // Preserve structures on pause; do not override Qwen with an
                // unsolicited demolition/rebuild loop during unfinished work.
                AgentRuntime.event(npc, now, "brain_pause", "reason=" + decision.reason()
                        + "; offered=" + now.getString("lastOffered"));

            }
            action(online, npc, now, decision.tool(), decision.target(), world, origin);
        }));
    }
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        int visited = 0;
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof CompanionEntity npc) || !isBusy(npc) || ++visited > 16) continue;
            CompoundTag t = task(npc);
            String phase = t.getString("state");
            String dimension = level.dimension().location().toString();
            if (!t.contains("originDimension")) t.putString("originDimension", dimension); // old saves
            if (!dimension.equals(t.getString("originDimension"))) {
                if (!"paused".equals(phase)) pause(npc,"NPC changed dimension; return to task world before resuming");
                continue;
            }
            ServerPlayer owner = npc.getOwnerUUID() == null ? null
                    : level.getServer().getPlayerList().getPlayer(npc.getOwnerUUID());
            if (owner == null || owner.level() != level || !owner.isAlive()) {
                if (!"paused".equals(phase)) pause(npc, "owner unavailable; resume when owner returns"); continue;
            }
            if ("paused".equals(phase)) continue;
            if ("cleanup".equals(phase) && !BrainSkills.authorized(owner, npc)) {
                tell(owner, npc, pause(npc, "owner or world-edit permission unavailable for cleanup"));
                continue;
            }
            if ("cleanup".equals(phase)) {
                if (!t.contains("cleanupStarted")) t.putLong("cleanupStarted", level.getGameTime());
                if (level.getGameTime() - t.getLong("cleanupStarted") > 2400) {
                    tell(owner,npc,pause(npc,"cleanup timed out; temporary structures retained")); continue;
                }
                String feedback = Navigation3DAccess.needsCleanup(t)
                        ? Navigation3DAccess.cleanup(owner,npc,t)
                        : (TreeScaffold.size(t) > 0 ? TreeScaffold.dismantle(level,npc,t)
                            : (npc.onGround() ? TreeScaffold.DONE : TreeScaffold.WAIT));
                // General route must be undone before the legacy root pillar.
                if (TreeScaffold.DONE.equals(feedback) && TreeScaffold.size(t) > 0) {
                    save(npc,t); continue;
                }
                if (TreeScaffold.DONE.equals(feedback)) {
                    if (WorldTerrainEdits.size(t) > 0) {
                        String restored = WorldTerrainEdits.restore(level, npc, owner, t);
                        if (!restored.startsWith("[Action confirmed:")) {
                            tell(owner, npc, pause(npc, restored));
                            continue;
                        }
                    }
                    if (t.getBoolean("cancelRequested")) {
                        finish(npc, t);
                        tell(owner, npc, "[Agentic task cancelled; scaffold safely dismantled; bag retained.]");
                    } else {
                        t.remove("treeLocked");
                        t.remove("pillarX"); t.remove("pillarY"); t.remove("pillarZ");
                        AgentRuntime.event(npc, t, "safe_retreat_completed",
                                "All recorded task scaffolds removed; inventory retained.");
                        if (t.getBoolean("pauseAfterSafeRetreat")) {
                            t.remove("pauseAfterSafeRetreat");
                            tell(owner, npc, pause(npc,
                                    "no further verified tree progress after repeated safe retreats; "
                                    + "scaffold removed; owner intervention required"));
                        } else {
                            result(npc, t, "descend", "",
                                    "[Action confirmed: recorded scaffold dismantled safely.]", true);
                        }
                    }
                } else if (feedback.startsWith(TreeScaffold.BLOCKED))
                    tell(owner, npc, pause(npc, "scaffold cleanup unsafe: " + feedback));
                else save(npc, t);
                continue;
            }
            if (!BrainSkills.authorized(owner, npc)) {
                tell(owner, npc, pause(npc, "owner moved away or safety rules changed")); continue;
            }
            // Body-level mechanic: standing close to a task drop collects it like
            // player pickup, without spending Qwen requests. No movement policy.
            if (level.getGameTime() % 5 == 0) {
                int before = collected(npc, t);
                int need = Math.max(0, t.getInt("mined") - before);
                if (need > 0) {
                    int taken = WorldActions.pickupForGather(owner, npc, t.getString("item"), need);
                    if (taken > 0) {
                        t.putInt("stalls", 0);
                        if (!"thinking".equals(phase))
                            t.putString("lastResult", "[Server auto-pickup: " + taken
                                    + " task logs added to NPC bag; total=" + collected(npc,t) + ".]");
                        save(npc, t);
                    }
                }
            }
            if (WholeTreeTracker.isCommitted(t) && WholeTreeTracker.pending(level, t) == 0
                    && collected(npc,t)+Navigation3DAccess.borrowed(t,t.getString("item"))>=t.getInt("mined")) {
                WholeTreeTracker.releaseCompleted(t);
                if(TreeScaffold.size(t)==0) t.remove("treeLocked"); // retain legacy pillar anchor while structure exists
                t.putInt("stalls", 0);
                t.putString("lastResult", "[Server verified entire committed tree harvested; decide whether to collect drops or another tree.]");
                save(npc, t);
            }
            if (level.getGameTime() - (t.contains("lastProgressAt") ? t.getLong("lastProgressAt") : t.getLong("started")) > MAX_TICKS
                    || ("ready".equals(phase) && !AgenticTaskPolicy.canContinue(
                            t.getInt("calls"), t.getInt("failures"), t.getInt("sameFailures"), t.getInt("stalls")))) {
                tell(owner, npc, pause(npc, "no-progress watchdog or repeated failures exhausted; inspect task trace")); continue;
            }
            if ("access_3d".equals(phase)) {
                String outcome = Navigation3DAccess.tick(owner,npc,t);
                if (TreeScaffold.DONE.equals(outcome) || "SEGMENT".equals(outcome)) {
                    if ("explore".equals(t.getString("activeSkill"))) ExplorationSkills.visited(npc,t);
                    result(npc,t,t.getString("activeSkill"),t.getString("activeRef"),
                            "SEGMENT".equals(outcome)
                                ? "[Action confirmed: route segment traversed; target NOT reached yet; replan from new position.]"
                                : TaskContext.reachedFeedback(t.getString("activeSkill")),true);
                }
                else if (outcome.startsWith(TreeScaffold.BLOCKED)) {
                    Navigation3DAccess.abortRoute(npc,t);
                    result(npc,t,t.getString("activeSkill"),t.getString("activeRef"),"[Action failed: " + outcome + "]",false);
                } else save(npc,t);
                continue;
            }
            if ("scaffold_prepare".equals(phase) || "scaffold_jump".equals(phase)) {
                if (level.getGameTime() - t.getLong("navStart") > NAV_TIMEOUT) {
                    result(npc, t, "climb", t.getString("activeRef"), "[Action failed: guarded scaffold skill timed out.]", false);
                    continue;
                }
                String outcome = "scaffold_jump".equals(phase)
                        ? TreeScaffold.tickJump(level, owner, npc, t, level.getGameTime())
                        : TreeScaffold.ascend(level, owner, npc, t, level.getGameTime());
                if (TreeScaffold.DONE.equals(outcome)) {
                    if ("scaffold_jump".equals(phase))
                        result(npc, t, "climb", t.getString("activeRef"), "[Action confirmed: climbed one verified scaffold level.]", true);
                    else { t.putString("state", "scaffold_jump"); save(npc, t); }
                } else if (outcome.startsWith(TreeScaffold.BLOCKED)) {
                    result(npc, t, "climb", t.getString("activeRef"), "[Action failed: " + outcome + "]", false);
                } else save(npc, t);
                continue;
            }
            if ("navigating_block".equals(phase) || "navigating_drop".equals(phase)
                    || "navigating_material".equals(phase)) {
                // A passive pickup can consume the target ItemEntity while the NPC walks.
                // An inventory delta is success, NOT "target vanished" / a failed route.
                if ("navigating_drop".equals(phase)
                        && collected(npc, t) > t.getInt("navCollectedBefore")) {
                    npc.getNavigation().stop();
                    result(npc, t, t.getString("activeSkill"), t.getString("activeRef"),
                            "[Action confirmed: walking brought drops into physical auto-pickup range.]", true);
                    continue;
                }
                boolean reached = false;
                if ("navigating_block".equals(phase) || "navigating_material".equals(phase)) {
                    BlockPos target = new BlockPos(t.getInt("targetX"), t.getInt("targetY"), t.getInt("targetZ"));
                    reached = "navigating_material".equals(phase)
                            ? MaterialSource.eligible(owner,npc,t,target)
                                  && MaterialSource.reachable(owner,npc,target)
                            : BrainSkills.validBlock(owner,npc,target,t.getString("item"),t)
                                  && npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))
                                      <= InteractionReach.squared(owner.isCreative())
                                  && WorldBlockActions.visible(npc,target);
                } else {
                    var drop = level.getEntity(t.getUUID("drop"));
                    reached = drop instanceof ItemEntity item
                            && WorldActions.reachableGatherDrop(npc, item, owner.isCreative());
                }
                if (reached) {
                    npc.getNavigation().stop();
                    boolean moved = navigationMoved(npc, t);
                    result(npc, t, t.getString("activeSkill"), t.getString("activeRef"),
                            moved ? "[Action confirmed: navigated to a newly reachable position.]"
                                  : "[Action unchanged: destination reachable, but NPC position did not change.]",
                            moved);
                }
                else if (npc.getNavigation().isDone() || level.getGameTime() - t.getLong("navStart") > NAV_TIMEOUT) {
                    npc.getNavigation().stop();
                    result(npc, t, t.getString("activeSkill"), t.getString("activeRef"), "[Action failed: navigation ended before target became reachable.]", false);
                }
                continue;
            }
            if ("loot_navigate".equals(phase)) {
                // Deterministic player-like retrieval of this task's real drops.
                // No Qwen call is spent; the NPC must physically reach each item.
                TaskDropNavigator.tick(owner,npc,t);
                save(npc,t);
                continue;
            }
            if ("thinking".equals(phase)) {
                // On reload no HTTP future survives; safely recover at most once.
                if (!IN_FLIGHT.containsKey(npc.getUUID()) && level.getGameTime() >= t.getLong("nextAt") + 20) {
                    t.putString("state", "ready");
                    t.putString("lastResult", "[Previous AI request interrupted; requesting new observation.]");
                    save(npc, t);
                }
                continue;
            }
            if ("ready".equals(phase) && level.getGameTime() % POLL_TICKS == 0
                    && !IN_FLIGHT.containsKey(npc.getUUID())
                    && TaskDropNavigator.startIfAny(owner,npc,t)) {
                save(npc,t);
                continue;
            }
            if (!"ready".equals(phase) || level.getGameTime() < t.getLong("nextAt")
                    || level.getGameTime() % POLL_TICKS != 0) continue;
            request(owner, npc, t);
        }
    }
}
