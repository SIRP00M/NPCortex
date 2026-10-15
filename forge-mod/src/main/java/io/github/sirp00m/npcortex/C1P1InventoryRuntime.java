package io.github.sirp00m.npcortex;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * P1-B operator-controlled, server-thread-only transactions. Does not implement
 * AI world actions, player-to-player transfers or external transaction commits.
 * Inventory mutation, the snapshot and the receipt are performed as one in-memory
 * operation, and saved together in a single SavedData file at world save time.
 */
final class C1P1InventoryRuntime {
    @FunctionalInterface private interface Mutation { String apply(Inventory inventory); }
    private C1P1InventoryRuntime() {}

    static void restore(MinecraftServer server, C1PlayerAvatar avatar) {
        C1InventoryData.get(server).restore(avatar.getUUID(), avatar.getInventory());
    }

    private static C1PlayerAvatar live(MinecraftServer server, String name) {
        return C1P0Runtime.activeAvatar(server, name);
    }

    private static String transact(MinecraftServer server, String name, UUID id,
                                   String fingerprint, Mutation mutation) {
        C1PlayerAvatar avatar = live(server, name);
        if (avatar == null) return "NPC not live: " + name;
        C1InventoryData data = C1InventoryData.get(server);
        if (data.blockage(avatar.getUUID()) != null) {
            return "BLOCKED corrupt Inventory SavedData: " + data.blockage(avatar.getUUID());
        }
        C1InventoryData.Record record = data.find(avatar.getUUID());
        if (record != null) {
            C1InventoryJournal.Lookup lookup = record.journal.lookup(id, fingerprint);
            if (lookup == C1InventoryJournal.Lookup.REPLAY) {
                var receipt = record.journal.find(id);
                return "REPLAY (no mutation) tx=" + id + " revision=" + receipt.revision() + " " + receipt.detail();
            }
            if (lookup == C1InventoryJournal.Lookup.CONFLICT) return "CONFLICT: UUID was used for a different operation";
            if (lookup == C1InventoryJournal.Lookup.FULL) return "BLOCKED: 512 receipts stored; no unsafe auto-prune (migration required)";
        }
        Inventory inventory = avatar.getInventory();
        ListTag before = inventory.save(new ListTag());
        int selected = inventory.selected;
        try {
            String outcome = mutation.apply(inventory);
            if (outcome == null || outcome.isBlank()) throw new IllegalArgumentException("empty mutation result");
            data.commit(avatar.getUUID(), inventory, id, fingerprint, outcome);
            syncForActiveViewers(avatar);
            return "APPLIED_PENDING_WORLD_SAVE tx=" + id + " revision=" + data.find(avatar.getUUID()).journal.revision() + " " + outcome;
        } catch (RuntimeException error) {
            inventory.load(before);
            inventory.selected = selected;
            return "NOT_COMMITTED tx=" + id + " " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    static String give(MinecraftServer server, String name, ResourceLocation id, int count, UUID transaction) {
        if (count <= 0 || count > 64) return "Count must be 1..64";
        if (!BuiltInRegistries.ITEM.containsKey(id)) return "Unknown item " + id;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == net.minecraft.world.item.Items.AIR) return "Cannot give air";
        return transact(server, name, transaction, "give|" + id + "|" + count, inventory -> {
            ItemStack remaining = new ItemStack(item, count);
            inventory.add(remaining);
            if (!remaining.isEmpty()) throw new IllegalStateException("Insufficient inventory space");
            return "gave " + count + " " + id;
        });
    }

    /** Copies an operator-held fixture (including enchantments/durability/NBT), does not debit operator. */
    static String copyHand(MinecraftServer server, String name, ServerPlayer operator, int slot, UUID transaction) {
        if (slot < 0 || slot > 40) return "Slot must be 0..40";
        ItemStack held = operator.getMainHandItem().copy();
        if (held.isEmpty()) return "Operator must hold an item (fixture-copy test)";
        CompoundTag tag = held.save(new CompoundTag());
        String fingerprint = "copyhand|" + operator.getUUID() + "|" + slot + "|" + tag;
        return transact(server, name, transaction, fingerprint, inventory -> {
            inventory.setItem(slot, held.copy());
            return "copied operator-held fixture into slot=" + slot + " item=" + held.getHoverName().getString();
        });
    }

    static String clear(MinecraftServer server, String name, int slot, UUID transaction) {
        if (slot < 0 || slot > 40) return "Slot must be 0..40";
        return transact(server, name, transaction, "clear|" + slot, inventory -> {
            inventory.setItem(slot, ItemStack.EMPTY);
            return "cleared slot=" + slot;
        });
    }

    static String equip(MinecraftServer server, String name, int sourceSlot,
                        String equipmentName, UUID transaction) {
        if (sourceSlot < 0 || sourceSlot > 35) return "Source must be a main-inventory slot 0..35";
        String position = equipmentName.toLowerCase(Locale.ROOT);
        if (!List.of("mainhand", "offhand", "feet", "legs", "chest", "head").contains(position)) {
            return "Equipment must be mainhand/offhand/feet/legs/chest/head";
        }
        return transact(server, name, transaction, "equip|" + sourceSlot + "|" + position, inventory -> {
            int destination = switch (position) {
                case "mainhand" -> inventory.selected;
                case "offhand" -> 40;
                case "feet" -> 36;
                case "legs" -> 37;
                case "chest" -> 38;
                case "head" -> 39;
                default -> throw new IllegalArgumentException("invalid slot");
            };
            ItemStack incoming = inventory.getItem(sourceSlot).copy();
            if (incoming.isEmpty()) throw new IllegalArgumentException("Source slot is empty");
            if (destination != sourceSlot && destination >= 36) {
                EquipmentSlot expected = switch (position) {
                    case "feet" -> EquipmentSlot.FEET;
                    case "legs" -> EquipmentSlot.LEGS;
                    case "chest" -> EquipmentSlot.CHEST;
                    case "head" -> EquipmentSlot.HEAD;
                    default -> null;
                };
                if (expected != null && LivingEntity.getEquipmentSlotForItem(incoming) != expected) {
                    throw new IllegalArgumentException("Item cannot be equipped in " + position);
                }
            }
            ItemStack swapped = inventory.getItem(destination).copy();
            inventory.setItem(destination, incoming);
            inventory.setItem(sourceSlot, swapped);
            return "equipped " + position + " from slot=" + sourceSlot + " to=" + destination;
        });
    }

    static String inventory(MinecraftServer server, String name) {
        C1PlayerAvatar avatar = live(server, name);
        if (avatar == null) return "NPC not live: " + name;
        var inventory = avatar.getInventory();
        List<String> contents = new ArrayList<>();
        for (int slot = 0; slot <= 40; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!item.isEmpty()) {
                contents.add(slot + ":" + BuiltInRegistries.ITEM.getKey(item.getItem()) + "x" + item.getCount());
            }
        }
        return name + " selected=" + inventory.selected + " [" + String.join(", ", contents) + "]";
    }

    static String equipment(MinecraftServer server, String name) {
        C1PlayerAvatar avatar = live(server, name);
        if (avatar == null) return "NPC not live: " + name;
        List<String> summary = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack item = avatar.getItemBySlot(slot);
            summary.add(slot.getName() + "=" + (item.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(item.getItem()) + "x" + item.getCount()));
        }
        return name + " equipment: " + String.join(", ", summary);
    }

    static String ledger(MinecraftServer server, String name) {
        C1IdentityData.Entry identity = C1IdentityData.get(server).find(name);
        if (identity == null) return "No NPC identity named " + name;
        C1InventoryData data = C1InventoryData.get(server);
        if (data.blockage(identity.profileId()) != null) return "BLOCKED " + data.blockage(identity.profileId());
        C1InventoryData.Record record = data.find(identity.profileId());
        return name + " inventory revision=" + (record == null ? 0 : record.journal.revision())
                + " receipts=" + (record == null ? 0 : record.journal.size()) + "/" + C1InventoryJournal.MAX_RECEIPTS
                + " profile=" + identity.profileId();
    }

    /** One packet per equipment mutation or per client-confirmed spawn/repair. No idle polling. */
    static void syncForViewer(ServerPlayer viewer, C1PlayerAvatar npc) {
        if (viewer == null || viewer.connection == null || npc == null || npc.isRemoved()) return;
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            slots.add(Pair.of(slot, npc.getItemBySlot(slot).copy()));
        }
        viewer.connection.send(new ClientboundSetEquipmentPacket(npc.getId(), slots));
    }

    static void syncForActiveViewers(C1PlayerAvatar npc) {
        C1P0Runtime.forEachPresentViewer(npc, viewer -> syncForViewer(viewer, npc));
    }
}
