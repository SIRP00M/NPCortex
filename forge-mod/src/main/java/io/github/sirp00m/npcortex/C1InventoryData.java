package io.github.sirp00m.npcortex;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative P1-B snapshot + replay receipts in ONE overworld SavedData file.
 * Minecraft saves this file at its regular save checkpoints, not at HTTP ACK.
 * The journal is not an external-side-effect transaction or fsync-per-command.
 */
final class C1InventoryData extends SavedData {
    private static final String KEY = "npcortex_c1_p1b_inventory";
    private final Map<UUID, Record> records = new HashMap<>();
    private final Map<UUID, String> blocked = new HashMap<>();
    private final Map<UUID, CompoundTag> blockedRaw = new HashMap<>();

    static final class Record {
        ListTag snapshot = new ListTag();
        int selected;
        final C1InventoryJournal journal = new C1InventoryJournal();
    }

    static C1InventoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                C1InventoryData::load, C1InventoryData::new, KEY);
    }

    String blockage(UUID profile) { return blocked.get(profile); }
    Record find(UUID profile) { return records.get(profile); }
    Record ensure(UUID profile) { return records.computeIfAbsent(profile, ignored -> new Record()); }

    void restore(UUID profile, Inventory target) {
        if (blocked.containsKey(profile)) throw new IllegalStateException("Inventory snapshot invalid for " + profile + ": " + blocked.get(profile));
        Record record = find(profile);
        if (record == null) return; // old P0/P1-A world: vanilla empty inventory
        target.load(record.snapshot.copy());
        target.selected = record.selected;
    }

    void commit(UUID profile, Inventory source, UUID txId, String fingerprint, String detail) {
        if (blocked.containsKey(profile)) throw new IllegalStateException("Inventory snapshot is blocked");
        Record record = ensure(profile);
        // Derive the full new snapshot before mutating the journal. This makes
        // both fields part of one SavedData save operation.
        ListTag next = source.save(new ListTag());
        record.journal.commit(txId, fingerprint, detail);
        record.snapshot = next;
        record.selected = source.selected;
        setDirty();
    }

    private static C1InventoryData load(CompoundTag root) {
        C1InventoryData data = new C1InventoryData();
        ListTag actors = root.getList("actors", Tag.TAG_COMPOUND);
        for (int i = 0; i < actors.size(); i++) {
            CompoundTag tag = actors.getCompound(i);
            if (!tag.hasUUID("profile_uuid")) continue;
            UUID profile = tag.getUUID("profile_uuid");
            try {
                if (data.records.containsKey(profile) || data.blocked.containsKey(profile)) {
                    throw new IllegalArgumentException("duplicate profile record");
                }
                if (tag.getInt("schema") != 1 || !tag.contains("inventory", Tag.TAG_LIST)
                        || !tag.contains("receipts", Tag.TAG_LIST)) {
                    throw new IllegalArgumentException("unexpected or missing schema fields");
                }
                Record record = new Record();
                record.selected = tag.getInt("selected");
                if (record.selected < 0 || record.selected > 8) throw new IllegalArgumentException("invalid selected hotbar slot");
                ListTag contents = tag.getList("inventory", Tag.TAG_COMPOUND);
                ListTag receipts = tag.getList("receipts", Tag.TAG_COMPOUND);
                if ((!contents.isEmpty() && contents.getElementType() != Tag.TAG_COMPOUND)
                        || (!receipts.isEmpty() && receipts.getElementType() != Tag.TAG_COMPOUND)) {
                    throw new IllegalArgumentException("unexpected NBT element type");
                }
                record.snapshot = contents.copy();
                List<C1InventoryJournal.Receipt> journalEntries = new ArrayList<>();
                for (int j = 0; j < receipts.size(); j++) {
                    CompoundTag receipt = receipts.getCompound(j);
                    if (!receipt.hasUUID("id")) throw new IllegalArgumentException("missing transaction UUID");
                    journalEntries.add(new C1InventoryJournal.Receipt(receipt.getUUID("id"),
                            receipt.getString("fingerprint"), receipt.getLong("revision"), receipt.getString("detail")));
                }
                record.journal.restore(tag.getLong("revision"), journalEntries);
                data.records.put(profile, record);
            } catch (RuntimeException invalid) {
                data.records.remove(profile);
                data.blocked.put(profile, invalid.getMessage()); // fail closed; never overwrite corrupt player items
                data.blockedRaw.put(profile, tag.copy());
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("schema", 1);
        ListTag actors = new ListTag();
        for (Map.Entry<UUID, Record> entry : records.entrySet()) {
            Record record = entry.getValue();
            CompoundTag tag = new CompoundTag();
            tag.putInt("schema", 1);
            tag.putUUID("profile_uuid", entry.getKey());
            tag.put("inventory", record.snapshot.copy());
            tag.putInt("selected", record.selected);
            tag.putLong("revision", record.journal.revision());
            ListTag receipts = new ListTag();
            for (C1InventoryJournal.Receipt receipt : record.journal.receipts()) {
                CompoundTag r = new CompoundTag();
                r.putUUID("id", receipt.id());
                r.putString("fingerprint", receipt.fingerprint());
                r.putLong("revision", receipt.revision());
                r.putString("detail", receipt.detail());
                receipts.add(r);
            }
            tag.put("receipts", receipts);
            actors.add(tag);
        }
        // Keep invalid serialized entries byte-for-byte in future saves triggered
        // by OTHER NPCs. Never silently erase a damaged actor's saved belongings.
        for (CompoundTag raw : blockedRaw.values()) actors.add(raw.copy());
        root.put("actors", actors);
        return root;
    }
}
