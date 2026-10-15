package io.github.sirp00m.npcortex;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The C1-P0 identities live in overworld SavedData, never in the C0 SQLite database.
 * Actor ID, GameProfile ID and world ID are deliberately distinct stable UUIDs.
 * This does NOT register actors with the backend (that is a later scope).
 */
final class C1IdentityData extends SavedData {
    private static final String STORAGE_KEY = "npcortex_c1_p0";
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private UUID worldId = UUID.randomUUID();

    record Entry(String character, UUID actorId, UUID profileId, String profileName,
                 String dimension, double x, double y, double z, C1RotationState rotation, boolean active) {
        float yaw() { return rotation.yaw(); }
        Entry withLocation(String targetDimension, double px, double py, double pz, C1RotationState facing, boolean enabled) {
            return new Entry(character, actorId, profileId, profileName, targetDimension,
                    px, py, pz, facing, enabled);
        }
        Entry withActive(boolean enabled) {
            return new Entry(character, actorId, profileId, profileName, dimension, x, y, z, rotation, enabled);
        }
    }

    static C1IdentityData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                C1IdentityData::load, C1IdentityData::new, STORAGE_KEY);
    }

    static String key(String input) {
        return input.toLowerCase(Locale.ROOT);
    }

    UUID worldId() {
        return worldId;
    }

    Entry find(String name) {
        return entries.get(key(name));
    }

    Collection<Entry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    Entry ensure(String name, String dimension, double x, double y, double z, float yaw) {
        String key = key(name);
        Entry old = entries.get(key);
        if (old != null) {
            Entry next = old.withLocation(dimension, x, y, z, C1RotationState.legacy(yaw), true);
            entries.put(key, next);
            setDirty();
            return next;
        }
        UUID actor = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        // The suffix avoids profile-name collisions when names differ past eight characters.
        String profileName = "N" + name.substring(0, Math.min(8, name.length()))
                + "_" + profile.toString().substring(0, 6);
        Entry next = new Entry(name, actor, profile, profileName, dimension, x, y, z, C1RotationState.legacy(yaw), true);
        entries.put(key, next);
        setDirty();
        return next;
    }

    /** Update position in the existing record without changing the three stable IDs. */
    void updatePosition(UUID profileId, String dimension, double x, double y, double z, C1RotationState rotation) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || rotation == null || !rotation.valid()) return;
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry current = item.getValue();
            if (!current.profileId().equals(profileId)) continue;
            if (current.dimension().equals(dimension) && current.x() == x && current.y() == y
                    && current.z() == z && current.rotation().equals(rotation)) return;
            item.setValue(current.withLocation(dimension, x, y, z, rotation, current.active()));
            setDirty();
            return;
        }
    }

    void deactivate(String name) {
        Entry found = find(name);
        if (found != null && found.active()) {
            entries.put(key(name), found.withActive(false));
            setDirty();
        }
    }

    private static C1IdentityData load(CompoundTag root) {
        C1IdentityData store = new C1IdentityData();
        if (root.hasUUID("world_uuid")) {
            store.worldId = root.getUUID("world_uuid");
        } else {
            // Old/malformed data cannot claim durable identity; write a new ID on next save.
            store.setDirty();
        }
        ListTag list = root.getList("actors", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            if (!tag.hasUUID("actor_uuid") || !tag.hasUUID("profile_uuid")) continue;
            String character = tag.getString("character");
            String profileName = tag.getString("profile_name");
            String dimension = tag.getString("dimension");
            if (!validName(character) || profileName.isBlank() || dimension.isBlank()) continue;
            double x = tag.getDouble("x");
            double y = tag.getDouble("y");
            double z = tag.getDouble("z");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) continue;
            float yaw = tag.getFloat("yaw");
            C1RotationState rotation = C1RotationState.saved(yaw,
                    tag.contains("pitch", Tag.TAG_FLOAT), tag.getFloat("pitch"),
                    tag.contains("head_yaw", Tag.TAG_FLOAT), tag.getFloat("head_yaw"),
                    tag.contains("body_yaw", Tag.TAG_FLOAT), tag.getFloat("body_yaw"));
            if (!tag.contains("pitch", Tag.TAG_FLOAT)
                    || !tag.contains("head_yaw", Tag.TAG_FLOAT)
                    || !tag.contains("body_yaw", Tag.TAG_FLOAT)) store.setDirty();
            Entry entry = new Entry(character, tag.getUUID("actor_uuid"), tag.getUUID("profile_uuid"),
                    profileName, dimension, x, y, z, rotation, tag.getBoolean("active"));
            store.entries.put(key(character), entry);
        }
        return store;
    }

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putUUID("world_uuid", worldId);
        ListTag list = new ListTag();
        for (Entry actor : entries.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("character", actor.character());
            tag.putUUID("actor_uuid", actor.actorId());
            tag.putUUID("profile_uuid", actor.profileId());
            tag.putString("profile_name", actor.profileName());
            tag.putString("dimension", actor.dimension());
            tag.putDouble("x", actor.x());
            tag.putDouble("y", actor.y());
            tag.putDouble("z", actor.z());
            tag.putFloat("yaw", actor.rotation().yaw());
            tag.putFloat("pitch", actor.rotation().pitch());
            tag.putFloat("head_yaw", actor.rotation().headYaw());
            tag.putFloat("body_yaw", actor.rotation().bodyYaw());
            tag.putBoolean("active", actor.active());
            list.add(tag);
        }
        root.put("actors", list);
        return root;
    }
}
