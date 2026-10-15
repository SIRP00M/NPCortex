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

import java.util.UUID;
import java.util.function.Supplier;
import java.util.Optional;

/** C1-P0 only: Forge play-phase handshakes, never a replacement for vanilla ChunkMap. */
final class C1P0Network {
    private static final String VERSION = "c1p1-rotation-1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NPCortex.MOD_ID, "c1_sync"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    // Each handshake is bound to a profile UUID, live entity ID and unguessable per-viewer token.
    record Message(UUID npcId, int entityId, UUID token) {
        static void encode(Message message, FriendlyByteBuf buffer) {
            buffer.writeUUID(message.npcId);
            buffer.writeVarInt(message.entityId);
            buffer.writeUUID(message.token);
        }
        static Message decode(FriendlyByteBuf buffer) {
            return new Message(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID());
        }
    }
    record Prepare(UUID npcId, int entityId, UUID token) {
        static void encode(Prepare m, FriendlyByteBuf b) { Message.encode(new Message(m.npcId,m.entityId,m.token), b); }
        static Prepare decode(FriendlyByteBuf b) { Message m=Message.decode(b); return new Prepare(m.npcId,m.entityId,m.token); }
    }
    record ProfileReady(UUID npcId, int entityId, UUID token) {
        static void encode(ProfileReady m, FriendlyByteBuf b) { Message.encode(new Message(m.npcId,m.entityId,m.token), b); }
        static ProfileReady decode(FriendlyByteBuf b) { Message m=Message.decode(b); return new ProfileReady(m.npcId,m.entityId,m.token); }
    }
    record Verify(UUID npcId, int entityId, UUID token) {
        static void encode(Verify m, FriendlyByteBuf b) { Message.encode(new Message(m.npcId,m.entityId,m.token), b); }
        static Verify decode(FriendlyByteBuf b) { Message m=Message.decode(b); return new Verify(m.npcId,m.entityId,m.token); }
    }
    record Observation(UUID npcId, int entityId, UUID token, boolean present) {
        static void encode(Observation m, FriendlyByteBuf b) {
            Message.encode(new Message(m.npcId,m.entityId,m.token), b); b.writeBoolean(m.present);
        }
        static Observation decode(FriendlyByteBuf b) {
            Message m=Message.decode(b); return new Observation(m.npcId,m.entityId,m.token,b.readBoolean());
        }
    }

    record RotationSnapshot(UUID npcId, int entityId, UUID token, int revision, C1RotationState rotation) {
        static void encode(RotationSnapshot m, FriendlyByteBuf b) {
            Message.encode(new Message(m.npcId,m.entityId,m.token), b);
            b.writeVarInt(m.revision);
            b.writeFloat(m.rotation.yaw());
            b.writeFloat(m.rotation.pitch());
            b.writeFloat(m.rotation.headYaw());
            b.writeFloat(m.rotation.bodyYaw());
        }
        static RotationSnapshot decode(FriendlyByteBuf b) {
            Message header = Message.decode(b);
            int revision = b.readVarInt();
            C1RotationState rotation = new C1RotationState(b.readFloat(), b.readFloat(),
                    b.readFloat(), b.readFloat());
            return new RotationSnapshot(header.npcId, header.entityId, header.token, revision, rotation);
        }
    }
    record RotationApplied(UUID npcId, int entityId, UUID token, int revision) {
        static void encode(RotationApplied m, FriendlyByteBuf b) {
            Message.encode(new Message(m.npcId,m.entityId,m.token), b);
            b.writeVarInt(m.revision);
        }
        static RotationApplied decode(FriendlyByteBuf b) {
            Message header = Message.decode(b);
            return new RotationApplied(header.npcId, header.entityId, header.token, b.readVarInt());
        }
    }

    private C1P0Network() {}
    static void register() {
        CHANNEL.registerMessage(0, Prepare.class, Prepare::encode, Prepare::decode,
                C1P0Network::handlePrepare, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, ProfileReady.class, ProfileReady::encode, ProfileReady::decode,
                C1P0Network::handleReady, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, Verify.class, Verify::encode, Verify::decode,
                C1P0Network::handleVerify, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, Observation.class, Observation::encode, Observation::decode,
                C1P0Network::handleObservation, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, RotationSnapshot.class, RotationSnapshot::encode, RotationSnapshot::decode,
                C1P0Network::handleRotationSnapshot, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(5, RotationApplied.class, RotationApplied::encode, RotationApplied::decode,
                C1P0Network::handleRotationApplied, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    static void send(ServerPlayer viewer, Prepare packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer), packet);
    }
    static void send(ServerPlayer viewer, Verify packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer), packet);
    }
    static void send(ServerPlayer viewer, RotationSnapshot packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer), packet);
    }
    static void rotationApplied(RotationApplied packet) { CHANNEL.sendToServer(packet); }
    static void ready(ProfileReady packet) { CHANNEL.sendToServer(packet); }
    static void observation(Observation packet) { CHANNEL.sendToServer(packet); }

    private static void handlePrepare(Prepare msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> C1P0ClientSync.onPrepare(msg)));
        context.setPacketHandled(true);
    }
    private static void handleVerify(Verify msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> C1P0ClientSync.onVerify(msg)));
        context.setPacketHandled(true);
    }
    private static void handleReady(ProfileReady msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> C1P0Runtime.onProfileReady(sender, msg));
        context.setPacketHandled(true);
    }
    private static void handleRotationSnapshot(RotationSnapshot msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> C1P0ClientSync.onRotationSnapshot(msg)));
        context.setPacketHandled(true);
    }
    private static void handleRotationApplied(RotationApplied msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> C1P0Runtime.onRotationApplied(sender, msg));
        context.setPacketHandled(true);
    }
    private static void handleObservation(Observation msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> C1P0Runtime.onClientObservation(sender, msg));
        context.setPacketHandled(true);
    }
}
