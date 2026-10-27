package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-only plumbing for the NPCortex Manager. Referenced exclusively from client code paths
 * (the Dist.CLIENT pause-menu hook and a DistExecutor lambda), so a dedicated server never loads it.
 */
final class ManagerClient {
    private static final Logger LOG = LogUtils.getLogger();

    interface Listener { void onMessage(ManagerProtocol.ToClient message); }

    private static Listener listener;

    private ManagerClient() {}

    static void attach(Listener next) { listener = next; }

    static void detach(Listener current) { if (listener == current) listener = null; }

    /** A fresh non-zero operation id; replies carrying any other id are stale and ignored by the screen. */
    static long nextOpId() { return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE); }

    /** @return false when the message could not even be encoded (a local limit was exceeded). */
    static boolean send(ManagerProtocol.ToServer message) {
        try {
            ManagerNetwork.sendToServer(ManagerProtocol.encode(message));
            return true;
        } catch (IOException | RuntimeException ex) {
            LOG.warn("NPCortex Manager request was not sent: {}", ex.getMessage());
            return false;
        }
    }

    static void onPacket(byte[] data) {
        ManagerProtocol.ToClient message;
        try {
            message = ManagerProtocol.decodeToClient(data);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("NPCortex Manager dropped a malformed server reply");
            return;
        }
        Listener current = listener;
        if (current != null) current.onMessage(message);
    }
}
