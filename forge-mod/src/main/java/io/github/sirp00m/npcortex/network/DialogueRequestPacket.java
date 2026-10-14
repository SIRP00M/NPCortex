package io.github.sirp00m.npcortex.network;

import io.github.sirp00m.npcortex.ChatBridge;
import io.github.sirp00m.npcortex.DirectIntent;
import io.github.sirp00m.npcortex.ActionPlanRunner;
import io.github.sirp00m.npcortex.GatherTaskController;
import io.github.sirp00m.npcortex.AgenticTaskController;
import io.github.sirp00m.npcortex.TreeSpecies;
import io.github.sirp00m.npcortex.GatherTaskIntent;
import io.github.sirp00m.npcortex.BulkTransferIntent;
import io.github.sirp00m.npcortex.WorldActions;
import io.github.sirp00m.npcortex.WorldObservation;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Set;
import java.util.Map;
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
            if (!player.getUUID().equals(npc.getOwnerUUID())) return;

            // Explicit counted gather and bulk inventory requests bypass the
            // one-step LLM actions so "six" never silently becomes "one".
            var bulk = BulkTransferIntent.parse(text);
            if (bulk != null) {
                if (AgenticTaskController.isBusy(npc)) {
                    send(player, packet.entityId, "Cancel the agentic task before transferring items.", false);
                    return;
                }
                npc.getPersistentData().putInt("NPCortexRequestRevision",
                        npc.getPersistentData().getInt("NPCortexRequestRevision") + 1);
                String status = WorldActions.executeBulk(player, npc, bulk);
                send(player, packet.entityId, status, status.startsWith("[Action confirmed:"));
                return; // factual feedback only; no premature generated success claim
            }
            String invalidGather = GatherTaskIntent.rejection(text);
            if (invalidGather != null) {
                send(player, packet.entityId, invalidGather, false);
                return;
            }
            // Bounded task directives are recognized before the LLM; count and
            // resource IDs are revalidated on the Minecraft server.
            var gather = GatherTaskIntent.parse(text);
            if (gather != null || GatherTaskIntent.isCancel(text)) {
                npc.getPersistentData().putInt("NPCortexRequestRevision",
                        npc.getPersistentData().getInt("NPCortexRequestRevision") + 1);
                String result = gather == null
                        ? (AgenticTaskController.isBusy(npc) ? AgenticTaskController.cancel(npc)
                                : GatherTaskController.cancel(npc))
                        : (TreeSpecies.supportedLog(gather.itemId())
                                ? AgenticTaskController.start(player, npc, text, gather.itemId(), gather.count())
                                : GatherTaskController.start(player, npc, gather.itemId(), gather.count()));
                send(player, packet.entityId, result, result.startsWith("[Task started:")
                        || result.startsWith("[Task cancelled")
                        || result.startsWith("[Agentic task started")
                        || result.startsWith("[Agentic task cancelled"));
                return;
            }
            if (AgenticTaskController.isBusy(npc)) {
                send(player, packet.entityId, AgenticTaskController.status(npc)
                        + " Use /npcortex task cancel before other actions.", false);
                return;
            }
            // Every accepted request supersedes older, still-pending model responses.
            // Explicit stay/follow instructions can interrupt an active plan immediately.
            String direct = DirectIntent.action(text, npc.getCharacterId(), npc.getName().getString());
            if (direct != null) {
                npc.getPersistentData().putInt("NPCortexRequestRevision",
                        npc.getPersistentData().getInt("NPCortexRequestRevision") + 1);
                ActionPlanRunner.cancel(npc);
                GatherTaskController.cancel(npc);
                // Deferred movement/jump actions use the same server-authoritative
                // completion/timeout tracking as the normal AI planner.
                boolean deferred = "move_to_player".equals(direct)
                        || "wander".equals(direct) || "jump".equals(direct);
                String status = deferred
                        ? ActionPlanRunner.begin(player, npc,
                              java.util.List.of(new ChatBridge.ActionStep(direct, "")),
                              text, npc.getPersistentData().getInt("NPCortexRequestRevision"), direct)
                        : WorldActions.execute(player, npc, direct, "");
                send(player, packet.entityId, status,
                        !status.contains("[Action failed:") && !status.contains("[Action blocked:")
                        && !status.contains("[Plan stopped:"));
                // Status goes to the player immediately: Ollama never gates movement.
                if (!status.contains("[Plan waiting for this action to complete.]")) {
                    speakAfterAction(player, npc, packet.entityId, text, direct, status,
                            npc.getPersistentData().getInt("NPCortexRequestRevision"));
                }
                return;
            }

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
            final int revision = npc.getPersistentData().getInt("NPCortexRequestRevision") + 1;
            npc.getPersistentData().putInt("NPCortexRequestRevision", revision);
            String characterId = npc.getCharacterId(); // never accept character IDs from client packets
            try {
                ChatBridge.agent(characterId, userId.toString(), text,
                                npc.getUUID().toString(), WorldObservation.capture(player, npc))
                        .whenComplete((reply, error) -> player.getServer().execute(() -> {
                            IN_FLIGHT.remove(userId);
                            ServerPlayer online = player.getServer().getPlayerList().getPlayer(userId);
                            if (online == null) return;
                            // A newer direct order (e.g. Stay here) invalidates this reply.
                            if (npc.getPersistentData().getInt("NPCortexRequestRevision") != revision)
                                return;
                            if (error != null) {
                                send(online, packet.entityId,
                                        "AI unavailable. Verify the NPCortex Backend and Ollama are running.", false);
                            } else {
                                Entity current = online.level().getEntity(packet.entityId);
                                String actionResult;
                                if (!(current instanceof CompanionEntity currentNpc)
                                        || !current.getUUID().equals(npc.getUUID())) {
                                    actionResult = "[Action skipped: NPC no longer available.]";
                                } else {
                                    actionResult = ActionPlanRunner.begin(online, currentNpc,
                                            reply.steps(), text, revision, "plan");
                                }
                                // Display game-confirmed status for *all* attempted actions,
                                // including one-step failures (the model may say "Here you go"
                                // before the actual transfer is attempted).
                                String textReply = reply.steps().isEmpty() ? reply.reply() : actionResult;
                                send(online, packet.entityId, textReply, true);
                                if (!reply.steps().isEmpty() && current instanceof CompanionEntity liveNpc
                                        && liveNpc.getUUID().equals(npc.getUUID())) {
                                    if (!actionResult.contains("[Plan waiting for this action to complete.]")) {
                                        speakAfterAction(online, liveNpc, packet.entityId, text,
                                                "plan", actionResult, revision);
                                    }
                                }
                            }
                        }));
            } catch (RuntimeException error) {
                IN_FLIGHT.remove(userId);
                send(player, packet.entityId, "Unable to connect to the NPCortex Backend.", false);
            }
        });
        context.setPacketHandled(true);
    }

    /** Model speech is secondary; factual game feedback never depends on Ollama. */
    private static final Map<UUID, Integer> REACTION_PENDING = new ConcurrentHashMap<>();

    public static void speakAfterAction(ServerPlayer player, CompanionEntity npc, int entityId,
            String playerMessage, String action, String status, int revision) {
        UUID npcId = npc.getUUID();
        // Allow an override (e.g. Follow -> Stay) to speak while an older
        // request is in flight, but bound inference fan-out per NPC.
        int inFlight = REACTION_PENDING.merge(npcId, 1, Integer::sum);
        if (inFlight > 2) {
            decrementReaction(npcId);
            return;
        }
        UUID ownerId = player.getUUID();
        ChatBridge.actionReply(npc.getCharacterId(), ownerId.toString(), playerMessage,
                npcId.toString(), action, status)
                .whenComplete((line, error) -> player.getServer().execute(() -> {
                    decrementReaction(npcId);
                    ServerPlayer online = player.getServer().getPlayerList().getPlayer(ownerId);
                    if (online == null || online.level() != npc.level() || npc.isRemoved()
                            || npc.getPersistentData().getInt("NPCortexRequestRevision") != revision)
                        return; // Newer requests must not be overwritten by stale speech.
                    if (error == null && line != null && !line.isBlank()) {
                        send(online, entityId, npc.getName().getString() + ": " + line + "\n" + status,
                                !status.contains("[Action failed:") && !status.contains("[Action blocked:")
                                && !status.contains("[Plan stopped:"));
                    }
                    // On Ollama failure, keep the authoritative status already displayed.
                }));
    }

    private static void decrementReaction(UUID npcId) {
        REACTION_PENDING.computeIfPresent(npcId, (id, count) -> count <= 1 ? null : count - 1);
    }

    private static void send(ServerPlayer player, int id, String text, boolean success) {
        NpcNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new DialogueReplyPacket(id, text, success));
    }
}
