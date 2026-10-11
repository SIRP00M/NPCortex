package io.github.sirp00m.npcortex.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-only entry points. Never called on a dedicated server. */
public final class ClientPackets {
    private ClientPackets() {}

    public static void openDialogue(int entityId, String characterId) {
        Minecraft.getInstance().setScreen(new DialogueScreen(entityId, characterId));
    }

    public static void receiveReply(int entityId, String reply, boolean success) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof DialogueScreen screen && screen.matchesEntity(entityId)) {
            screen.receiveReply(reply, success);
        } else if (client.player != null) {
            // Don't lose a response if the player closes the screen while the model is generating.
            client.player.sendSystemMessage(Component.literal((success ? "NPCortex: " : "NPCortex error: ") + reply));
        }
    }
}
