package io.github.sirp00m.npcortex.network;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

/** A local appearance override, not a character-pack or AI prompt edit. */
public record UpdateSkinPacket(int entityId, String skinId, boolean slim) {
    public static void encode(UpdateSkinPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId());
        buf.writeUtf(packet.skinId(), 40);
        buf.writeBoolean(packet.slim());
    }

    public static UpdateSkinPacket decode(FriendlyByteBuf buf) {
        return new UpdateSkinPacket(buf.readVarInt(), buf.readUtf(40), buf.readBoolean());
    }

    public static void handle(UpdateSkinPacket message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !message.skinId().matches("[a-z][a-z0-9_-]{0,39}")) return;
            Entity target = player.level().getEntity(message.entityId());
            if (!(target instanceof CompanionEntity npc)) return;
            if (!player.getUUID().equals(npc.getOwnerUUID()) || player.distanceToSqr(npc) > 144.0D) return;
            npc.setAppearanceOverride(message.skinId(), message.slim());
        });
        ctx.setPacketHandled(true);
    }
}
