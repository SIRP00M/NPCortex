package io.github.sirp00m.npcortex.network;

import io.github.sirp00m.npcortex.client.MemoryManagementScreen;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/** S2C response: screen only, no direct world-state changes on client. */
public record MemoryReplyPacket(int entityId, String json, String error) {
    public static void encode(MemoryReplyPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.entityId());
        buf.writeUtf(p.json(), 18000);
        buf.writeUtf(p.error(), 300);
    }
    public static MemoryReplyPacket decode(FriendlyByteBuf buf) {
        return new MemoryReplyPacket(buf.readVarInt(), buf.readUtf(18000), buf.readUtf(300));
    }
    public static void handle(MemoryReplyPacket p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.screen instanceof MemoryManagementScreen screen)
                screen.accept(p.entityId(), p.json(), p.error());
        });
        ctx.setPacketHandled(true);
    }
}
