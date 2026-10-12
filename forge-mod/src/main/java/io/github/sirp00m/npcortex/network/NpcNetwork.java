package io.github.sirp00m.npcortex.network;

import io.github.sirp00m.npcortex.NPCortex;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;

public final class NpcNetwork {
    private static final String VERSION = "5";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NPCortex.MOD_ID, "dialogue"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private NpcNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, OpenDialoguePacket.class,
                OpenDialoguePacket::encode, OpenDialoguePacket::decode, OpenDialoguePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, DialogueRequestPacket.class,
                DialogueRequestPacket::encode, DialogueRequestPacket::decode, DialogueRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, DialogueReplyPacket.class,
                DialogueReplyPacket::encode, DialogueReplyPacket::decode, DialogueReplyPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, UpdateSkinPacket.class,
                UpdateSkinPacket::encode, UpdateSkinPacket::decode, UpdateSkinPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, MemoryRequestPacket.class,
                MemoryRequestPacket::encode, MemoryRequestPacket::decode, MemoryRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5, MemoryReplyPacket.class,
                MemoryReplyPacket::encode, MemoryReplyPacket::decode, MemoryReplyPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(6, AutonomyRequestPacket.class,
                AutonomyRequestPacket::encode, AutonomyRequestPacket::decode, AutonomyRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(7, AutonomyReplyPacket.class,
                AutonomyReplyPacket::encode, AutonomyReplyPacket::decode, AutonomyReplyPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
