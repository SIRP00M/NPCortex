package io.github.sirp00m.npcortex.network;

import io.github.sirp00m.npcortex.client.ClientPackets;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

public record DialogueReplyPacket(int entityId, String reply, boolean success) {
    public static void encode(DialogueReplyPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId);
        buf.writeUtf(packet.reply, 4096);
        buf.writeBoolean(packet.success);
    }

    public static DialogueReplyPacket decode(FriendlyByteBuf buf) {
        return new DialogueReplyPacket(buf.readVarInt(), buf.readUtf(4096), buf.readBoolean());
    }

    public static void handle(DialogueReplyPacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPackets.receiveReply(message.entityId, message.reply, message.success)));
        context.get().setPacketHandled(true);
    }
}
