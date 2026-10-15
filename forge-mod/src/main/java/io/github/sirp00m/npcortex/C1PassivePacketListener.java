package io.github.sirp00m.npcortex;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * ServerPlayer's connection slot cannot be null for all vanilla tracking paths.
 * This is a deliberately passive SERVERBOUND endpoint, not a logged-in player.
 * Client-facing NPC spawn/profile packets go to real viewers, never here.
 * Any send into this listener is dropped instead of filling an unconnected
 * Connection's outbound queue. It accepts NO inbound client packets.
 */
final class C1PassivePacketListener extends ServerGamePacketListenerImpl {
    C1PassivePacketListener(MinecraftServer server, C1PlayerAvatar avatar) {
        super(server, new Connection(PacketFlow.SERVERBOUND), avatar);
    }

    @Override
    public void send(Packet<?> packet) {
        // No socket/channel for the NPC; never queue a packet to nowhere.
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        // Also suppress packets that supply callbacks.
    }
}
