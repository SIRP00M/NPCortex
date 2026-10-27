package io.github.sirp00m.npcortex;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Separate, versioned channel for the NPCortex Manager. It never shares a message with the C1 sync channel
 * (npcortex:c1_sync), so that protocol is untouched. Each message is one opaque, size-capped byte[]
 * that {@link ManagerProtocol} validates; a malformed packet is dropped, never trusted.
 */
final class ManagerNetwork {
    private static final String VERSION = "manager-1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NPCortex.MOD_ID, "manager"), () -> VERSION, VERSION::equals, VERSION::equals);

    record ToServerPacket(byte[] data) {
        static void encode(ToServerPacket packet, FriendlyByteBuf buffer) { buffer.writeByteArray(packet.data); }
        static ToServerPacket decode(FriendlyByteBuf buffer) {
            return new ToServerPacket(buffer.readByteArray(ManagerProtocol.MAX_PACKET_BYTES));
        }
    }

    record ToClientPacket(byte[] data) {
        static void encode(ToClientPacket packet, FriendlyByteBuf buffer) { buffer.writeByteArray(packet.data); }
        static ToClientPacket decode(FriendlyByteBuf buffer) {
            return new ToClientPacket(buffer.readByteArray(ManagerProtocol.MAX_PACKET_BYTES));
        }
    }

    private ManagerNetwork() {}

    static void register() {
        CHANNEL.registerMessage(0, ToServerPacket.class, ToServerPacket::encode, ToServerPacket::decode,
                ManagerNetwork::handleServer, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, ToClientPacket.class, ToClientPacket::encode, ToClientPacket::decode,
                ManagerNetwork::handleClient, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void sendToServer(byte[] data) {
        CHANNEL.sendToServer(new ToServerPacket(data));
    }

    static void sendToClient(ServerPlayer player, byte[] data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ToClientPacket(data));
    }

    private static void handleServer(ToServerPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> CharacterManagerService.onPacket(sender, packet.data()));
        context.setPacketHandled(true);
    }

    private static void handleClient(ToClientPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ManagerClient.onPacket(packet.data())));
        context.setPacketHandled(true);
    }
}
