package io.github.sirp00m.npcortex;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.LevelEvent;

/**
 * D2: vanilla statistics and advancements of the avatar, in vanilla's own files (world/stats/uuid.json, world/advancements/uuid.json).
 * LOADING is already vanilla: the ServerPlayer constructor asks PlayerList.getPlayerStats/getPlayerAdvancements, which load the file on
 * the first request of a UUID and then cache the object (a respawn in the same session gets the same object; getPlayerAdvancements calls
 * setPlayer on it). SAVING is the gap: PlayerList.saveAll/remove only save members of PlayerList.players, and the avatar is not one.
 * This class is the single writer. It must be switched off if the avatar is ever registered in PlayerList (PlayerList.save would then
 * write the very same objects to the very same files). Never call PlayerAdvancements.stopListening: setPlayer does not re-register the
 * criterion listeners, so a respawned avatar would earn nothing.
 */
final class C1VanillaStatePersistence {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    /** Kill switch / hand-over flag for a future PlayerList registration. */
    static volatile boolean enabled = true;

    private C1VanillaStatePersistence() {}

    static void save(C1PlayerAvatar avatar) {
        if (!enabled || avatar == null) return;
        try {
            avatar.getStats().save();
            avatar.getAdvancements().save();
        } catch (RuntimeException ex) {
            LOG.error("NPCortex parity: saving statistics/advancements failed for {}", avatar.getUUID(), ex);
        }
    }

    static void saveAll() {
        // A registered avatar is saved by vanilla's own PlayerList.saveAll (same objects, same files): one writer per round.
        for (C1PlayerAvatar av : C1P0Runtime.liveAvatars()) if (!av.isRemoved() && !C1PlayerListRegistry.isRegistered(av)) save(av);
    }

    /** The world save (autosave, /save-all, shutdown): the overworld event fires once per save round, like PlayerList.saveAll's caller. */
    static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) saveAll();
    }

    static void beforeServerStop(MinecraftServer server) {
        saveAll();   // LIVE is cleared by C1P0Runtime.onServerStopping; the shutdown world save would find nobody
    }
}
