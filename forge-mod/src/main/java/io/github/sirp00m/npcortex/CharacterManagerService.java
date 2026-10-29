package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Forge adapter for {@link ManagerCore}. Authority lives on the server: identities come from C1 saved state,
 * the player must be an operator (level 2) or the single-player owner, and the backend (with the local
 * credential) is only ever contacted from here, never from the client.
 */
final class CharacterManagerService {
    private static final Logger LOG = LogUtils.getLogger();
    private static volatile MinecraftServer server;
    private static volatile ManagerCore core;

    private CharacterManagerService() {}

    static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        core = new ManagerCore(new ForgeHost(event.getServer()));
    }

    static void onServerStopping(ServerStoppingEvent event) {
        core = null;
        server = null;
    }

    /** Server thread (Forge enqueueWork). Malformed input is dropped without a reply. */
    static void onPacket(ServerPlayer sender, byte[] data) {
        ManagerCore current = core;
        if (current == null) return;
        ManagerProtocol.ToServer message;
        try {
            message = ManagerProtocol.decodeToServer(data);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("NPCortex Manager dropped a malformed packet from {}", sender.getGameProfile().getName());
            return;
        }
        current.handle(sender.getUUID(), message);
    }

    private record ForgeHost(MinecraftServer minecraft) implements ManagerCore.Host {
        @Override public boolean authorized(UUID player) {
            ServerPlayer found = minecraft.getPlayerList().getPlayer(player);
            return found != null && (found.hasPermissions(2) || minecraft.isSingleplayerOwner(found.getGameProfile()));
        }

        @Override public List<ManagerCore.Npc> npcs() {
            List<ManagerCore.Npc> result = new ArrayList<>();
            for (C1IdentityData.Entry entry : C1IdentityData.get(minecraft).all())
                result.add(new ManagerCore.Npc(entry.character(), entry.actorId(), entry.dimension(), entry.active(),
                        entry.profileMode(), entry.preset()));
            result.sort(Comparator.comparing(npc -> npc.name().toLowerCase(java.util.Locale.ROOT)));
            return result;
        }

        @Override public UUID worldId() { return C1IdentityData.get(minecraft).worldId(); }

        @Override public String createNpc(UUID player, String name) {
            ServerPlayer commander = minecraft.getPlayerList().getPlayer(player);
            if (commander == null) return "Player is no longer online.";
            String result = C1P0Runtime.spawn(minecraft, commander, name, C1IdentityData.MODE_CUSTOM, "");
            return result.startsWith("Spawn attempted") ? null : result;
        }

        @Override public void reply(UUID player, ManagerProtocol.ToClient message) {
            ServerPlayer found = minecraft.getPlayerList().getPlayer(player);
            if (found == null) return;
            try {
                ManagerNetwork.sendToClient(found, ManagerProtocol.encode(message));
            } catch (IOException ex) {
                LOG.warn("NPCortex Manager reply could not be encoded: {}", ex.getMessage());
            }
        }

        @Override public void onServerThread(Runnable task) { minecraft.execute(task); }

        @Override public CompletableFuture<ManagerCore.Http> http(String method, String path, JsonObject body) {
            return ChatBridge.profileRequest(method, path, body);
        }

        @Override public void log(String message) { LOG.info("NPCortex Manager: {}", message); }

        @Override public CompletableFuture<WorldEffectFlow.Result> worldAction(UUID requester, String npcName, boolean reconcile) {
            if (!reconcile) return WorldEffectRuntime.request(minecraft, requester, npcName, false);
            // Rec.: a native attempt (break/pickup) is judged first; anything else is the accepted no-op reconcile, unchanged.
            return NativeEffectRuntime.reconcile(minecraft, requester, npcName).thenCompose(result ->
                    "NO_NATIVE_ATTEMPT".equals(result.state())
                            ? PlayerActionRuntime.reconcile(minecraft, requester, npcName).thenCompose(pap ->
                                    "NO_PLAYER_ACTION".equals(pap.state())
                                            ? WorldEffectRuntime.request(minecraft, requester, npcName, true)
                                            : CompletableFuture.completedFuture(new WorldEffectFlow.Result(
                                                    WorldEffectFlow.Kind.valueOf(pap.kind().name()), pap.message(), pap.state())))
                            : CompletableFuture.completedFuture(new WorldEffectFlow.Result(
                                    WorldEffectFlow.Kind.valueOf(result.kind().name()), result.message(), result.state())));
        }

        @Override public CompletableFuture<NativeEffectFlow.Result> nativeAction(UUID requester, String npcName, int action, String nonce) {
            return switch (action) {
                case ManagerProtocol.NATIVE_PREVIEW_BREAK -> NativeEffectRuntime.preview(minecraft, requester, npcName, NativeEffectFlow.Action.BREAK);
                case ManagerProtocol.NATIVE_PREVIEW_PICKUP -> NativeEffectRuntime.preview(minecraft, requester, npcName, NativeEffectFlow.Action.PICKUP);
                case ManagerProtocol.NATIVE_PREVIEW_PLACE -> PlayerActionRuntime.preview(minecraft, requester, npcName, PlayerActionFlow.Action.PLACE);
                case ManagerProtocol.NATIVE_PREVIEW_USE -> PlayerActionRuntime.preview(minecraft, requester, npcName, PlayerActionFlow.Action.USE);
                case ManagerProtocol.NATIVE_CONFIRM -> PlayerActionRuntime.hasPreview(minecraft, requester, npcName)
                        ? PlayerActionRuntime.confirm(minecraft, requester, npcName, nonce)
                        : NativeEffectRuntime.confirm(minecraft, requester, npcName, nonce);
                default -> {                                           // a preview lives in exactly one of the two flows; cancel both
                    PlayerActionRuntime.cancel(minecraft, requester, npcName);
                    yield NativeEffectRuntime.cancel(minecraft, requester, npcName);
                }
            };
        }
    }
}
