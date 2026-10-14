package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Generic, server-grounded material acquisition. No model-provided coordinates.
 * Resource excavation is NOT reversible, unlike scaffolding; world edits must be
 * enabled, inventory supply is observed and the source cannot be a load-bearing block
 * under either actor, near a locked tree root, or beneath a known scaffold.
 */
public final class MaterialSource {
    private MaterialSource() {}
    public static boolean eligible(ServerPlayer owner,CompanionEntity npc,CompoundTag task,BlockPos pos) {
        if (!BrainSkills.authorized(owner,npc) || pos==null || !TaskWorldBounds.allows(owner,pos)
                || !owner.serverLevel().isLoaded(pos) || !owner.serverLevel().isLoaded(pos.above())
                || !owner.serverLevel().isLoaded(pos.below())
                || !owner.serverLevel().getWorldBorder().isWithinBounds(pos)
                || owner.serverLevel().getBlockEntity(pos)!=null
                || pos.equals(npc.blockPosition().below()) || pos.equals(owner.blockPosition().below())) return false;
        var state=owner.serverLevel().getBlockState(pos);
        if (!state.is(Blocks.DIRT) && !state.is(Blocks.GRASS_BLOCK)) return false;
        if (!state.getFluidState().isEmpty() || state.getDestroySpeed(owner.serverLevel(),pos)<0) return false;
        // Avoid mining the support near committed tree root or under a task tower.
        if (WholeTreeTracker.isCommitted(task) && task.contains("wholeTreeRootX")
                && Math.abs(pos.getX()-task.getInt("wholeTreeRootX"))<=1
                && Math.abs(pos.getZ()-task.getInt("wholeTreeRootZ"))<=1
                && pos.getY()<=task.getInt("wholeTreeRootY")) return false;
        if (TreeScaffold.ownsPlacedBlock(task,pos) || Navigation3DAccess.ownsPlacedBlock(task,pos)
                || Navigation3DAccess.protectsSupport(task,pos)) return false;
        if(TreeScaffold.size(task)>0 && pos.equals(new BlockPos(task.getInt("pillarX"),
                task.getInt("pillarY")-1,task.getInt("pillarZ")))) return false;
        // Surface soil only: do not tunnel under structures or release fluids.
        if (!owner.serverLevel().getBlockState(pos.above()).isAir()) return false;
        for (var direction : net.minecraft.core.Direction.values()) {
            BlockPos neighbor=pos.relative(direction);
            if (!TaskWorldBounds.allows(owner,neighbor)
                    || !owner.serverLevel().getFluidState(neighbor).isEmpty()) return false;
        }
        return CompanionItemPickup.canStore(npc,new net.minecraft.world.item.ItemStack(Blocks.DIRT))
                && owner.serverLevel().getEntities(npc,new net.minecraft.world.phys.AABB(pos.above())).isEmpty()
                && !npc.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos.above()));
    }
    public static boolean reachable(ServerPlayer owner,CompanionEntity npc,BlockPos p) {
        return p!=null && reachableFrom(owner,npc,npc.getEyePosition(),p);
    }
    public static boolean reachableFrom(ServerPlayer owner,CompanionEntity npc,Vec3 eye,BlockPos p) {
        return io.github.sirp00m.npcortex.agent.MaterialApproach.reachable(
                eye.x,eye.y-npc.getEyeHeight(),eye.z,npc.getEyeHeight(),npc.getBbHeight(),npc.getBbWidth(),
                p.getX(),p.getY(),p.getZ(),InteractionReach.squared(owner.isCreative()))
                && WorldBlockActions.visibleFrom(npc,eye,p);
    }
    public static String mine(ServerPlayer owner,CompanionEntity npc,CompoundTag task,BlockPos p) {
        if (!eligible(owner,npc,task,p) || !reachable(owner,npc,p))
            return "[Action blocked: soil source changed, unsafe or out of pickup-safe reach.]";
        String id=BuiltInRegistries.BLOCK.getKey(owner.serverLevel().getBlockState(p).getBlock()).toString();
        String outcome=WorldBlockActions.mineAt(owner,npc,id,p);
        if (outcome.startsWith("[Action confirmed:")) {
            task.putInt("materialMined",task.getInt("materialMined")+1);
            AgentRuntime.event(npc,task,"material_mined",p.toShortString()+"; dirt drops physically (not instantly granted)");
        }
        return outcome;
    }
}
