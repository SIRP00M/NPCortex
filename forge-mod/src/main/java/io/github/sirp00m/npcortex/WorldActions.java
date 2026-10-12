package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;

/** Validated one-step NPC actions, always applied on Minecraft's server thread. */
public final class WorldActions {
    private WorldActions() {}

    public static String execute(ServerPlayer player, CompanionEntity npc, String action, String targetItem) {
        if ("none".equals(action)) return "";
        if (!ActionCatalog.validTarget(action, targetItem))
            return "[Action blocked: action or target not registered.]";
        if (npc.isRemoved() || npc.level() != player.level() ||
                player.distanceToSqr(npc) > 64.0D || !player.isAlive() || !npc.isAlive() ||
                !player.getUUID().equals(npc.getOwnerUUID())) {
            return "[Action blocked: only the nearby owner can control this NPC.]";
        }
        // An explicit owner command always supersedes a temporary autonomous retreat.
        npc.clearRetreat();
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
            case "pickup_nearby" -> pickup(npc, targetItem);
            case "equip_item" -> equip(npc, targetItem);
            case "drop_held_item" -> drop(npc);
            case "drop_stored_item" -> dropStored(npc, targetItem);
            case "take_offered_item" -> takeOffered(player, npc, targetItem);
            case "give_item_to_owner" -> giveBack(player, npc, targetItem);
            case "mine_nearby" -> WorldBlockActions.mine(player, npc, targetItem);
            case "place_block" -> WorldBlockActions.place(player, npc, targetItem);
            case "craft_item" -> WorldCrafting.craft(npc, targetItem);
            default -> "[Action blocked: unknown action.]";
        };
    }

    /** Legacy immediate executor for developer diagnostics. Dialogue uses ActionPlanRunner. */
    public static String executePlan(ServerPlayer player, CompanionEntity npc,
            List<ChatBridge.ActionStep> steps) {
        if (steps == null || steps.size() > ChatBridge.MAX_PLAN_STEPS)
            return "[Action blocked: invalid plan size.]";
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < steps.size(); ++i) {
            ChatBridge.ActionStep step = steps.get(i);
            String status = execute(player, npc, step.action(), step.targetItem());
            if (output.length() > 0) output.append("\n");
            output.append("[Step ").append(i + 1).append("/").append(steps.size())
                    .append("] ").append(status);
            if (!status.startsWith("[Action confirmed:")) {
                if (i + 1 < steps.size()) output.append("\n[Plan paused/stopped: step not confirmed.] ");
                break;
            }
        }
        return output.toString();
    }

    private static String takeOffered(ServerPlayer player, CompanionEntity npc, String targetItem) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return "[Action failed: owner holds no item.]";
        if (targetItem != null && !targetItem.isEmpty()
                && !BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(targetItem))
            return "[Action failed: owner is no longer holding requested item.]";
        ItemStack single = held.copy();
        single.setCount(1);
        if (!npc.bag().addItem(single).isEmpty())
            return "[Action failed: NPC inventory is full.]";
        held.shrink(1);
        return "[Action confirmed: NPC received one item from owner.]";
    }

    /** Exact item IDs are chosen from the server-observed inventory, never client-provided NBT. */
    private static int findStoredSlot(CompanionEntity npc, String targetItem) {
        if (targetItem == null) targetItem = "";
        for (int i = 0; i < npc.bag().getContainerSize(); ++i) {
            ItemStack stored = npc.bag().getItem(i);
            if (stored.isEmpty()) continue;
            if (targetItem.isEmpty() ||
                    BuiltInRegistries.ITEM.getKey(stored.getItem()).toString().equals(targetItem)) return i;
        }
        return -1;
    }

    private static String missingItem(CompanionEntity npc, String targetItem) {
        return npc.storedSlots() == 0 ? "[Action failed: NPC inventory is empty.]"
                : "[Action failed: requested item " + targetItem + " is not in NPC bag or main hand.]";
    }

    private static boolean heldMatches(CompanionEntity npc, String targetItem) {
        ItemStack held = npc.getMainHandItem();
        return !held.isEmpty() && (targetItem == null || targetItem.isEmpty()
                || BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(targetItem));
    }

    /** Transfer one item from equipped hand, avoiding partial state changes on failure. */
    private static String giveFromHand(ServerPlayer player, CompanionEntity npc) {
        ItemStack held = npc.getMainHandItem();
        String item = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        ItemStack one = held.copy();
        one.setCount(1);
        if (!player.getInventory().add(one))
            return "[Action failed: owner's inventory is full.]";
        ItemStack remainder = held.copy();
        remainder.shrink(1);
        npc.setItemSlot(EquipmentSlot.MAINHAND, remainder);
        return "[Action confirmed: transferred 1 " + item + " from NPC hand to owner's inventory.]";
    }

    /** Equipped items live outside the nine-slot bag. Targeted transfers prefer
     *  the main hand when its item ID matches, then fall back to storage. */
    private static String giveBack(ServerPlayer player, CompanionEntity npc, String targetItem) {
        if (targetItem != null && !targetItem.isEmpty() && heldMatches(npc, targetItem))
            return giveFromHand(player, npc);
        int slot = findStoredSlot(npc, targetItem);
        if (slot < 0) return heldMatches(npc, targetItem) ? giveFromHand(player, npc)
                : missingItem(npc, targetItem);
        ItemStack stored = npc.bag().getItem(slot);
        String item = BuiltInRegistries.ITEM.getKey(stored.getItem()).toString();
        ItemStack one = stored.copy();
        one.setCount(1);
        if (!player.getInventory().add(one))
            return "[Action failed: owner's inventory is full.]";
        npc.bag().removeItem(slot, 1);
        return "[Action confirmed: transferred 1 " + item + " to owner's inventory (not dropped).]";
    }

    private static String dropOneFromHand(CompanionEntity npc) {
        ItemStack held = npc.getMainHandItem();
        String item = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        ItemStack one = held.copy();
        one.setCount(1);
        ItemEntity dropped = npc.spawnAtLocation(one);
        if (dropped == null) return "[Action failed: could not drop held item.]";
        ItemStack remainder = held.copy();
        remainder.shrink(1);
        npc.setItemSlot(EquipmentSlot.MAINHAND, remainder);
        return "[Action confirmed: dropped 1 " + item + " from NPC hand onto ground.]";
    }

    /** Drop a matching item from either the equipped hand or the NPC bag. */
    private static String dropStored(CompanionEntity npc, String targetItem) {
        if (targetItem != null && !targetItem.isEmpty() && heldMatches(npc, targetItem))
            return dropOneFromHand(npc);
        int slot = findStoredSlot(npc, targetItem);
        if (slot < 0) return heldMatches(npc, targetItem) ? dropOneFromHand(npc)
                : missingItem(npc, targetItem);
        ItemStack stored = npc.bag().getItem(slot);
        String item = BuiltInRegistries.ITEM.getKey(stored.getItem()).toString();
        ItemStack one = stored.copy();
        one.setCount(1);
        ItemEntity dropped = npc.spawnAtLocation(one);
        if (dropped == null) return "[Action failed: could not drop stored item.]";
        npc.bag().removeItem(slot, 1);
        return "[Action confirmed: dropped 1 " + item + " from NPC inventory onto ground.]";
    }

    private static String pickup(CompanionEntity npc, String targetItem) {
        List<ItemEntity> drops = npc.level().getEntitiesOfClass(ItemEntity.class,
                npc.getBoundingBox().inflate(3.0D), item -> item.isAlive() && !item.getItem().isEmpty()
                        && npc.distanceToSqr(item) <= 9.0D
                        && (targetItem == null || targetItem.isEmpty() ||
                            BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString().equals(targetItem)));
        drops.sort(Comparator.comparingDouble(npc::distanceToSqr));
        if (drops.isEmpty()) return "[Action failed: no matching dropped items within 3 blocks.]";
        ItemEntity target = drops.get(0);
        String itemId = BuiltInRegistries.ITEM.getKey(target.getItem().getItem()).toString();
        int before = target.getItem().getCount();
        ItemStack remaining = npc.bag().addItem(target.getItem().copy());
        int collected = before - remaining.getCount();
        if (collected <= 0) return "[Action failed: NPC inventory is full.]";
        if (remaining.isEmpty()) target.discard();
        else target.setItem(remaining);
        return "[Action confirmed: collected " + collected + " " + itemId + " into NPC inventory.]";
    }

    private static String equip(CompanionEntity npc, String targetItem) {
        if (targetItem != null && !targetItem.isEmpty() && heldMatches(npc, targetItem))
            return "[Action confirmed: requested item " + targetItem + " is already equipped.]";
        int slot = findStoredSlot(npc, targetItem);
        if (slot < 0) return missingItem(npc, targetItem);
        ItemStack stack = npc.bag().getItem(slot);
        ItemStack old = npc.getMainHandItem().copy();
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        npc.bag().setItem(slot, ItemStack.EMPTY);
        npc.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
        if (!old.isEmpty()) {
            ItemStack leftover = npc.bag().addItem(old);
            if (!leftover.isEmpty()) npc.spawnAtLocation(leftover);
        }
        return "[Action confirmed: equipped " + item + " from NPC inventory.]";
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
