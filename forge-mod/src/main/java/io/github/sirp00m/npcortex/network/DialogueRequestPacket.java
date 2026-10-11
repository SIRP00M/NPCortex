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

/** Untrusted client text is validated before a backend call. */
public record DialogueRequestPacket(int entityId, String message) {
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    public static void encode(DialogueRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId);
        buf.writeUtf(packet.message, 400);
    }

    public static DialogueRequestPacket decode(FriendlyByteBuf buf) {
        return new DialogueRequestPacket(buf.readVarInt(), buf.readUtf(400));
    }

    public static void handle(DialogueRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            Entity target = player.level().getEntity(packet.entityId);
            if (!(target instanceof CompanionEntity npc) || player.distanceToSqr(npc) > 64.0D) return;
            String text = packet.message.trim();
            if (text.isEmpty() || text.length() > 400) return;

            UUID userId = player.getUUID();
            if (!IN_FLIGHT.add(userId)) {
                send(player, packet.entityId, "Please wait for the previous reply.", false);
                return;
            }
            long now = player.level().getGameTime();
            long last = player.getPersistentData().getLong("NPCortexLastChatTick");
            if (last > 0 && now >= last && now - last < 20) {
                IN_FLIGHT.remove(userId);
                send(player, packet.entityId, "Please wait a moment before speaking again.", false);
                return;
            }
            player.getPersistentData().putLong("NPCortexLastChatTick", now);
            String characterId = npc.getCharacterId(); // never accept character IDs from client packets
            try {
                ChatBridge.ask(characterId, userId.toString(), text, npc.getUUID().toString())
                        .whenComplete((reply, error) -> player.getServer().execute(() -> {
                            IN_FLIGHT.remove(userId);
                            ServerPlayer online = player.getServer().getPlayerList().getPlayer(userId);
                            if (online == null) return;
                            if (error != null) {
                                send(online, packet.entityId,
                                        "AI unavailable. Verify the NPCortex Backend and Ollama are running.", false);
                            } else {
                                send(online, packet.entityId,
                                        reply.length() > 4096 ? reply.substring(0, 4096) : reply, true);
                            }
                        }));
            } catch (RuntimeException error) {
                IN_FLIGHT.remove(userId);
                send(player, packet.entityId, "Unable to connect to the NPCortex Backend.", false);
            }
        });
        context.setPacketHandled(true);
    }

    private static void send(ServerPlayer player, int id, String text, boolean success) {
        NpcNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new DialogueReplyPacket(id, text, success));
    }
}
