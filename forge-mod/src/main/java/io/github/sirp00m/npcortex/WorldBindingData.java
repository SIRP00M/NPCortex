package io.github.sirp00m.npcortex;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Stage 4 native-effect world binding: the lineage EPOCH this world last agreed with the backend. A separate, additive
 * overworld SavedData (the accepted C1 identity data is not touched). The epoch travels with the world folder, which is exactly
 * why it is compared with the backend record together with the world FOLDER name (see NativeEffectStore.bind): neither is proof
 * of a physical world identity, and a restore of the last saved state cannot be told from the real one.
 * Minecraft writes this file at its normal save checkpoints; the backend learns of a save through the LevelEvent.Save hook.
 */
final class WorldBindingData extends SavedData {
    private static final String KEY = "npcortex_native_binding";
    private long epoch;

    static WorldBindingData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(WorldBindingData::load, WorldBindingData::new, KEY);
    }

    long epoch() { return epoch; }

    void setEpoch(long next) {
        if (next < 0) throw new IllegalArgumentException("negative epoch");
        this.epoch = next;
        setDirty();
    }

    private static WorldBindingData load(CompoundTag root) {
        WorldBindingData data = new WorldBindingData();
        data.epoch = Math.max(0L, root.getLong("epoch"));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("version", 1);
        root.putLong("epoch", epoch);
        return root;
    }
}
