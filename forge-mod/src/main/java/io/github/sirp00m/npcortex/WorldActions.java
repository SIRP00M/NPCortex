package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;

/** Validated one-step NPC actions, always applied on Minecraft's server thread. */
public final class WorldActions {
    private WorldActions() {}

    public static String execute(ServerPlayer player, CompanionEntity npc, String action) {
        if ("none".equals(action)) return "";
        if (npc.isRemoved() || npc.level() != player.level() ||
                player.distanceToSqr(npc) > 64.0D || !player.isAlive() || !npc.isAlive() ||
                !player.getUUID().equals(npc.getOwnerUUID())) {
            return "[Action blocked: only the nearby owner can control this NPC.]";
        }
        return switch (action) {
            case "follow" -> {
                npc.setFollowing(true);
                yield "[Action confirmed: following owner.]";
            }
            case "stay" -> {
                npc.setFollowing(false);
                yield "[Action confirmed: staying here.]";
            }
            case "look_at_player" -> {
                npc.lookAtPlayerFor(100);
                yield "[Action confirmed: looking at owner for 5 seconds.]";
            }
            case "move_to_player" -> {
                npc.setFollowing(false);
                boolean started = npc.getNavigation().moveTo(player, 1.1D);
                yield started ? "[Action started: moving toward owner.]" : "[Action failed: no route to owner.]";
            }
            case "wander" -> {
                npc.setFollowing(false);
                Vec3 target = DefaultRandomPos.getPos(npc, 6, 3);
                boolean started = target != null &&
                        npc.getNavigation().moveTo(target.x, target.y, target.z, 1.0D);
                yield started ? "[Action started: short wander.]" : "[Action failed: no safe route nearby.]";
            }
            case "jump" -> {
                if (!npc.onGround()) yield "[Action failed: NPC is not on the ground.]";
                npc.getJumpControl().jump();
                yield "[Action started: jump requested.]";
            }
            case "pickup_nearby" -> pickup(npc);
            case "equip_item" -> equip(npc);
            case "drop_held_item" -> drop(npc);
            case "take_offered_item" -> takeOffered(player, npc);
            case "give_item_to_owner" -> giveBack(player, npc);
            default -> "[Action blocked: unknown action.]";
        };
    }

    private static String takeOffered(ServerPlayer player, CompanionEntity npc) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return "[Action failed: owner holds no item.]";
        ItemStack single = held.copy();
        single.setCount(1);
        if (!npc.bag().addItem(single).isEmpty())
            return "[Action failed: NPC inventory is full.]";
        held.shrink(1);
        return "[Action confirmed: NPC received one item from owner.]";
    }

    private static String giveBack(ServerPlayer player, CompanionEntity npc) {
        for (int i = 0; i < npc.bag().getContainerSize(); ++i) {
            ItemStack stored = npc.bag().getItem(i);
            if (stored.isEmpty()) continue;
            ItemStack one = stored.copy();
            one.setCount(1);
            if (!player.getInventory().add(one))
                return "[Action failed: owner's inventory is full.]";
            npc.bag().removeItem(i, 1);
            return "[Action confirmed: returned one stored item to owner.]";
        }
        return "[Action failed: NPC inventory is empty.]";
    }

    private static String pickup(CompanionEntity npc) {
        List<ItemEntity> drops = npc.level().getEntitiesOfClass(ItemEntity.class,
                npc.getBoundingBox().inflate(3.0D), item -> item.isAlive() && !item.getItem().isEmpty()
                        && npc.distanceToSqr(item) <= 9.0D);
        drops.sort(Comparator.comparingDouble(npc::distanceToSqr));
        if (drops.isEmpty()) return "[Action failed: no dropped items nearby.]";
        ItemEntity target = drops.get(0);
        int before = target.getItem().getCount();
        ItemStack remaining = npc.bag().addItem(target.getItem().copy());
        int collected = before - remaining.getCount();
        if (collected <= 0) return "[Action failed: NPC inventory is full.]";
        if (remaining.isEmpty()) target.discard();
        else target.setItem(remaining);
        return "[Action confirmed: collected " + collected + " item(s) in NPC inventory.]";
    }

    private static String equip(CompanionEntity npc) {
        for (int i = 0; i < npc.bag().getContainerSize(); ++i) {
            ItemStack stack = npc.bag().getItem(i);
            if (stack.isEmpty()) continue;
            ItemStack old = npc.getMainHandItem().copy();
            npc.bag().setItem(i, ItemStack.EMPTY);
            npc.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
            if (!old.isEmpty()) {
                ItemStack leftover = npc.bag().addItem(old);
                if (!leftover.isEmpty()) npc.spawnAtLocation(leftover);
            }
            return "[Action confirmed: equipped an item from NPC inventory.]";
        }
        return "[Action failed: NPC inventory is empty.]";
    }

    private static String drop(CompanionEntity npc) {
        ItemStack held = npc.getMainHandItem();
        if (held.isEmpty()) return "[Action failed: NPC holds no item.]";
        ItemEntity dropped = npc.spawnAtLocation(held.copy());
        if (dropped == null) return "[Action failed: could not drop held item.]";
        npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return "[Action confirmed: dropped held item nearby.]";
    }
}
