package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server half of the visible-NPC-name feature. A separate clientbound channel (the C1 sync protocol is untouched)
 * tells every real player which GameProfile UUIDs are NPCs and what their logical C1 name is. Nothing about the
 * GameProfile, its name, the selectors or the saved identity changes: only the client's display name does.
 */
final class NpcNameNetwork {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String VERSION = "names-1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NPCortex.MOD_ID, "names"), () -> VERSION, VERSION::equals, VERSION::equals);

    record Packet(byte[] data) {
        static void encode(Packet packet, FriendlyByteBuf buffer) { buffer.writeByteArray(packet.data); }
        static Packet decode(FriendlyByteBuf buffer) { return new Packet(buffer.readByteArray(NpcNameCodec.MAX_PACKET_BYTES)); }
    }

    private NpcNameNetwork() {}

    static void register() {
        CHANNEL.registerMessage(0, Packet.class, Packet::encode, Packet::decode, NpcNameNetwork::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void handle(Packet packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> NpcNameClient.onPacket(packet.data())));
        context.setPacketHandled(true);
    }

    private static boolean realPlayer(ServerPlayer player) {
        return !(player instanceof C1PlayerAvatar);
    }

    private static void send(ServerPlayer player, NpcNameCodec.Sync sync) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Packet(NpcNameCodec.encode(sync)));
        } catch (IOException ex) {
            LOG.warn("NPCortex NPC name sync not sent: {}", ex.getMessage());
        }
    }

    /** Every saved NPC of this world (spawned or not): lets a (re)joining client name an NPC the moment it appears. */
    static NpcNameCodec.Sync snapshot(MinecraftServer server) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (C1IdentityData.Entry entry : C1IdentityData.get(server).all()) {
            if (names.size() >= NpcNameCodec.MAX_ENTRIES) {
                LOG.warn("NPCortex NPC name sync truncated at {} NPCs", NpcNameCodec.MAX_ENTRIES);
                break;
            }
            if (NpcNameCodec.validName(entry.character())) names.put(entry.profileId(), entry.character());
        }
        return new NpcNameCodec.Sync(true, names);
    }

    static void sendAll(ServerPlayer player) {
        if (player == null || !realPlayer(player) || player.getServer() == null) return;
        send(player, snapshot(player.getServer()));
    }

    /** Called when an NPC is spawned or restored, BEFORE its player-info/spawn packets reach any viewer. */
    static void announce(MinecraftServer server, C1IdentityData.Entry entry) {
        if (!NpcNameCodec.validName(entry.character())) return;
        NpcNameCodec.Sync one = new NpcNameCodec.Sync(false, Map.of(entry.profileId(), entry.character()));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (realPlayer(player)) send(player, one);
    }

    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }

    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }

    static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }
}
