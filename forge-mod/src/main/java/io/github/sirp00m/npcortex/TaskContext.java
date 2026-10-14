package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.agent.GoalCompletionLedger;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Server evidence for Qwen's working memory. Never selects or suppresses a skill.
 * Movement success is deliberately separate from goal/inventory progress.
 */
final class TaskContext {
    private TaskContext() {}
    static JsonObject observe(CompanionEntity npc, CompoundTag task) {
        int mined=task.getInt("mined"), collected=AgenticTaskController.collected(npc,task);
        if(!task.contains("goalProgressCall") || mined>task.getInt("goalBestMined")
                || collected>task.getInt("goalBestCollected")) task.putInt("goalProgressCall",task.getInt("calls"));
        task.putInt("goalBestMined",Math.max(mined,task.getInt("goalBestMined")));
        task.putInt("goalBestCollected",Math.max(collected,task.getInt("goalBestCollected")));
        int without=Math.max(0,task.getInt("calls")-task.getInt("goalProgressCall"));
        task.putInt("decisionsWithoutGoalProgress",without);
        JsonObject out=new JsonObject(), anchor=new JsonObject();
        BlockPos at=npc.blockPosition(), work=task.contains("workAnchor")?ExplorationSkills.workAnchor(task):at;
        anchor.addProperty("dx",work.getX()-at.getX());
        anchor.addProperty("dy",work.getY()-at.getY());
        anchor.addProperty("dz",work.getZ()-at.getZ());
        out.add("work_anchor",anchor);
        out.addProperty("usable_blocks",BuildMaterials.available(npc,task));
        out.addProperty("material_mined",task.getInt("materialMined"));
        out.addProperty("decisions_since_goal_progress",without);
        JsonArray recent=new JsonArray();
        for(var tag:task.getList("skillOutcomes",Tag.TAG_COMPOUND)) {
            CompoundTag row=(CompoundTag)tag;
            JsonObject entry=new JsonObject();
            for(String key:new String[]{"skill","result","reason"}) entry.addProperty(key,row.getString(key));
            for(String key:new String[]{"mined","collected","usable_blocks"}) entry.addProperty(key,row.getInt(key));
            recent.add(entry);
        }
        out.add("recent_actions",recent);
        int pending = npc.level() instanceof net.minecraft.server.level.ServerLevel sl
                ? WholeTreeTracker.pending(sl, task) : 0;
        int borrowed=Navigation3DAccess.borrowed(task,task.getString("item"));
        int temporary=Navigation3DAccess.size(task)+TreeScaffold.size(task)+WorldTerrainEdits.size(task);
        GoalCompletionLedger.Phase phase=GoalCompletionLedger.phase(WholeTreeTracker.isCommitted(task),
                pending,task.getInt("completedTrees"),mined,collected,borrowed,task.getInt("goal"),temporary);
        JsonObject completion=new JsonObject();
        completion.addProperty("phase",phase.name().toLowerCase(java.util.Locale.ROOT));
        completion.addProperty("missing_logs",GoalCompletionLedger.missing(mined,collected,borrowed));
        completion.addProperty("borrowed_logs",borrowed);
        completion.addProperty("temporary_blocks",temporary);
        completion.addProperty("tree_logs_remaining",pending);
        completion.addProperty("verified_trees",task.getInt("completedTrees"));
        completion.addProperty("worksite_distance",GoalRecoverySkills.root(task)==null ? -1
                : (int)Math.round(Math.sqrt(npc.blockPosition().distSqr(GoalRecoverySkills.root(task)))));
        out.add("goal_completion",completion);
        return out;
    }
    static void record(CompanionEntity npc, CompoundTag task, String skill, String feedback) {
        CompoundTag entry=new CompoundTag();
        entry.putString("skill",skill);
        entry.putString("result",feedback.substring(0,Math.min(feedback.length(),180)));
        String reason=task.getString("lastBrainReason");
        entry.putString("reason",reason.substring(0,Math.min(reason.length(),180)));
        entry.putInt("mined",task.getInt("mined"));
        entry.putInt("collected",AgenticTaskController.collected(npc,task));
        entry.putInt("usable_blocks",BuildMaterials.available(npc,task));
        ListTag recent=task.getList("skillOutcomes",Tag.TAG_COMPOUND);
        recent.add(entry); while(recent.size()>4) recent.remove(0);
        task.put("skillOutcomes",recent);
    }
    static String reachedFeedback(String skill) {
        return switch(skill) {
            case "navigate_material" -> "[Action confirmed: soil is safely within mining/pickup range; NO material mined by walking. Inspect mine_material in fresh choices.]";
            case "explore" -> "[Action confirmed: frontier reached; only position changed. Compare fresh resources, supplies and work anchor before exploring again.]";
            case "return_to_work" -> "[Action confirmed: saved work stand reached; inspect remaining task targets.]";
            case "recover_worksite" -> "[Action confirmed: walked a grounded segment closer to the verified tree; re-observe for physical task drops. No logs credited.]";
            case "navigate_3d_drop" -> "[Action confirmed: physical drop pickup verified in NPC bag.]";
            default -> "[Action confirmed: target within interaction reach; navigation did NOT mine it. Inspect fresh target choices.]";
        };
    }
}
