package io.github.sirp00m.npcortex.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sirp00m.npcortex.network.MemoryRequestPacket;
import io.github.sirp00m.npcortex.network.NpcNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** ESC > NPCortex Manager > Memory. No direct client backend access. */
public final class MemoryManagementScreen extends Screen {
    private record Note(int id, String text) {}
    private final Screen parent;
    private final int entityId;
    private final String name;
    private final List<Note> notes = new ArrayList<>();
    private EditBox input;
    private Button prev, next, forget;
    private int current = 0;
    private int chats, affinity;
    private String status = "Loading NPC memory...";
    private boolean confirmClear = false;

    public MemoryManagementScreen(Screen parent, int entityId, String name) {
        super(Component.literal("NPCortex Memory Manager"));
        this.parent = parent;
        this.entityId = entityId;
        this.name = name;
    }

    @Override
    protected void init() {
        int x = (width - 360) / 2;
        int y = Math.max(6, (height - 225) / 2);
        input = addRenderableWidget(new EditBox(font, x + 8, y + 47, 344, 20,
                Component.literal("Save a memory note")));
        input.setMaxLength(300);
        input.setHint(Component.literal("A fact this NPC should remember (up to 300 chars)"));
        addRenderableWidget(Button.builder(Component.literal("Save Note"), b -> {
            if (!input.getValue().isBlank()) {
                request("add", input.getValue(), 0);
                input.setValue("");
            }
        }).bounds(x + 8, y + 73, 104, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> request("list", "", 0))
                .bounds(x + 118, y + 73, 95, 20).build());
        forget = addRenderableWidget(Button.builder(Component.literal("Forget Selected"), b -> {
            if (!notes.isEmpty()) request("delete", "", notes.get(current).id());
        }).bounds(x + 219, y + 73, 133, 20).build());
        prev = addRenderableWidget(Button.builder(Component.literal("< Previous"), b -> {
            current = Math.max(0, current - 1); updateButtons();
        }).bounds(x + 8, y + 148, 102, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("Next >"), b -> {
            current = Math.min(notes.size() - 1, current + 1); updateButtons();
        }).bounds(x + 117, y + 148, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear Notes"), b -> {
            if (confirmClear) {
                request("clear", "", 0);
                confirmClear = false;
            } else {
                status = "Press Clear Notes again to confirm deletion.";
                confirmClear = true;
            }
        }).bounds(x + 219, y + 148, 133, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset Friendship"), b -> {
            request("reset_relationship", "", 0);
        }).bounds(x + 8, y + 178, 155, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(x + 268, y + 178, 84, 20).build());
        request("list", "", 0);
        updateButtons();
    }

    private void updateButtons() {
        if (prev != null) prev.active = current > 0;
        if (next != null) next.active = current < notes.size() - 1;
        if (forget != null) forget.active = !notes.isEmpty();
    }

    private void request(String op, String text, int id) {
        status = "Loading...";
        NpcNetwork.CHANNEL.sendToServer(new MemoryRequestPacket(entityId, op, text, id));
    }

    public void accept(int incomingEntity, String json, String error) {
        if (incomingEntity != entityId) return;
        if (!error.isEmpty()) { status = error; return; }
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            chats = obj.get("chats").getAsInt();
            affinity = obj.get("affinity").getAsInt();
            notes.clear();
            JsonArray array = obj.getAsJsonArray("notes");
            for (int i = 0; i < array.size() && i < 30; i++) {
                JsonObject note = array.get(i).getAsJsonObject();
                notes.add(new Note(note.get("id").getAsInt(), note.get("text").getAsString()));
            }
            current = Math.min(current, Math.max(0, notes.size() - 1));
            status = "Memory updated. Stored on the local NPCortex backend.";
            confirmClear = false;
            updateButtons();
        } catch (Exception exception) {
            status = "Invalid memory response from backend.";
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics);
        int x = (width - 360) / 2;
        int y = Math.max(6, (height - 225) / 2);
        graphics.fill(x, y, x + 360, y + 221, 0xEF152027);
        graphics.fill(x, y, x + 360, y + 2, 0xFF8BA66A);
        graphics.drawString(font, "NPCortex / Memory: " + font.plainSubstrByWidth(name, 180),
                x + 8, y + 11, 0xFFE4EDDA, false);
        graphics.drawString(font, "Chats: " + chats + " | Friendship: " + affinity + "/100",
                x + 8, y + 28, 0xFFABD7A7, false);
        graphics.drawString(font, "Saved note " + (notes.isEmpty() ? "0/0" : (current + 1) + "/" + notes.size()),
                x + 8, y + 108, 0xFFE4EDDA, false);
        if (!notes.isEmpty()) {
            String text = notes.get(current).text();
            var lines = font.split(Component.literal(text), 340);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.drawString(font, lines.get(i), x + 8, y + 119 + i * 11, 0xFFCAD2C1, false);
            }
        }
        graphics.drawString(font, font.plainSubstrByWidth(status, 344), x + 8, y + 207,
                0xFFABD7A7, false);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return true; }
}
