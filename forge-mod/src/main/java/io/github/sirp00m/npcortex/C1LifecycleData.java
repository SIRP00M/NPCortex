package io.github.sirp00m.npcortex;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persist lifecycle phase + health separately from identity. Missing = P1-B legacy ALIVE/full health.
 * No cross-SavedData atomicity or fsync is implied. Corrupt records fail closed. */
final class C1LifecycleData extends SavedData {
    private static final String KEY = "npcortex_c1_p1c_lifecycle";
    record State(C1LifecyclePhase phase, float health, long revision) {
        State {
            if (phase == null || !Float.isFinite(health) || health < 0 || health > 2048 || revision < 0)
                throw new IllegalArgumentException("invalid lifecycle state");
        }
    }
    private final Map<UUID, State> records = new HashMap<>();
    private final Map<UUID, CompoundTag> corrupt = new HashMap<>();
    static C1LifecycleData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                C1LifecycleData::load, C1LifecycleData::new, KEY);
    }
    State state(UUID id) {
        if (corrupt.containsKey(id)) return new State(C1LifecyclePhase.TRANSITION, 0, 0);
        return records.getOrDefault(id, new State(C1LifecyclePhase.ALIVE, 20, 0));
    }
    boolean blocked(UUID id) { return corrupt.containsKey(id); }
    void update(UUID id, C1LifecyclePhase phase, float health) {
        if (blocked(id)) throw new IllegalStateException("corrupt lifecycle record");
        State old = state(id);
        if (old.phase == phase && old.health == health) return;
        records.put(id, new State(phase, health, old.revision + 1));
        setDirty();
    }
    private static C1LifecycleData load(CompoundTag root) {
        C1LifecycleData data = new C1LifecycleData();
        for (var element : root.getList("actors", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag)element;
            if (!tag.hasUUID("profile_uuid")) continue;
            UUID id = tag.getUUID("profile_uuid");
            if (data.records.containsKey(id) || data.corrupt.containsKey(id)) {
                data.records.remove(id); data.corrupt.put(id, tag.copy()); continue;
            }
            try {
                if (tag.getInt("schema") != 1 || !tag.contains("phase", Tag.TAG_STRING)
                        || !tag.contains("health", Tag.TAG_FLOAT)) throw new IllegalArgumentException("invalid schema");
                C1LifecyclePhase phase = C1LifecyclePhase.decode(tag.getString("phase"));
                if (phase == C1LifecyclePhase.TRANSITION && !tag.getString("phase").equals("TRANSITION"))
                    throw new IllegalArgumentException("unrecognized phase");
                data.records.put(id, new State(phase, tag.getFloat("health"), tag.getLong("revision")));
            } catch (RuntimeException bad) { data.corrupt.put(id, tag.copy()); }
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        records.forEach((id, state) -> {
            CompoundTag tag = new CompoundTag();
            tag.putInt("schema", 1);
            tag.putUUID("profile_uuid", id);
            tag.putString("phase", state.phase().name());
            tag.putFloat("health", state.health());
            tag.putLong("revision", state.revision());
            list.add(tag);
        });
        corrupt.values().forEach(raw -> list.add(raw.copy()));
        root.put("actors", list);
        return root;
    }
}
