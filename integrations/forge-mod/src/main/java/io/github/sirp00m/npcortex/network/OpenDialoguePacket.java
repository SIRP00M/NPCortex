package io.github.sirp00m.npcortex.network;

import io.github.sirp00m.npcortex.client.ClientPackets;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.network.NetworkEvent;

public record OpenDialoguePacket(int entityId, String characterId, String displayName) {
    public static void encode(OpenDialoguePacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId);
        buf.writeUtf(packet.characterId, 64);
        buf.writeUtf(packet.displayName, 80);
    }

    public static OpenDialoguePacket decode(FriendlyByteBuf buf) {
        return new OpenDialoguePacket(buf.readVarInt(), buf.readUtf(64), buf.readUtf(80));
    }

    public static void handle(OpenDialoguePacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPackets.openDialogue(message.entityId, message.characterId, message.displayName)));
        context.get().setPacketHandled(true);
    }
}
