package io.github.sirp00m.npcortex.network;

import io.github.sirp00m.npcortex.AutonomyRoster;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/** Client can request a roster or a specific toggle; ownership is checked again server-side. */
public record AutonomyRequestPacket(int requestId, String operation, UUID npcId, boolean enabled) {
    public static void encode(AutonomyRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.requestId());
        buf.writeUtf(packet.operation(), 8);
        buf.writeUUID(packet.npcId());
        buf.writeBoolean(packet.enabled());
    }

    public static AutonomyRequestPacket decode(FriendlyByteBuf buf) {
        return new AutonomyRequestPacket(buf.readVarInt(), buf.readUtf(8), buf.readUUID(), buf.readBoolean());
    }

    public static void handle(AutonomyRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            String message = "";
            if ("set".equals(packet.operation())) {
                CompanionEntity npc = AutonomyRoster.findOwned(player, packet.npcId());
                if (npc == null) {
                    message = "NPC not loaded or not owned by you.";
                } else {
                    npc.setAutonomyEnabled(packet.enabled());
                    message = npc.getName().getString() + " autonomy " + (packet.enabled() ? "ON" : "OFF");
                    player.sendSystemMessage(Component.literal("NPCortex: " + message));
                }
            } else if (!"list".equals(packet.operation())) {
                message = "Unknown operation.";
            }
            NpcNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AutonomyReplyPacket(packet.requestId(),
                            AutonomyRoster.entries(AutonomyRoster.ownedLoaded(player)), message));
        });
        ctx.setPacketHandled(true);
    }
}
