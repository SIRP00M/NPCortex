package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.agent.EventBus;
import io.github.sirp00m.npcortex.agent.GoalState;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Shared lifecycle/ownership adapter for the three existing task front ends.
 * NBT is authoritative, so unload/reload never loses an ownership lock.
 * Task-specific progress remains in the adapter until each goal type is migrated.
 */
public final class AgentRuntime {
    public static final EventBus EVENTS = new EventBus();
    private AgentRuntime() {}
    public static boolean isBusy(CompanionEntity npc) {
        return AgenticTaskController.isBusy(npc) || GatherTaskController.isBusy(npc)
                || ActionPlanRunner.isBusy(npc);
    }
    public static void event(CompanionEntity npc, CompoundTag task, String type, String detail) {
        String bounded = detail.substring(0, Math.min(240, detail.length()));
        String goal = task.hasUUID("taskId") ? task.getUUID("taskId").toString() : "legacy";
        EVENTS.publish(new EventBus.Event(npc.getUUID(), goal, type, bounded, npc.level().getGameTime()));
        ListTag journal = task.getList("runtimeEvents", Tag.TAG_COMPOUND);
        if (journal.size() >= 32) journal.remove(0);
        CompoundTag entry = new CompoundTag();
        entry.putString("type", type); entry.putString("detail", bounded);
        entry.putLong("tick", npc.level().getGameTime());
        journal.add(entry); task.put("runtimeEvents", journal);
    }
    public static void transition(CompanionEntity npc, CompoundTag task, GoalState next) {
        GoalState previous;
        try { previous = GoalState.valueOf(task.getString("goalState")); }
        catch (IllegalArgumentException ex) { previous = task.contains("goalState") ? GoalState.PAUSED : GoalState.phase(task.getString("state")); }
        if (!previous.permits(next)) {
            // Corrupted/unknown saved transitions fail closed and retain the ledger.
            task.putString("state", "paused");
            task.putString("lastResult", "[Runtime paused: invalid lifecycle transition " + previous + " -> " + next + ".]");
            next = GoalState.PAUSED;
        }
        if (previous != next) event(npc, task, "goal_state", previous + " -> " + next);
        task.putString("goalState", next.name());
    }
    public static void savePhase(CompanionEntity npc, CompoundTag task) {
        transition(npc, task, GoalState.phase(task.getString("state")));
    }
    public static void clearTransient(net.minecraftforge.event.server.ServerStoppedEvent event) {
        EVENTS.clear();
        ActionPlanRunner.clearTransient();
        AgenticTaskController.clearTransient();
    }
}
