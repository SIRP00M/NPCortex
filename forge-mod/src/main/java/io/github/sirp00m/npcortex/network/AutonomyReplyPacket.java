package io.github.sirp00m.npcortex.network;

import io.github.sirp00m.npcortex.AutonomyRoster;
import io.github.sirp00m.npcortex.client.AutonomyManagementScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/** Only published server-side NPC metadata reaches the in-game manager. */
public record AutonomyReplyPacket(int requestId, List<AutonomyRoster.Entry> roster, String message) {
    public static void encode(AutonomyReplyPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.requestId());
        buf.writeVarInt(packet.roster().size());
        for (var entry : packet.roster()) {
            buf.writeUUID(entry.uuid());
            buf.writeUtf(entry.characterId(), 64);
            buf.writeUtf(entry.name(), 128);
            buf.writeUtf(entry.dimension(), 128);
            buf.writeBoolean(entry.enabled());
            buf.writeBoolean(entry.following());
        }
        buf.writeUtf(packet.message(), 160);
    }

    public static AutonomyReplyPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > AutonomyRoster.MAX_ENTRIES) throw new IllegalArgumentException("Invalid NPC roster size");
        List<AutonomyRoster.Entry> roster = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            roster.add(new AutonomyRoster.Entry(buf.readUUID(), buf.readUtf(64), buf.readUtf(128),
                    buf.readUtf(128), buf.readBoolean(), buf.readBoolean()));
        }
        return new AutonomyReplyPacket(id, List.copyOf(roster), buf.readUtf(160));
    }

    public static void handle(AutonomyReplyPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.screen instanceof AutonomyManagementScreen screen)
                screen.accept(packet.requestId(), packet.roster(), packet.message());
        });
        ctx.setPacketHandled(true);
    }
}
