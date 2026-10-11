package io.github.sirp00m.npcortex.network;

import io.github.sirp00m.npcortex.ChatBridge;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/** C2S; never let clients provide the player/character/session identity. */
public record MemoryRequestPacket(int entityId, String operation, String text, int noteId) {
    private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<String> OPERATIONS = Set.of("list", "add", "delete", "clear", "reset_relationship");

    public static void encode(MemoryRequestPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.entityId());
        buf.writeUtf(p.operation(), 24);
        buf.writeUtf(p.text(), 300);
        buf.writeVarInt(p.noteId());
    }
    public static MemoryRequestPacket decode(FriendlyByteBuf buf) {
        return new MemoryRequestPacket(buf.readVarInt(), buf.readUtf(24), buf.readUtf(300), buf.readVarInt());
    }
    public static void handle(MemoryRequestPacket p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            Entity entity = player.level().getEntity(p.entityId());
            if (!(entity instanceof CompanionEntity npc) || npc.isRemoved() || !npc.isAlive() ||
                    player.distanceToSqr(npc) > 64.0D || !player.getUUID().equals(npc.getOwnerUUID())) {
                send(player, p.entityId(), "", "Memory access denied: own an NPC within 8 blocks.");
                return;
            }
            if (!OPERATIONS.contains(p.operation()) || p.text().length() > 300 || p.noteId() < 0) return;
            UUID playerId = player.getUUID();
            if (!PENDING.add(playerId)) {
                send(player, p.entityId(), "", "Memory operation in progress. Please wait.");
                return;
            }
            ChatBridge.memory(playerId.toString(), npc.getUUID().toString(), npc.getCharacterId(),
                    p.operation(), p.text(), p.noteId())
                .whenComplete((memory, error) -> player.getServer().execute(() -> {
                    PENDING.remove(playerId);
                    ServerPlayer online = player.getServer().getPlayerList().getPlayer(playerId);
                    if (online == null) return;
                    Entity current = online.level().getEntity(p.entityId());
                    if (!(current instanceof CompanionEntity currentNpc) ||
                            !current.getUUID().equals(npc.getUUID()) ||
                            online.distanceToSqr(current) > 64.0D ||
                            !playerId.equals(currentNpc.getOwnerUUID())) return;
                    if (error != null) send(online, p.entityId(), "", "Backend memory request failed. Check logs.");
                    else send(online, p.entityId(), memory.toString(), "");
                }));
        });
        ctx.setPacketHandled(true);
    }
    private static void send(ServerPlayer player, int entityId, String json, String error) {
        NpcNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MemoryReplyPacket(entityId, json, error));
    }
}
