package io.github.sirp00m.npcortex;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Forge-side action ledger. Persisted C2 runs fail closed after unclean restart:
 * no old action is re-executed. SavedData is not fsync; crash consistency beyond
 * explicit save/rejoin remains an acceptance task, NOT a proven guarantee.
 */
final class C2ActionJournal extends SavedData {
    private static final String KEY = "npcortex_c2_actions";
    record Row(UUID actor, UUID goal, UUID action, String state, String message) {
        boolean uncertain() { return !state.equals("COMPLETED") && !state.equals("FAILED") && !state.equals("RESET"); }
    }
    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    static C2ActionJournal get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                C2ActionJournal::load, C2ActionJournal::new, KEY);
    }

    Row find(UUID actor) { return rows.get(actor); }

    void record(UUID actor, UUID goal, UUID action, String state, String message) {
        if (state == null || message == null || state.length() > 48 || message.length() > 180)
            throw new IllegalArgumentException("Invalid C2 journal entry");
        rows.put(actor, new Row(actor, goal, action, state, message));
        setDirty();
    }

    void reset(UUID actor) { record(actor, null, null, "RESET", "Explicit operator reset"); }

    private static C2ActionJournal load(CompoundTag tag) {
        C2ActionJournal out = new C2ActionJournal();
        ListTag entries = tag.getList("runs", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag item = entries.getCompound(i);
            if (!item.hasUUID("actor")) continue;
            UUID actor = item.getUUID("actor");
            String state = item.getString("state");
            if (state.length() > 48) continue;
            out.rows.put(actor, new Row(actor,
                    item.hasUUID("goal") ? item.getUUID("goal") : null,
                    item.hasUUID("action") ? item.getUUID("action") : null,
                    state, item.getString("message")));
        }
        return out;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Row row : rows.values()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("actor", row.actor());
            if (row.goal() != null) item.putUUID("goal", row.goal());
            if (row.action() != null) item.putUUID("action", row.action());
            item.putString("state", row.state());
            item.putString("message", row.message());
            list.add(item);
        }
        tag.put("runs", list);
        return tag;
    }
}
