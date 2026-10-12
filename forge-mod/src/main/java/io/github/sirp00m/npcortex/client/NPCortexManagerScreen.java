package io.github.sirp00m.npcortex.client;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import io.github.sirp00m.npcortex.network.NpcNetwork;
import io.github.sirp00m.npcortex.network.UpdateSkinPacket;
import java.awt.FileDialog;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * ESC > NPCortex Manager. All file operations are local to this Minecraft instance.
 * Does not contact the backend and does not upload the player's files over a network.
 */
public final class NPCortexManagerScreen extends Screen {
    private final Screen parent;
    private EditBox skinId;
    private int selectedEntity = -1;
    private boolean slim;
    private String message = "Select a nearby companion, then import a 64x64 PNG.";
    private boolean problem;
    private boolean fileDialogOpen;
    private int panelX, panelY, panelW;
    private Button modelButton;

    public NPCortexManagerScreen(Screen parent) {
        super(Component.literal("NPCortex Manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(460, width - 16);
        panelX = (width - panelW) / 2;
        panelY = Math.max(4, (height - 274) / 2);
        int left = panelX + 12;
        int right = panelX + panelW - 12;
        int full = panelW - 24;
        skinId = addRenderableWidget(new EditBox(font, left, panelY + 60, full, 20,
                Component.literal("Skin ID")));
        skinId.setMaxLength(40);
        skinId.setValue("yui");
        skinId.setHint(Component.literal("Lowercase skin ID, e.g. yui"));

        addRenderableWidget(Button.builder(Component.literal("Select nearby NPC"), btn -> selectNextNpc())
                .bounds(left, panelY + 31, Math.min(151, full), 20).build());
        modelButton = addRenderableWidget(Button.builder(Component.literal("Arms: Wide"), btn -> {
                    slim = !slim;
                    modelButton.setMessage(Component.literal(slim ? "Arms: Slim" : "Arms: Wide"));
                }).bounds(right - Math.min(113, full / 3), panelY + 31, Math.min(113, full / 3), 20).build());

        int half = (full - 6) / 2;
        addRenderableWidget(Button.builder(Component.literal("Import PNG..."), btn -> browse())
                .bounds(left, panelY + 90, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload Skin"), btn -> reloadSkin())
                .bounds(left + half + 6, panelY + 90, full - half - 6, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Apply to selected NPC"), btn -> applyAppearance())
                .bounds(left, panelY + 117, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open Skins Folder"), btn -> openFolder())
                .bounds(left + half + 6, panelY + 117, full - half - 6, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Memory Manager"), btn -> openMemory())
                .bounds(left, panelY + 211, Math.min(142, full - 74), 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open Character Packs"), btn -> openPacks())
                .bounds(left + Math.min(142, full - 74) + 4, panelY + 211, Math.min(150, full - 225), 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(right - 68, panelY + 211, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Manage NPC Autonomy"), btn -> openAutonomy())
                .bounds(left, panelY + 239, full, 20).build());
        selectNearestIfAvailable();
    }

    private List<CompanionEntity> nearbyNpcs() {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return List.of();
        List<CompanionEntity> found = minecraft.level.getEntitiesOfClass(CompanionEntity.class,
                minecraft.player.getBoundingBox().inflate(12.0D));
        found.sort(Comparator.comparingDouble(minecraft.player::distanceToSqr));
        return found;
    }

    private CompanionEntity selectedNpc() {
        return nearbyNpcs().stream().filter(n -> n.getId() == selectedEntity).findFirst().orElse(null);
    }

    private void selectNearestIfAvailable() {
        List<CompanionEntity> npcs = nearbyNpcs();
        if (!npcs.isEmpty()) selectNpc(npcs.get(0));
    }

    private void selectNextNpc() {
        List<CompanionEntity> npcs = nearbyNpcs();
        if (npcs.isEmpty()) {
            selectedEntity = -1;
            status("No companion within 12 blocks. You can still import a skin by ID.", true);
            return;
        }
        int index = -1;
        for (int i = 0; i < npcs.size(); ++i) {
            if (npcs.get(i).getId() == selectedEntity) { index = i; break; }
        }
        selectNpc(npcs.get((index + 1) % npcs.size()));
    }

    private void selectNpc(CompanionEntity npc) {
        selectedEntity = npc.getId();
        skinId.setValue(npc.getSkinId());
        slim = npc.isSlimModel();
        modelButton.setMessage(Component.literal(slim ? "Arms: Slim" : "Arms: Wide"));
        status("Selected " + npc.getName().getString() + " (skin: " + npc.getSkinId() + ")", false);
    }

    private void browse() {
        if (fileDialogOpen) return;
        if (!SkinFileManager.validId(skinId.getValue())) {
            status("Enter a valid lowercase Skin ID before importing.", true);
            return;
        }
        final String id = skinId.getValue();
        fileDialogOpen = true;
        status("Choose a PNG in your file picker. You can also drag a PNG onto this screen.", false);
        // Native AWT picker can block on some systems; never run it on the render thread.
        Thread thread = new Thread(() -> {
            try {
                FileDialog picker = new FileDialog((Frame) null, "Import Minecraft 64x64 skin", FileDialog.LOAD);
                picker.setFile("*.png");
                picker.setFilenameFilter((folder, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
                picker.setVisible(true);
                Path path = picker.getFile() == null ? null : Path.of(picker.getDirectory(), picker.getFile());
                picker.dispose();
                Minecraft.getInstance().execute(() -> {
                    fileDialogOpen = false;
                    if (path != null) importPng(path, id);
                    else status("Import cancelled.", false);
                });
            } catch (Exception exception) {
                Minecraft.getInstance().execute(() -> {
                    fileDialogOpen = false;
                    status("File picker unavailable. Drag a 64x64 PNG onto this screen instead.", true);
                });
            }
        }, "NPCortex-file-picker");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (paths.size() != 1) {
            status("Drop exactly one 64x64 PNG skin at a time.", true);
            return;
        }
        importPng(paths.get(0), skinId.getValue());
    }

    private void importPng(Path source, String id) {
        try {
            Path dest = SkinFileManager.importPng(source, SkinTextureCache.skinDirectory(), id);
            SkinTextureCache.reload(id);
            status("Imported " + dest.getFileName() + " - texture refreshed immediately.", false);
        } catch (Exception exception) {
            status("Import failed: " + exception.getMessage(), true);
        }
    }

    private void reloadSkin() {
        String id = skinId.getValue();
        if (!SkinFileManager.validId(id)) {
            status("Invalid skin ID. Use lowercase letters, digits, _ or -.", true);
            return;
        }
        SkinTextureCache.reload(id);
        status(SkinTextureCache.exists(id)
                ? "Reloaded " + id + ".png from this Minecraft instance."
                : "Skin " + id + ".png is missing. Import it first or check the folder.",
                !SkinTextureCache.exists(id));
    }

    private void applyAppearance() {
        String id = skinId.getValue();
        CompanionEntity npc = selectedNpc();
        if (!SkinFileManager.validId(id)) {
            status("Invalid skin ID. Use lowercase letters, digits, _ or -.", true);
            return;
        }
        if (npc == null) {
            status("Select a nearby companion before applying.", true);
            return;
        }
        if (!SkinTextureCache.exists(id)) {
            status("No local PNG for '" + id + "'. Import it first.", true);
            return;
        }
        NpcNetwork.CHANNEL.sendToServer(new UpdateSkinPacket(npc.getId(), id, slim));
        SkinTextureCache.reload(id);
        status("Appearance update requested for " + npc.getName().getString()
                + ". Server will apply only if you own this NPC and are nearby.", false);
    }

    private void openAutonomy() {
        minecraft.setScreen(new AutonomyManagementScreen(this));
    }

    private void openMemory() {
        CompanionEntity npc = selectedNpc();
        if (npc == null) {
            status("Select an owned nearby NPC first.", true);
            return;
        }
        minecraft.setScreen(new MemoryManagementScreen(this, npc.getId(), npc.getName().getString()));
    }

    private void openFolder() {
        try {
            Path folder = SkinTextureCache.skinDirectory();
            Files.createDirectories(folder);
            Util.getPlatform().openFile(folder.toFile());
            status("Opened Minecraft's actual skins directory.", false);
        } catch (Exception error) {
            status("Cannot open folder: " + error.getMessage(), true);
        }
    }

    private void openPacks() {
        // Only the development client can infer a repository path reliably.
        // On dedicated multiplayer or CurseForge the backend may live elsewhere.
        Path maybeRepo = minecraft.gameDirectory.toPath().resolve("..").resolve("..")
                .resolve("character-packs").normalize();
        if (!Files.isDirectory(maybeRepo)) {
            status("Character Packs are on the backend host; edit them there.", true);
            return;
        }
        try {
            Util.getPlatform().openFile(maybeRepo.toFile());
            status("Opened local Character Packs folder (restart backend after server code updates).", false);
        } catch (Exception error) {
            status("Cannot open Character Packs: " + error.getMessage(), true);
        }
    }

    private void status(String text, boolean error) {
        message = text;
        problem = error;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        renderBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 274, 0xEF152027);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 2, 0xFF8BA66A);
        graphics.drawString(font, "NPCortex / Character Manager", panelX + 12, panelY + 9, 0xFFE4EDDA, false);
        graphics.drawString(font, "Skin ID", panelX + 12, panelY + 52, 0xFFC6C8B8, false);
        List<String> skins = SkinFileManager.installed(SkinTextureCache.skinDirectory());
        graphics.drawString(font, "Installed: " + skins.size() + "  |  "
                        + (SkinTextureCache.exists(skinId.getValue()) ? "PNG found" : "PNG not found"),
                panelX + 12, panelY + 146, 0xFFCAD2C1, false);
        CompanionEntity npc = selectedNpc();
        graphics.drawString(font, "NPC: " + (npc == null ? "(none selected nearby)" : npc.getName().getString()),
                panelX + 12, panelY + 159, 0xFFCAD2C1, false);
        // Preview the raw 64x64 Minecraft skin atlas (not a rendered 3D character).
        if (SkinFileManager.validId(skinId.getValue()) && SkinTextureCache.exists(skinId.getValue())) {
            ResourceLocation texture = SkinTextureCache.resolve(skinId.getValue(),
                    DefaultPlayerSkin.getDefaultSkin(minecraft.player == null
                            ? new java.util.UUID(0L, 0L) : minecraft.player.getUUID()));
            graphics.blit(texture, panelX + panelW - 80, panelY + 143, 0, 0, 64, 64, 64, 64);
        }
        int col = problem ? 0xFFFFAA9E : 0xFFABD7A7;
        graphics.drawString(font, font.plainSubstrByWidth(message, panelW - 108),
                panelX + 12, panelY + 185, col, false);
        graphics.drawString(font, font.plainSubstrByWidth(
                        "Drag/drop one PNG here. Skin files stay on your computer.", panelW - 108),
                panelX + 12, panelY + 198, 0xFFAAB4AD, false);
        super.render(graphics, mouseX, mouseY, tick);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return true; }
}
