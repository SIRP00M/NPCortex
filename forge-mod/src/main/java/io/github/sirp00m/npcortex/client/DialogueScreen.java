package io.github.sirp00m.npcortex.client;

import io.github.sirp00m.npcortex.network.DialogueRequestPacket;
import io.github.sirp00m.npcortex.network.NpcNetwork;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Simple conversation UI; intentionally does not pause the world. */
public final class DialogueScreen extends Screen {
    private final int npcEntityId;
    private final String characterId;
    private final String displayName;
    private EditBox input;
    private Button sendButton;
    private String answer = "Say hello to your companion.";
    private boolean waiting = false;

    public DialogueScreen(int npcEntityId, String characterId, String displayName) {
        super(Component.literal("NPCortex Dialogue"));
        this.npcEntityId = npcEntityId;
        this.characterId = characterId;
        this.displayName = displayName;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(370, width - 24);
        int left = (width - panelWidth) / 2;
        int top = (height - 194) / 2;
        input = new EditBox(font, left + 12, top + 154, panelWidth - 102, 20, Component.literal("Message"));
        input.setMaxLength(400);
        input.setHint(Component.literal("Type in English..."));
        input.setResponder(value -> sendButton.active = !waiting && !value.trim().isEmpty());
        addRenderableWidget(input);
        sendButton = addRenderableWidget(Button.builder(Component.literal("Send"), button -> send())
                .bounds(left + panelWidth - 82, top + 154, 70, 20).build());
        sendButton.active = false;
        setInitialFocus(input);
    }

    private void send() {
        if (waiting || input == null) return;
        String message = input.getValue().trim();
        if (message.isEmpty()) return;
        waiting = true;
        answer = "Thinking...";
        sendButton.active = false;
        input.setValue("");
        input.setEditable(false);
        NpcNetwork.CHANNEL.sendToServer(new DialogueRequestPacket(npcEntityId, message));
    }

    public boolean matchesEntity(int entityId) { return npcEntityId == entityId; }

    public void receiveReply(String reply, boolean success) {
        waiting = false;
        answer = success ? reply : "Error: " + reply;
        if (input != null) {
            input.setEditable(true);
            setFocused(input);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            send();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panelWidth = Math.min(370, width - 24);
        int left = (width - panelWidth) / 2;
        int top = (height - 194) / 2;
        graphics.fill(left, top, left + panelWidth, top + 194, 0xEE181F27);
        graphics.fill(left, top, left + panelWidth, top + 2, 0xFF829B68);
        graphics.drawString(font, "NPCortex / " + displayName, left + 12, top + 14, 0xFFE0E7D4, false);
        graphics.drawString(font, "Right-click a companion to talk. Esc closes.",
                left + 12, top + 29, 0xFFB6B9AD, false);
        List<FormattedCharSequence> lines = font.split(Component.literal(answer), panelWidth - 26);
        for (int i = 0; i < Math.min(10, lines.size()); i++) {
            graphics.drawString(font, lines.get(i), left + 12, top + 47 + i * 10, 0xFFFFFFFF, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
