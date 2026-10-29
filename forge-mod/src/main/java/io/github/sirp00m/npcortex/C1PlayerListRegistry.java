package io.github.sirp00m.npcortex;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.Stats;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/**
 * D3 (selected architecture "C+"): the live avatar is a member of vanilla's {@code PlayerList.players} / {@code playersByUUID}, WITHOUT
 * running {@code placeNewPlayer} (that would swap the passive listener for a real Connection, load playerdata/uuid.dat over the C1
 * SavedData owner, and replace the C1 spawn path). Source evidence (javap, Forge 47.4.10): {@code @a/@p/@r}, {@code @e[type=player]},
 * name/UUID selectors, /list, the player count, broadcasts, scoreboard-by-selector and {@code PlayerList.saveAll} (stats + advancements)
 * all read these two collections and nothing else. Lifecycle hooks mirror {@code PlayerList.placeNewPlayer}/{@code remove}: the Forge
 * PlayerLoggedIn/LoggedOut events and the LEAVE_GAME statistic, but NOT {@code stopListening}/map removal (see C1VanillaStatePersistence).
 *
 * Known differences from a human join (documented, not hidden): no real Connection (the passive listener discards packets), no
 * playerdata load (C1 SavedData stays the inventory/position owner; saveAll still writes a never-read playerdata/uuid.dat), no
 * authenticated chat. The player count and max-players now include NPCs, as for additional human participants.
 * Rollback: set {@link #enabled} false (no registration; C1VanillaStatePersistence then saves on the world-save event as in D2).
 */
final class C1PlayerListRegistry {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    static volatile boolean enabled = true;
    /** PlayerLoggedIn/Out fire for NPCs (mods tracking participants see them). NPCortex's own handlers skip C1PlayerAvatar. */
    static volatile boolean fireLifecycleEvents = true;

    // SRG names (ObfuscationReflectionHelper maps them in a dev environment too): f_11196_ = players, f_11197_ = playersByUUID.
    private static final Field PLAYERS = ObfuscationReflectionHelper.findField(PlayerList.class, "f_11196_");
    private static final Field BY_UUID = ObfuscationReflectionHelper.findField(PlayerList.class, "f_11197_");

    private C1PlayerListRegistry() {}

    @SuppressWarnings("unchecked")
    private static List<ServerPlayer> players(PlayerList list) {
        try { return (List<ServerPlayer>) PLAYERS.get(list); } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> byUuid(PlayerList list) {
        try { return (Map<UUID, ServerPlayer>) BY_UUID.get(list); } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
    }

    static boolean isRegistered(C1PlayerAvatar avatar) {
        MinecraftServer server = avatar.getServer();
        return server != null && players(server.getPlayerList()).contains(avatar);
    }

    /** Server thread, after ServerLevel.addNewPlayer. Idempotent; never replaces a different instance of the same UUID. */
    static void register(MinecraftServer server, C1PlayerAvatar avatar) {
        if (!enabled) return;
        PlayerList list = server.getPlayerList();
        ServerPlayer other = byUuid(list).get(avatar.getUUID());
        if (other != null && other != avatar) {
            LOG.error("NPCortex D3: PlayerList already holds another player with UUID {}: not registering", avatar.getUUID());
            return;
        }
        if (players(list).contains(avatar)) return;
        players(list).add(avatar);
        byUuid(list).put(avatar.getUUID(), avatar);
        if (fireLifecycleEvents) ForgeEventFactory.firePlayerLoggedIn(avatar);
    }

    /** Server thread, BEFORE the avatar is discarded (like PlayerList.remove: event, LEAVE_GAME, save, then the lists). */
    static void unregister(MinecraftServer server, C1PlayerAvatar avatar) {
        if (avatar == null) return;
        PlayerList list = server.getPlayerList();
        if (!players(list).contains(avatar)) return;
        try {
            if (fireLifecycleEvents) ForgeEventFactory.firePlayerLoggedOut(avatar);
            avatar.awardStat(Stats.LEAVE_GAME);
        } finally {
            players(list).remove(avatar);
            byUuid(list).remove(avatar.getUUID(), avatar);
        }
    }

    /** Server stopping: MUST precede PlayerList.removeAll, which would disconnect() every member through a Connection the avatar lacks. */
    static void unregisterAll(MinecraftServer server) {
        for (C1PlayerAvatar av : C1P0Runtime.liveAvatars()) unregister(server, av);
    }
}
