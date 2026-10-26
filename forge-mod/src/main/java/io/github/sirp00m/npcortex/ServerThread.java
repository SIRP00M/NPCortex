package io.github.sirp00m.npcortex;

import net.minecraft.server.MinecraftServer;

/** Fail-fast guard for static state that is only safe on the Minecraft server thread. */
final class ServerThread {
    private ServerThread() {}

    /** Call before mutating anything, so a violation cannot leave half-applied state. */
    static void require(MinecraftServer server, String operation) {
        if (server == null || !server.isSameThread())
            throw new IllegalStateException("NPCortex " + operation + " requires the Minecraft server thread");
    }
}
