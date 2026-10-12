package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import io.github.sirp00m.npcortex.network.DialogueReplyPacket;
import io.github.sirp00m.npcortex.network.DialogueRequestPacket;
import io.github.sirp00m.npcortex.network.NpcNetwork;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.PacketDistributor;

/** Per-NPC bounded, sequential plans. All game-world accesses occur on the server thread.
 *  Add actions in WorldActions and ChatBridge. The runner needs no new branches
 *  for actions that report [Action confirmed: ...] immediately.
 */
public final class ActionPlanRunner {
    private static final int TIMEOUT_TICKS = 120; // 6 seconds of game time per async step
    private static final Map<UUID, RunningPlan> ACTIVE = new HashMap<>();

    private ActionPlanRunner() {}

    private static final class RunningPlan {
        final UUID npcId;
        final UUID ownerId;
        final int entityId;
        final ResourceKey<Level> dimension;
        final List<ChatBridge.ActionStep> steps;
        final String playerMessage;
        final String actionLabel;
        final int revision;
        int nextStep = 0;
        String waitingAction = null;
        long startTick;
        Vec3 startPosition;
        RunningPlan(ServerPlayer owner, CompanionEntity npc, List<ChatBridge.ActionStep> actions,
                String playerMessage, int revision, String actionLabel) {
            this.playerMessage = playerMessage;
            this.revision = revision;
            this.actionLabel = actionLabel;
            npcId = npc.getUUID();
            ownerId = owner.getUUID();
            entityId = npc.getId();
            dimension = npc.level().dimension();
            steps = List.copyOf(actions);
        }
    }

    /** Autonomous activities must not interrupt player-directed queued actions. */
    public static boolean isBusy(CompanionEntity npc) {
        return ACTIVE.containsKey(npc.getUUID());
    }

    /** Cancel pending work when the owner explicitly overrides an NPC's behavior. */
    public static boolean cancel(CompanionEntity npc) {
        RunningPlan removed = ACTIVE.remove(npc.getUUID());
        if (removed != null) npc.getNavigation().stop();
        return removed != null;
    }

    public static String begin(ServerPlayer owner, CompanionEntity npc,
            List<ChatBridge.ActionStep> steps, String playerMessage,
            int revision, String actionLabel) {
        if (steps == null || steps.size() > ChatBridge.MAX_PLAN_STEPS)
            return "[Plan rejected: invalid number of steps.]";
        if (steps.isEmpty()) return "";
        if (AgenticTaskController.isBusy(npc)) return "[Plan busy: NPC is running an agentic task.]";
        if (GatherTaskController.isBusy(npc)) {
            String first = steps.get(0).action();
            if (steps.size() == 1 && ("follow".equals(first) || "stay".equals(first)))
                GatherTaskController.cancel(npc);
            else return "[Plan busy: NPC is gathering. Cancel that task first.]";
        }
        if (ACTIVE.containsKey(npc.getUUID())) {
            // Single-step follow/stay is an explicit owner override, not a queued task.
            String first = steps.get(0).action();
            if (steps.size() == 1 && ("stay".equals(first) || "follow".equals(first)))
                cancel(npc);
            else
                return "[Plan busy: this NPC is completing an earlier task.]";
        }
        RunningPlan plan = new RunningPlan(owner, npc, steps, playerMessage,
                revision, actionLabel);
        ACTIVE.put(npc.getUUID(), plan);
        // Immediate steps execute now; navigation waits until a later world tick.
        return advance(owner, npc, plan);
    }

    private static String advance(ServerPlayer owner, CompanionEntity npc, RunningPlan plan) {
        StringBuilder feedback = new StringBuilder();
        while (plan.nextStep < plan.steps.size()) {
            ChatBridge.ActionStep step = plan.steps.get(plan.nextStep);
            String status = WorldActions.execute(owner, npc, step.action(), step.targetItem());
            String line = "[Step " + (plan.nextStep + 1) + "/" + plan.steps.size()
                    + "] " + status;
            if (feedback.length() > 0) feedback.append("\n");
            feedback.append(line);
            if (status.startsWith("[Action confirmed:")) {
                plan.nextStep++;
                continue;
            }
            if (status.startsWith("[Action started:") && isDeferred(step.action())) {
                plan.waitingAction = step.action();
                plan.startTick = npc.level().getGameTime();
                plan.startPosition = npc.position();
                return feedback.toString() + "\n[Plan waiting for this action to complete.]";
            }
            ACTIVE.remove(plan.npcId);
            return feedback.toString() + "\n[Plan stopped: step failed or was blocked.]";
        }
        ACTIVE.remove(plan.npcId);
        if (feedback.length() > 0) feedback.append("\n");
        return feedback.append("[Plan complete: ").append(plan.steps.size())
                .append("/").append(plan.steps.size()).append(" steps confirmed.]").toString();
    }

    private static boolean isDeferred(String action) {
        return "move_to_player".equals(action) || "wander".equals(action) || "jump".equals(action);
    }

    /** Subscribed once to the Forge event bus from NPCortex's constructor. */
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)
                || ACTIVE.isEmpty()) return;
        List<RunningPlan> pending = new ArrayList<>(ACTIVE.values());
        for (RunningPlan plan : pending) {
            if (ACTIVE.get(plan.npcId) != plan) continue;
            // Resolve only in the originating dimension and release orphaned plans.
            if (!level.dimension().equals(plan.dimension)) continue;
            var candidate = level.getEntity(plan.npcId);
            if (!(candidate instanceof CompanionEntity npc)) {
                ACTIVE.remove(plan.npcId);
                continue;
            }
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(plan.ownerId);
            if (owner == null || owner.level() != level || npc.isRemoved() || !npc.isAlive()
                    || !owner.isAlive() || !plan.ownerId.equals(npc.getOwnerUUID())
                    || owner.distanceToSqr(npc) > 64.0D) {
                npc.getNavigation().stop();
                ACTIVE.remove(plan.npcId);
                if (owner != null) notifyOwner(owner, plan, "[Plan cancelled: owner or NPC unavailable / too far away.]");
                continue;
            }
            int elapsed = (int) (level.getGameTime() - plan.startTick);
            if (elapsed > TIMEOUT_TICKS) {
                npc.getNavigation().stop();
                ACTIVE.remove(plan.npcId);
                notifyOwner(owner, plan, "[Plan stopped: action timed out.]");
                continue;
            }
            boolean completed = switch (plan.waitingAction) {
                case "move_to_player" -> owner.distanceToSqr(npc) <= 9.0D;
                case "wander" -> elapsed >= 2 && npc.getNavigation().isDone()
                        && npc.position().distanceToSqr(plan.startPosition) >= 1.0D;
                case "jump" -> elapsed >= 3 && npc.onGround();
                default -> false;
            };
            if (!completed) {
                // If navigation gives up, don't misreport arrival or continue.
                if (("move_to_player".equals(plan.waitingAction) || "wander".equals(plan.waitingAction))
                        && elapsed >= 5 && npc.getNavigation().isDone()) {
                    ACTIVE.remove(plan.npcId);
                    notifyOwner(owner, plan, "[Plan stopped: navigation ended before the goal was reached.]");
                }
                continue;
            }
            plan.nextStep++;
            plan.waitingAction = null;
            String result = "[Step " + plan.nextStep + "/" + plan.steps.size()
                    + " confirmed after movement.]\n" + advance(owner, npc, plan);
            notifyOwner(owner, plan, result);
        }
    }

    private static void notifyOwner(ServerPlayer owner, RunningPlan plan, String message) {
        owner.sendSystemMessage(Component.literal("NPCortex: " + message));
        NpcNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner),
                new DialogueReplyPacket(plan.entityId, message, true));
        // Only request character dialogue after an asynchronous step really ended.
        // Immediate action statuses use the same speech bridge in DialogueRequestPacket.
        var entity = owner.serverLevel().getEntity(plan.npcId);
        if (entity instanceof CompanionEntity npc && !npc.isRemoved()) {
            DialogueRequestPacket.speakAfterAction(owner, npc, plan.entityId,
                    plan.playerMessage, plan.actionLabel, message, plan.revision);
        }
    }
}
