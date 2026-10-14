package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.agent.Navigation3D;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Worksite recovery: Qwen elects to revisit the LAST verified tree, the server
 * selects only a connected collision-safe walk segment toward its remembered
 * root. No teleport, item generation, unrelated exploration or forced mining. */
final class GoalRecoverySkills {
    private GoalRecoverySkills() {}

    static BlockPos root(CompoundTag t) {
        if(t.contains("recoveryRoot")) return BlockPos.of(t.getLong("recoveryRoot"));
        // Backward-compatible recovery for Fix7 saves paused mid-committed tree.
        if(t.contains("wholeTreeRootX") && t.contains("wholeTreeRootY") && t.contains("wholeTreeRootZ"))
            return new BlockPos(t.getInt("wholeTreeRootX"),t.getInt("wholeTreeRootY"),t.getInt("wholeTreeRootZ"));
        return null;
    }
    static boolean isAtWorksite(CompanionEntity npc, CompoundTag t) {
        BlockPos root=root(t);
        return root!=null && npc.blockPosition().distSqr(root) <= 36.0D;
    }
    static boolean eligible(ServerPlayer owner, CompanionEntity npc, CompoundTag task) {
        BlockPos root=root(task);
        return root!=null && npc.onGround() && !Navigation3DAccess.needsCleanup(task)
            && TreeScaffold.size(task)==0 && TaskWorldBounds.allows(owner,root)
            && !isAtWorksite(npc,task);
    }
    static void recover_worksite(BrainSkillRegistry.Context c,String ref) {
        var t=c.task(); var npc=c.npc();
        t.putString("lastChoice","recover_worksite");
        if (!eligible(c.owner(),npc,t)) {
            AgenticTaskController.result(npc,t,"recover_worksite","",
                "[Action failed: safe grounded worksite recovery unavailable.]",false);
            return;
        }
        BlockPos root=root(t), at=npc.blockPosition();
        var routes=Navigation3DAccess.terrainRoutes(c.owner(),npc,t);
        var next=routes.destinations().stream()
            .map(v->new BlockPos(v.x(),v.y(),v.z()))
            .filter(p -> !p.equals(at))
            .filter(p -> p.distSqr(root)+1.0D < at.distSqr(root))
            .min(Comparator.comparingDouble(p->p.distSqr(root)))
            .orElse(null);
        if(next==null) {
            AgenticTaskController.result(npc,t,"recover_worksite","",
                "[Action failed: no closer grounded path to verified tree worksite within bounded search.]",false);
            return;
        }
        String outcome=Navigation3DAccess.startWaypoint(c.owner(),npc,t,next);
        if(outcome.startsWith("[Action started:")) {
            t.putString("state","access_3d");
            t.putString("lastResult",outcome);
            AgenticTaskController.save(npc,t);
        } else AgenticTaskController.result(npc,t,"recover_worksite","",outcome,false);
    }
}
