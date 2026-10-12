package io.github.sirp00m.npcortex.client;

import io.github.sirp00m.npcortex.AutonomyRoster;
import io.github.sirp00m.npcortex.network.AutonomyRequestPacket;
import io.github.sirp00m.npcortex.network.NpcNetwork;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Server-authoritative roster: never trusts a client-side owner or autonomy flag. */
public final class AutonomyManagementScreen extends Screen {
    private static final int PAGE_SIZE = 6;
    private static final UUID NONE = new UUID(0L, 0L);
    private final Screen parent;
    private final Button[] npcButtons = new Button[PAGE_SIZE];
    private List<AutonomyRoster.Entry> roster = List.of();
    private UUID selected;
    private int requestId;
    private int page;
    private int panelX, panelY, panelW;
    private boolean waiting;
    private String status = "Loading your companions...";
    private Button previousButton, nextButton, enableButton, disableButton;

    public AutonomyManagementScreen(Screen parent) {
        super(Component.literal("NPCortex Autonomy Manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(500, width - 16);
        panelX = (width - panelW) / 2;
        panelY = Math.max(4, (height - 306) / 2);
        int left = panelX + 12;
        int full = panelW - 24;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> request("list", NONE, false))
                .bounds(left, panelY + 31, Math.max(65, full / 3), 20).build());
        previousButton = addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {
            page = Math.max(0, page - 1);
            updateButtons();
        }).bounds(left + full - 148, panelY + 31, 72, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal("Next >"), b -> {
            page++;
            updateButtons();
        }).bounds(left + full - 72, panelY + 31, 72, 20).build());
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int row = i;
            npcButtons[i] = addRenderableWidget(Button.builder(Component.literal("-"), b -> select(page * PAGE_SIZE + row))
                    .bounds(left, panelY + 59 + i * 26, full, 22).build());
        }
        enableButton = addRenderableWidget(Button.builder(Component.literal("Enable Autonomy"),
                b -> setAutonomy(true)).bounds(left, panelY + 241, (full - 8) / 2, 20).build());
        disableButton = addRenderableWidget(Button.builder(Component.literal("Disable Autonomy"),
                b -> setAutonomy(false)).bounds(left + (full - 8) / 2 + 8, panelY + 241,
                full - (full - 8) / 2 - 8, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(left, panelY + 269, full, 20).build());
        updateButtons();
        request("list", NONE, false);
    }

    private void request(String operation, UUID npc, boolean enabled) {
        if (minecraft == null || minecraft.getConnection() == null) {
            status = "Connect to a world to manage NPCs.";
            return;
        }
        waiting = true;
        status = "Contacting Minecraft server...";
        requestId++;
        NpcNetwork.CHANNEL.sendToServer(new AutonomyRequestPacket(requestId, operation, npc, enabled));
        updateButtons();
    }

    private void setAutonomy(boolean enabled) {
        if (selected == null || waiting) return;
        request("set", selected, enabled);
    }

    private void select(int index) {
        if (index < 0 || index >= roster.size()) return;
        selected = roster.get(index).uuid();
        status = "Selected " + roster.get(index).name() + " (" + selected.toString().substring(0, 8) + ")";
        updateButtons();
    }

    public void accept(int replyId, List<AutonomyRoster.Entry> loaded, String message) {
        if (replyId != requestId) return; // Prevent an older roster response replacing a new toggle.
        waiting = false;
        roster = loaded;
        if (selected == null || roster.stream().noneMatch(n -> n.uuid().equals(selected))) {
            selected = roster.isEmpty() ? null : roster.get(0).uuid();
        }
        page = Math.min(page, Math.max(0, (roster.size() - 1) / PAGE_SIZE));
        status = message.isBlank() ? "Loaded " + roster.size() + " owned NPC(s)." : message;
        updateButtons();
    }

    private void updateButtons() {
        for (int i = 0; i < PAGE_SIZE; i++) {
            Button button = npcButtons[i];
            if (button == null) continue;
            int index = page * PAGE_SIZE + i;
            if (index >= roster.size()) {
                button.setMessage(Component.literal(""));
                button.active = false;
            } else {
                var npc = roster.get(index);
                String check = npc.uuid().equals(selected) ? "> " : "  ";
                String label = check + npc.name() + " (" + npc.characterId() + ")  ["
                        + npc.uuid().toString().substring(0, 8) + "]  "
                        + (npc.enabled() ? "ON" : "OFF") + "  " + npc.dimension();
                button.setMessage(Component.literal(font.plainSubstrByWidth(label, panelW - 56)));
                button.active = !waiting;
            }
        }
        if (previousButton != null) previousButton.active = !waiting && page > 0;
        if (nextButton != null) nextButton.active = !waiting && (page + 1) * PAGE_SIZE < roster.size();
        if (enableButton != null) enableButton.active = !waiting && selected != null;
        if (disableButton != null) disableButton.active = !waiting && selected != null;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 300, 0xF0152027);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 2, 0xFF8BA66A);
        graphics.drawString(font, "NPCortex / Autonomy Manager", panelX + 12, panelY + 9, 0xFFE4EDDA, false);
        graphics.drawString(font, "Loaded owned NPCs only  |  Page " + (page + 1) + "/"
                + Math.max(1, (roster.size() + PAGE_SIZE - 1) / PAGE_SIZE),
                panelX + 12, panelY + 217, 0xFFC6C8B8, false);
        graphics.drawString(font, font.plainSubstrByWidth(status, panelW - 24),
                panelX + 12, panelY + 229, 0xFFABD7A7, false);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return true; }
}
