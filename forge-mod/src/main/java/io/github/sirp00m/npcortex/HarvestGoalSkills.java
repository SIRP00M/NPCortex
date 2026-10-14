package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import static io.github.sirp00m.npcortex.AgenticTaskController.*;

/** Harvest-goal adapter. Each registered skill has its own executor; the runtime
 * has no tool-name switch. New goal adapters may reuse navigation and inventory
 * primitives without changing the Qwen transport or registry lifecycle. */
final class HarvestGoalSkills {
    private HarvestGoalSkills() {}
    static void mine_material(BrainSkillRegistry.Context c, String ref) {
        BlockPos pos=BrainObservation.resolveMaterial(c.world(),ref,c.origin());
        CompoundTag task=c.task(); CompanionEntity npc=c.npc();
        task.putString("lastChoice","mine_material("+ref+")");
        String outcome=MaterialSource.mine(c.owner(),npc,task,pos);
        result(npc,task,"mine_material",ref,outcome,outcome.startsWith("[Action confirmed:"));
        if("paused".equals(task.getString("state"))) tell(c.owner(),npc,status(npc));
    }
    static void approach_material(BrainSkillRegistry.Context c, String ref) {
        BlockPos pos=BrainObservation.resolveMaterial(c.world(),ref,c.origin());
        CompoundTag task=c.task(); CompanionEntity npc=c.npc();
        task.putString("lastChoice","approach_material("+ref+")");
        if(!MaterialSource.eligible(c.owner(),npc,task,pos)) {
            result(npc,task,"approach_material",ref,"[Action blocked: observed material source changed.]",false); return;
        }
        String outcome=BrainSkills.approachMaterial(c.owner(),npc,pos,task);
        if(outcome.startsWith("[Action started:")) {
            task.putString("state","navigating_material");
            task.putInt("targetX",pos.getX()); task.putInt("targetY",pos.getY()); task.putInt("targetZ",pos.getZ());
            task.putLong("navStart",npc.level().getGameTime());
            recordNavOrigin(npc,task); task.putString("lastResult",outcome); save(npc,task); return;
        }
        result(npc,task,"approach_material",ref,outcome,false);
    }
    static void navigate_material(BrainSkillRegistry.Context c,String ref) {
        BlockPos p=BrainObservation.resolveMaterial(c.world(),ref,c.origin());
        String outcome=Navigation3DAccess.startMaterial(c.owner(),c.npc(),c.task(),p);
        c.task().putString("lastChoice","navigate_material("+ref+")");
        if(outcome.startsWith("[Action started:")) {
            c.task().putString("state","access_3d"); c.task().putString("lastResult",outcome); save(c.npc(),c.task());
        } else result(c.npc(),c.task(),"navigate_material",ref,outcome,false);
    }
    static void finish(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="finish";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
            if (t.getInt("completedTrees") > 0 && WholeTreePolicy.complete(
                    collected(npc, t), t.getInt("mined"), t.getInt("goal"),
                    WholeTreeTracker.pending(owner.serverLevel(), t),
                    TreeScaffold.size(t) + Navigation3DAccess.size(t) + WorldTerrainEdits.size(t))
                    && !Navigation3DAccess.needsCleanup(t)) {
                int count = collected(npc, t);
                AgenticTaskController.finish(npc, t);
                tell(owner, npc, "[Agentic task complete: " + count + "/" + t.getInt("goal")
                        + " " + item + " verified in NPC bag; items NOT transferred.]");
                return;
            }
            outcome = "[Action failed: finish denied; goal, complete tree, all mined logs collected or cleanup not verified.]";
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void pause(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="pause";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
            tell(owner, npc, AgenticTaskController.pause(npc,
                    "Qwen requested owner intervention (inspect brain_pause and offered options in task trace)"));
            return;
    }
    static void wait(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="wait";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
            outcome = "[Action confirmed: waited for new server observation.]";
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void pickup(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="pickup";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
            int remaining = Math.max(0, t.getInt("mined") - collected(npc, t));
            int taken = WorldActions.pickupForGather(owner, npc, item, remaining);
            outcome = taken > 0 ? "[Action confirmed: picked up " + taken + " " + item + " into NPC bag.]"
                    : "[Action failed: no reachable drops or NPC inventory full.]";
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void approach_drop(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="approach_drop";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
            ItemEntity drop = BrainObservation.resolveDrop(owner, snapshot, ref, item);
            if (drop == null || npc.distanceToSqr(drop) > 144.0D)
                outcome = "[Action failed: dropped item no longer available.]";
            else {
                outcome = BrainSkills.approachDrop(owner, npc, drop, t);
                if (outcome.startsWith("[Action started:")) {
                    t.putString("state", "navigating_drop");
                    t.putUUID("drop", drop.getUUID());
                    t.putInt("navCollectedBefore", collected(npc, t));
                    t.putLong("navStart", npc.level().getGameTime());
                    recordNavOrigin(npc, t);
                    t.putString("lastResult", outcome);
                    save(npc, t);
                    return;
                }
            }
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void restore(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="restore";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
            if (WorldTerrainEdits.size(t) == 0 && !Navigation3DAccess.needsCleanup(t) && TreeScaffold.size(t) == 0)
                outcome = "[Action unchanged: no task-owned terrain changes to restore.]";
            else {
                t.putString("state", "cleanup");
                t.putLong("cleanupStarted", npc.level().getGameTime());
                t.putString("lastResult", "[Action started: restore only recorded task terrain.] ");
                save(npc, t);
                return;
            }
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void descend(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="descend";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
            if (TreeScaffold.size(t) == 0 && !Navigation3DAccess.needsCleanup(t))
                outcome = "[Action confirmed: no scaffold remains.]";
            else {
                // A Qwen-chosen recovery descent DURING harvest is permitted
                // only after a failed bounded route. The original committed
                // tree remains assigned; after descent Qwen can replan from
                // a different position. Cap consecutive no-mine retreat cycles.
                if (WholeTreeTracker.isCommitted(t) && WholeTreeTracker.pending(owner.serverLevel(),t)>0) {
                    int retreats=t.getInt("retreatWithoutMine")+1;
                    t.putInt("retreatWithoutMine",retreats);
                    if (io.github.sirp00m.npcortex.agent.SafeRetreatPolicy.pauseAfterRetreat(retreats))
                        t.putBoolean("pauseAfterSafeRetreat",true);
                    AgentRuntime.event(npc,t,"harvest_replan_retreat",
                            "Guarded descent from blocked access; cycle="+retreats);
                }
                t.putBoolean("harvestNavBlocked",false);
                t.putString("state", "cleanup");
                t.putLong("cleanupStarted", npc.level().getGameTime());
                t.putString("lastResult", "[Action started: dismantle only recorded scaffold blocks.]");
                save(npc, t);
                return;
            }
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void mine(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="mine";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
        if (!BrainSkills.validBlock(owner,npc,pos,item,t)) {
            result(npc,t,tool,ref,"[Action blocked: target changed, unloaded or unauthorized.]",false);
            return;
        }
            // Inspect the tree BEFORE removing any logs. Once committed, only
            // known members can be destroyed. No Qwen-supplied coordinates.
            long[] proposedTree = null;
            if (!WholeTreeTracker.isCommitted(t)) {
                if (!WholeTreePolicy.canStartTree(t.getInt("completedTrees"), t.getInt("mined"),
                        collected(npc, t), t.getInt("goal")))
                    outcome = "[Action blocked: no additional tree is needed or task tree budget exhausted.]";
                else {
                    proposedTree = WholeTreeTracker.inspect(owner.serverLevel(), pos,
                            pos);
                    if (proposedTree == null || t.getInt("mined") + proposedTree.length > WholeTreePolicy.MAX_TASK_LOGS)
                        outcome = "[Action blocked: tree not verified natural/bounded, or too large for task budget.]";
                    else {
                        BlockPos root = WholeTreeTracker.verifiedRoot(owner.serverLevel(), proposedTree);
                        if (root == null) {
                            outcome = "[Action blocked: natural tree root could not be verified.]";
                        } else {
                            outcome = BrainSkills.mine(owner, npc, pos, item);
                            if (outcome.startsWith("[Action confirmed:")) {
                                // Record this BEFORE any later choice of a branch-log
                                // climb target. Tree root may already be air by then.
                                t.putInt("wholeTreeRootX", root.getX());
                                t.putInt("wholeTreeRootY", root.getY());
                                t.putInt("wholeTreeRootZ", root.getZ());
                                t.putLong("workAnchor",npc.blockPosition().asLong());
                                t.putLong("recoveryRoot",root.asLong());
                                WholeTreeTracker.commit(t, proposedTree);
                                t.putInt("mined", t.getInt("mined") + 1);
                            }
                        }
                    }
                }
            } else if (!WholeTreeTracker.contains(t, pos)
                    || !WholeTreePolicy.canMine(t.getInt("mined"),
                            WholeTreeTracker.pending(owner.serverLevel(), t)))
                outcome = "[Action blocked: not a remaining log on committed tree.]";
            else {
                outcome = BrainSkills.mine(owner, npc, pos, item);
                if (outcome.startsWith("[Action confirmed:")) t.putInt("mined", t.getInt("mined") + 1);
            }
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void clear_leaf(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="clear_leaf";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
        if (!BrainSkills.validBlock(owner,npc,pos,item,t)) {
            result(npc,t,tool,ref,"[Action blocked: target changed, unloaded or unauthorized.]",false);
            return;
        }
            outcome = BrainSkills.clearLeaf(owner, npc, pos);
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void clear_soft(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="clear_soft";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
        if (!BrainSkills.validBlock(owner,npc,pos,item,t)) {
            result(npc,t,tool,ref,"[Action blocked: target changed, unloaded or unauthorized.]",false);
            return;
        }
            if (!WholeTreeTracker.isCommitted(t))
                outcome = "[Action blocked: must commit a verified natural tree before soil excavation.]";
            else outcome = WorldTerrainEdits.clear(owner, npc, pos, t);
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void approach(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="approach";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
        if (!BrainSkills.validBlock(owner,npc,pos,item,t)) {
            result(npc,t,tool,ref,"[Action blocked: target changed, unloaded or unauthorized.]",false);
            return;
        }
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
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void navigate_3d_drop(BrainSkillRegistry.Context c, String ref) {
        CompoundTag task=c.task(); CompanionEntity npc=c.npc();
        task.putString("lastChoice","navigate_3d_drop("+ref+")");
        ItemEntity drop=BrainObservation.resolveDrop(c.owner(),c.world(),ref,task.getString("item"));
        if(drop==null || !CompanionMinedDrops.fromTask(npc,
                task.hasUUID("taskId")?task.getUUID("taskId"):null,drop)) {
            result(npc,task,"navigate_3d_drop",ref,"[Action blocked: drop provenance or entity changed.]",false);
            return;
        }
        String outcome=Navigation3DAccess.startDrop(c.owner(),npc,task,drop);
        if(outcome.startsWith("[Action started:")) {
            task.putString("state","access_3d"); task.putString("lastResult",outcome);
            save(npc,task); return;
        }
        result(npc,task,"navigate_3d_drop",ref,outcome,false);
    }
    static void navigate_3d(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="navigate_3d";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
        if (!BrainSkills.validBlock(owner,npc,pos,item,t)) {
            result(npc,t,tool,ref,"[Action blocked: target changed, unloaded or unauthorized.]",false);
            return;
        }
            if (!WholeTreeTracker.isCommitted(t) || !WholeTreeTracker.contains(t, pos))
                outcome = "[Action blocked: 3D access requires a committed observed goal block.]";
            else {
                outcome = Navigation3DAccess.start(owner,npc,t,pos);
                // Record an executor-verified route dead end, not merely the
                // model's textual hypothesis. Qwen may then choose to retreat.
                t.putBoolean("harvestNavBlocked",outcome.startsWith(
                        "[Action failed: bounded 3D search found no safe supported route"));
                if (outcome.startsWith("[Action started:")) {
                    t.putString("state", "access_3d"); t.putString("lastResult", outcome);
                    save(npc,t); return;
                }
            }
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
    static void climb(BrainSkillRegistry.Context c, String ref) {
        ServerPlayer owner=c.owner(); CompanionEntity npc=c.npc(); CompoundTag t=c.task();
        JsonObject snapshot=c.world(); BlockPos origin=c.origin();
        String tool="climb";
        String item = t.getString("item");
        t.putString("lastChoice", tool + (ref.isEmpty() ? "" : "(" + ref + ")"));
        BlockPos pos = BrainObservation.resolveBlock(snapshot, ref, origin, item);
        String outcome;
        if (!BrainSkills.validBlock(owner,npc,pos,item,t)) {
            result(npc,t,tool,ref,"[Action blocked: target changed, unloaded or unauthorized.]",false);
            return;
        }
            // The AI selects climb for a committed remaining log. The skill
            // anchors to the verified *root* rather than accidentally treating
            // a horizontal branch as a new vertical trunk.
            if (!WholeTreeTracker.isCommitted(t) || !WholeTreeTracker.contains(t, pos)
                    || !t.contains("wholeTreeRootX") || !t.contains("wholeTreeRootY")
                    || !t.contains("wholeTreeRootZ")) {
                outcome = "[Action blocked: no verified committed tree root for scaffold.]";
            } else if (TreeScaffold.size(t) >= ScaffoldPolicy.MAX_HEIGHT) {
                outcome = "[Action blocked: scaffold height limit reached; select another safe method.]";
            } else {
                t.putBoolean("treeLocked", true);
                t.putInt("treeX", t.getInt("wholeTreeRootX"));
                t.putInt("treeZ", t.getInt("wholeTreeRootZ"));
                t.putInt("treeBaseY", t.getInt("wholeTreeRootY"));
                t.putString("state", "scaffold_prepare");
                t.putLong("navStart", npc.level().getGameTime());
                t.putString("lastResult", "[Action started: guarded scaffold beside committed tree root, not branch target.]");
                save(npc, t);
                return;
            }
        boolean changed=outcome.startsWith("[Action confirmed:")
            && (tool.equals("mine") || tool.equals("pickup") || tool.equals("clear_leaf") || tool.equals("clear_soft"));
        result(npc,t,tool,ref,outcome,changed);
        if ("paused".equals(t.getString("state"))) tell(owner,npc,status(npc));
    }
}
