package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Physical, server-owned item attraction and pickup for all NPCs.
 * Runs independently of Qwen, dialogue, task state, follow and autonomy.
 * Only ground item entities are handled here; XP is a separate feature.
 */
public final class CompanionItemPickup {
    private static final String INTENTIONAL_DROPPER = "NPCortexDroppedBy";
    private static final double MAGNET_RADIUS = io.github.sirp00m.npcortex.agent.MaterialApproach.PICKUP_RADIUS;
    private static final double MAGNET_RADIUS_SQ = MAGNET_RADIUS * MAGNET_RADIUS;
    private static final double CONTACT_RADIUS_SQ = 1.5D * 1.5D;
    private CompanionItemPickup() {}

    /** Mark a deliberate NPC inventory drop. Automatic pickup must not undo it.
     * The player can pick it up; an explicit /npcortex item pickup may override it.
     */
    public static void intentionalDrop(CompanionEntity npc, ItemEntity drop) {
        if (drop == null) return;
        drop.getPersistentData().putUUID(INTENTIONAL_DROPPER, npc.getUUID());
        drop.setPickUpDelay(40);
        Vec3 look = npc.getLookAngle();
        drop.setDeltaMovement(look.x * 0.28D, 0.18D, look.z * 0.28D);
    }

    public static boolean isDeliberatelyDroppedBy(CompanionEntity npc, ItemEntity drop) {
        CompoundTag data = drop.getPersistentData();
        return data.hasUUID(INTENTIONAL_DROPPER)
                && npc.getUUID().equals(data.getUUID(INTENTIONAL_DROPPER));
    }

    public static void tick(CompanionEntity npc) {
        if (npc.level().isClientSide || !npc.isAlive() || npc.tickCount % 2 != 0) return;
        Vec3 center = npc.position().add(0.0D, npc.getBbHeight() * 0.45D, 0.0D);
        List<ItemEntity> drops = npc.level().getEntitiesOfClass(ItemEntity.class,
                npc.getBoundingBox().inflate(MAGNET_RADIUS, 1.25D, MAGNET_RADIUS),
                drop -> drop.isAlive() && !drop.getItem().isEmpty() && !drop.hasPickUpDelay());
        drops.sort(Comparator.comparingDouble(drop -> drop.position().distanceToSqr(center)));
        int handled = 0;
        for (ItemEntity drop : drops) {
            if (++handled > 32) break; // bounded work per NPC/tick
            if (isDeliberatelyDroppedBy(npc, drop)) continue;
            ItemStack stack = drop.getItem();
            if (!canStore(npc, stack)) continue;
            Vec3 target = drop.getBoundingBox().getCenter();
            double dSq = center.distanceToSqr(target);
            if (dSq > MAGNET_RADIUS_SQ || !lineOfSight(npc, target)) continue;
            if (dSq <= CONTACT_RADIUS_SQ) {
                ItemStack remainder = npc.bag().addItem(stack.copy());
                int collected = stack.getCount() - remainder.getCount();
                if (collected <= 0) continue;
                if (remainder.isEmpty()) drop.discard();
                else drop.setItem(remainder);
                npc.level().playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                        SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
                        0.9F + npc.getRandom().nextFloat() * 0.2F);
            } else {
                // Smooth attraction over multiple game ticks; no teleporting items.
                Vec3 toward = center.subtract(target);
                if (toward.lengthSqr() > 1.0E-6D) {
                    Vec3 velocity = drop.getDeltaMovement().scale(0.85D)
                            .add(toward.normalize().scale(0.075D));
                    drop.setDeltaMovement(velocity);
                    drop.hasImpulse = true;
                }
            }
        }
    }

    private static boolean lineOfSight(CompanionEntity npc, Vec3 item) {
        return npc.level().clip(new ClipContext(npc.getEyePosition(), item,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc)).getType()
                == HitResult.Type.MISS;
    }

    public static boolean canStore(CompanionEntity npc, ItemStack stack) {
        for (int slot = 0; slot < npc.bag().getContainerSize(); slot++) {
            ItemStack existing = npc.bag().getItem(slot);
            if (existing.isEmpty() || (ItemStack.isSameItemSameTags(existing, stack)
                    && existing.getCount() < existing.getMaxStackSize())) return true;
        }
        return false;
    }
}
