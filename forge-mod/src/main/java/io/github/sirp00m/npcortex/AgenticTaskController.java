package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
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

/** v0.8.5 pilot: persistent task ledger and bounded Observe->Qwen->Skill->Verify->Replan.
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
    private static final int MAX_TICKS = 12000, NAV_TIMEOUT = 160;
    private static final int POLL_TICKS = 10;
    private AgenticTaskController() {}

    public static boolean isBusy(CompanionEntity npc) { return npc.getPersistentData().contains(KEY); }
    private static CompoundTag task(CompanionEntity npc) { return npc.getPersistentData().getCompound(KEY); }
    private static void save(CompanionEntity npc, CompoundTag t) { npc.getPersistentData().put(KEY, t); }
    private static int itemCount(CompanionEntity npc, String id) {
        int n = 0;
        for (int i = 0; i < npc.bag().getContainerSize(); i++) {
            ItemStack stack = npc.bag().getItem(i);
            if (!stack.isEmpty() && id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
                n += stack.getCount();
        }
        return n;
    }
    private static int collected(CompanionEntity npc, CompoundTag t) {
        return Math.max(0, itemCount(npc, t.getString("item")) - t.getInt("initial"));
    }
    private static void tell(ServerPlayer owner, CompanionEntity npc, String msg) {
        owner.sendSystemMessage(Component.literal("NPCortex " + npc.getName().getString() + ": " + msg));
    }
    public static String status(CompanionEntity npc) {
        if (!isBusy(npc)) return "[Agentic task: idle.]";
        CompoundTag t = task(npc);
        return "[Agentic task " + t.getString("state") + ": " + t.getString("item")
                + " " + collected(npc, t) + "/" + t.getInt("goal")
                + " stored; mined=" + t.getInt("mined") + "; AI requests=" + t.getInt("calls")
                + "/" + MAX_CALLS + "; failures=" + t.getInt("failures") + "/" + MAX_FAILURES
                + "; stalls=" + t.getInt("stalls") + "/" + MAX_STALLED
                + "; scaffold=" + TreeScaffold.size(t)
                + "; observedDrops=" + t.getInt("observedDrops")
                + "; pickupReady=" + t.getInt("observedPickupReady")
                + "; chosen=" + t.getString("lastChoice")
                + "; last=" + t.getString("lastResult") + "]";
    }
    public static String start(ServerPlayer owner, CompanionEntity npc, String message, String item, int goal) {
        if (!BrainSkills.authorized(owner, npc) || owner.distanceToSqr(npc) > 64.0D)
            return "[Agentic task blocked: nearby owner, cheats and world-edits opt-in required.]";
        if (!GatherTaskPolicy.valid(item, goal) || !"minecraft:oak_log".equals(item))
            return "[Agentic task blocked: v0.8.5 pilot supports 1–16 oak logs only.]";
        if (isBusy(npc) || GatherTaskController.isBusy(npc) || ActionPlanRunner.isBusy(npc))
            return "[Agentic task busy: cancel current NPC work first.]";
        CompoundTag t = new CompoundTag();
        t.putUUID("taskId", UUID.randomUUID()); // forbid stale answers crossing task restarts
        t.putString("item", item); t.putInt("goal", goal); t.putInt("initial", itemCount(npc, item));
        t.putInt("calls", 0); t.putInt("failures", 0); t.putInt("sameFailures", 0);
        t.putInt("mined", 0); t.putInt("stalls", 0); t.putString("state", "ready");
        t.putString("request", message.substring(0, Math.min(message.length(), 400)));
        t.putString("lastResult", "[Task accepted by server; no tool executed yet.]");
        t.putInt("homeX", npc.blockPosition().getX());
        t.putInt("homeY", npc.blockPosition().getY());
        t.putInt("homeZ", npc.blockPosition().getZ());
        t.putLong("started", owner.level().getGameTime());
        t.putLong("nextAt", owner.level().getGameTime() + 1);
        t.putBoolean("previousFollow", npc.isFollowing());
        npc.setFollowing(false);
        save(npc, t);
        return "[Agentic task started: Qwen will select skills to collect " + goal + " " + item
                + " into NPC bag; no delivery without separate command.]";
    }
    public static String cancel(CompanionEntity npc) {
        if (!isBusy(npc)) return "[Agentic task: none active.]";
        CompoundTag t = task(npc);
        IN_FLIGHT.remove(npc.getUUID());
        npc.getNavigation().stop();
        if (AgenticTaskPolicy.mustCleanBeforeCancel(TreeScaffold.size(t))) {
            t.putBoolean("cancelRequested", true);
            t.putString("state", "cleanup");
            save(npc, t);
            return "[Agentic task cancelled: guarded scaffold cleanup pending; bag retained.]";
        }
        finish(npc, t);
        return "[Agentic task cancelled: collected items retained in NPC bag.]";
    }
    public static String pause(CompanionEntity npc, String reason) {
        if (!isBusy(npc)) return "[Agentic task: none active.]";
        CompoundTag t = task(npc);
        IN_FLIGHT.remove(npc.getUUID());
        npc.getNavigation().stop();
        t.putString("state", "paused"); t.putString("lastResult", reason);
        save(npc, t);
        return "[Agentic task paused: " + reason + "; progress preserved.]";
    }
    public static String resume(CompanionEntity npc) {
        if (!isBusy(npc)) return "[Agentic task: no saved work.]";
        CompoundTag t = task(npc);
        if (!"paused".equals(t.getString("state"))) return status(npc);
        if (!AgenticTaskPolicy.canPlan(t.getInt("calls"), t.getInt("failures"),
                t.getInt("sameFailures"), t.getInt("stalls")))
            return "[Agentic task blocked: budget exhausted; cancel and issue a new task.]";
        t.putString("state", t.getBoolean("cancelRequested") && TreeScaffold.size(t) > 0
                ? "cleanup" : "ready");
        t.putLong("nextAt", npc.level().getGameTime() + 1);
        t.putString("lastResult", "[Task resumed after explicit owner approval.]");
        save(npc, t);
        return "[Agentic task resumed.]";
    }
    private static void finish(CompanionEntity npc, CompoundTag t) {
        IN_FLIGHT.remove(npc.getUUID());
        npc.getNavigation().stop();
        npc.getPersistentData().remove(KEY);
        npc.setFollowing(t.getBoolean("previousFollow"));
    }
    private static void result(CompanionEntity npc, CompoundTag t, String tool, String ref,
            String feedback, boolean actualProgress) {
        // A tool may have its precondition already satisfied without changing the
        // world or NPC position. This MUST NOT reset the no-progress watchdog.
        // Qwen still chooses the next skill; Java only enforces liveness budgets.
        boolean okay = feedback.startsWith("[Action confirmed:")
                || feedback.startsWith("[Action started:")
                || feedback.startsWith("[Action unchanged:");
        String signature = tool + "/" + ref;
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
        t.putInt("stalls", AgenticTaskPolicy.nextStalledSteps(t.getInt("stalls"), actualProgress));
        t.putString("lastResult", feedback.substring(0, Math.min(350, feedback.length())));
        t.putLong("nextAt", npc.level().getGameTime() + POLL_TICKS);
        t.putString("state", "ready");
        if (!AgenticTaskPolicy.canPlan(t.getInt("calls"), t.getInt("failures"),
                t.getInt("sameFailures"), t.getInt("stalls")))
            t.putString("state", "paused");
        save(npc, t);
    }
    private static void action(ServerPlayer owner, CompanionEntity npc, CompoundTag t,
            String tool, String ref, JsonObject snapshot, BlockPos origin) {
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
        if (tool.equals("finish")) {
            if (AgenticTaskPolicy.verifiedComplete(itemCount(npc, item), t.getInt("initial"),
                    t.getInt("goal"), TreeScaffold.size(t))) {
                int count = collected(npc, t);
                finish(npc, t);
                tell(owner, npc, "[Agentic task complete: " + count + "/" + t.getInt("goal")
                        + " " + item + " verified in NPC bag; items NOT transferred.]");
                return;
            }
            outcome = "[Action failed: finish denied; insufficient NPC inventory or scaffold remains.]";
        } else if (tool.equals("pause")) {
            tell(owner, npc, pause(npc, "Qwen requested owner intervention"));
            return;
        } else if (tool.equals("wait")) {
            outcome = "[Action confirmed: waited for new server observation.]";
        } else if (tool.equals("pickup")) {
            int remaining = t.getInt("goal") - collected(npc, t);
            int taken = WorldActions.pickupForGather(owner, npc, item, remaining);
            outcome = taken > 0 ? "[Action confirmed: picked up " + taken + " " + item + " into NPC bag.]"
                    : "[Action failed: no reachable drops or NPC inventory full.]";
        } else if (tool.equals("approach_drop")) {
            ItemEntity drop = BrainObservation.resolveDrop(owner, snapshot, ref, item);
            if (drop == null || npc.distanceToSqr(drop) > 144.0D)
                outcome = "[Action failed: dropped item no longer available.]";
            else {
                outcome = BrainSkills.approachDrop(owner, npc, drop, t);
                if (outcome.startsWith("[Action started:")) {
                    t.putString("state", "navigating_drop");
                    t.putUUID("drop", drop.getUUID());
                    t.putLong("navStart", npc.level().getGameTime());
                    recordNavOrigin(npc, t);
                    t.putString("lastResult", outcome);
                    save(npc, t);
                    return;
                }
            }
        } else if (tool.equals("descend")) {
            if (TreeScaffold.size(t) == 0)
                outcome = "[Action confirmed: no scaffold remains.]";
            else {
                t.putString("state", "cleanup");
                t.putString("lastResult", "[Action started: dismantle only recorded scaffold blocks.]");
                save(npc, t);
                return;
            }
        } else if (!BrainSkills.validBlock(owner, npc, pos, item, t)) {
            outcome = "[Action blocked: target changed, unloaded, out of home range or not authorized.]";
        } else if (tool.equals("mine")) {
            if (t.getInt("mined") >= t.getInt("goal"))
                outcome = "[Action blocked: enough blocks mined; collect drops before mining more.]";
            else {
                outcome = BrainSkills.mine(owner, npc, pos, item);
                if (outcome.startsWith("[Action confirmed:")) t.putInt("mined", t.getInt("mined") + 1);
            }
        } else if (tool.equals("clear_leaf")) {
            outcome = BrainSkills.clearLeaf(owner, npc, pos);
        } else if (tool.equals("approach")) {
            outcome = BrainSkills.approach(owner, npc, pos, t);
            if (outcome.startsWith("[Action started:")) {
                t.putString("state", "navigating_block");
                t.putInt("targetX", pos.getX()); t.putInt("targetY", pos.getY()); t.putInt("targetZ", pos.getZ());
                t.putLong("navStart", npc.level().getGameTime());
                recordNavOrigin(npc, t);
                t.putString("lastResult", outcome);
                save(npc, t);
                return;
            }
        } else if (tool.equals("climb")) {
            if (!"minecraft:oak_log".equals(item) || (t.getBoolean("treeLocked")
                    && (t.getInt("treeX") != pos.getX() || t.getInt("treeZ") != pos.getZ())))
                outcome = "[Action blocked: another tree has an active task-owned scaffold.]";
            else {
                int base = pos.getY();
                for (int y = pos.getY() - 1; y >= Math.max(owner.serverLevel().getMinBuildHeight(), pos.getY() - 8); y--) {
                    BlockPos below = new BlockPos(pos.getX(), y, pos.getZ());
                    if (!owner.serverLevel().isLoaded(below) || !owner.serverLevel().getBlockState(below)
                            .is(net.minecraft.world.level.block.Blocks.OAK_LOG)) break;
                    base = y;
                }
                t.putBoolean("treeLocked", true);
                t.putInt("treeX", pos.getX()); t.putInt("treeZ", pos.getZ()); t.putInt("treeBaseY", base);
                t.putString("state", "scaffold_prepare");
                t.putLong("navStart", npc.level().getGameTime());
                save(npc, t);
                return;
            }
        } else {
            outcome = "[Action blocked: unknown tool.]";
        }
        // State-changing effects are verified by the Minecraft executor, not the
        // LLM reason string. Navigation progress is measured independently below.
        boolean changed = outcome.startsWith("[Action confirmed:")
                && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf"));
        result(npc, t, tool, ref, outcome, changed);
        if ("paused".equals(t.getString("state"))) tell(owner, npc, status(npc));
    }
    private static void recordNavOrigin(CompanionEntity npc, CompoundTag t) {
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
        JsonObject world = BrainObservation.capture(owner, npc, t.getString("item"));
        int readyDrops = 0;
        var observed = world.getAsJsonArray("drops");
        for (var entry : observed) {
            if (entry.getAsJsonObject().get("pickup_reachable").getAsBoolean()) readyDrops++;
        }
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
                    || now.getInt("calls") != generation || !"thinking".equals(now.getString("state"))) return;
            if (!BrainSkills.authorized(online, npc)) {
                tell(online, npc, pause(npc, "owner permissions or world-edit safeguard changed")); return;
            }
            if (error != null || decision == null) {
                tell(online, npc, pause(npc, "Qwen unavailable or invalid structured decision")); return;
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
            ServerPlayer owner = npc.getOwnerUUID() == null ? null
                    : level.getServer().getPlayerList().getPlayer(npc.getOwnerUUID());
            if (owner == null || owner.level() != level || !owner.isAlive()) {
                npc.getNavigation().stop(); continue;
            }
            if ("paused".equals(phase)) continue;
            if ("cleanup".equals(phase) && !BrainSkills.authorized(owner, npc)) {
                tell(owner, npc, pause(npc, "owner or world-edit permission unavailable for cleanup"));
                continue;
            }
            if ("cleanup".equals(phase)) {
                String feedback = TreeScaffold.dismantle(level, npc, t);
                if (TreeScaffold.DONE.equals(feedback)) {
                    if (t.getBoolean("cancelRequested")) {
                        finish(npc, t);
                        tell(owner, npc, "[Agentic task cancelled; scaffold safely dismantled; bag retained.]");
                    } else {
                        t.remove("treeLocked");
                        t.remove("pillarX"); t.remove("pillarY"); t.remove("pillarZ");
                        result(npc, t, "descend", "", "[Action confirmed: recorded scaffold dismantled safely.]", true);
                    }
                } else if (feedback.startsWith(TreeScaffold.BLOCKED))
                    tell(owner, npc, pause(npc, "scaffold cleanup unsafe: " + feedback));
                else save(npc, t);
                continue;
            }
            if (!BrainSkills.authorized(owner, npc)) {
                tell(owner, npc, pause(npc, "owner moved away or safety rules changed")); continue;
            }
            if (level.getGameTime() - t.getLong("started") > MAX_TICKS
                    || ("ready".equals(phase) && !AgenticTaskPolicy.canPlan(
                            t.getInt("calls"), t.getInt("failures"), t.getInt("sameFailures"), t.getInt("stalls")))) {
                tell(owner, npc, pause(npc, "task time/LLM/replanning budget exhausted")); continue;
            }
            if ("scaffold_prepare".equals(phase) || "scaffold_jump".equals(phase)) {
                if (level.getGameTime() - t.getLong("navStart") > NAV_TIMEOUT) {
                    result(npc, t, "climb", "", "[Action failed: guarded scaffold skill timed out.]", false);
                    continue;
                }
                String outcome = "scaffold_jump".equals(phase)
                        ? TreeScaffold.tickJump(level, owner, npc, t, level.getGameTime())
                        : TreeScaffold.ascend(level, owner, npc, t, level.getGameTime());
                if (TreeScaffold.DONE.equals(outcome)) {
                    if ("scaffold_jump".equals(phase))
                        result(npc, t, "climb", "", "[Action confirmed: climbed one verified scaffold level.]", true);
                    else { t.putString("state", "scaffold_jump"); save(npc, t); }
                } else if (outcome.startsWith(TreeScaffold.BLOCKED)) {
                    result(npc, t, "climb", "", "[Action failed: " + outcome + "]", false);
                } else save(npc, t);
                continue;
            }
            if ("navigating_block".equals(phase) || "navigating_drop".equals(phase)) {
                boolean reached = false;
                if ("navigating_block".equals(phase)) {
                    BlockPos target = new BlockPos(t.getInt("targetX"), t.getInt("targetY"), t.getInt("targetZ"));
                    reached = BrainSkills.validBlock(owner, npc, target, t.getString("item"), t)
                            && npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))
                                  <= InteractionReach.squared(owner.isCreative())
                            && WorldBlockActions.visible(npc, target);
                } else {
                    var drop = level.getEntity(t.getUUID("drop"));
                    reached = drop instanceof ItemEntity item
                            && WorldActions.reachableGatherDrop(npc, item, owner.isCreative());
                }
                if (reached) {
                    boolean moved = navigationMoved(npc, t);
                    result(npc, t, "approach", "",
                            moved ? "[Action confirmed: navigated to a newly reachable position.]"
                                  : "[Action unchanged: destination reachable, but NPC position did not change.]",
                            moved);
                }
                else if (npc.getNavigation().isDone() || level.getGameTime() - t.getLong("navStart") > NAV_TIMEOUT) {
                    npc.getNavigation().stop();
                    result(npc, t, "approach", "", "[Action failed: navigation ended before target became reachable.]", false);
                }
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
            if (!"ready".equals(phase) || level.getGameTime() < t.getLong("nextAt")
                    || level.getGameTime() % POLL_TICKS != 0) continue;
            request(owner, npc, t);
        }
    }
}
