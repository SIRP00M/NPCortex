package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.ManagerProtocol.Row;
import io.github.sirp00m.npcortex.ManagerProtocol.RowState;
import io.github.sirp00m.npcortex.ManagerProtocol.Status;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** ESC menu -> NPCortex Manager: the NPCs of the current world and their Character Profile status. */
final class ManagerScreen extends Screen implements ManagerClient.Listener {
    /**
     * The game pauses (single-player) while the Manager is open. The integrated server still runs queued
     * tasks while paused (IntegratedServer.tickServer only skips the vanilla tick), so backend completions
     * and packet handlers keep working. If Owner evidence shows otherwise, flip this to false.
     */
    static final boolean PAUSE_WHILE_OPEN = true;
    private static final long TIMEOUT_MS = 25_000;
    private static final int ROW_HEIGHT = 34;

    private final Screen parent;
    private List<Row> rows = List.of();
    private int page;
    private long pendingOp;
    private long sentAt;
    private Component status = Component.empty();
    private int statusColor = 0xAAAAAA;
    private boolean needsRefresh = true;
    /** Stage 4: a server-issued preview waiting for Confirm. The nonce is opaque; the client holds no coordinates. */
    private String previewNpc = "";
    private String previewNonce = "";

    ManagerScreen(Screen parent) {
        super(Component.translatable("npcortex.manager.title"));
        this.parent = parent;
    }

    @Override public boolean isPauseScreen() { return PAUSE_WHILE_OPEN; }

    /** A child screen changed something: reload the list when this screen is shown again. */
    void markStale() { needsRefresh = true; }

    private int pageSize() { return Math.max(1, Math.min(8, (this.height - 110) / ROW_HEIGHT)); }

    @Override protected void init() {
        ManagerClient.attach(this);
        if (needsRefresh) { needsRefresh = false; refresh(false); }
        int bottom = this.height - 28;
        int w = 92;
        int left = this.width / 2 - (w * 3 + 8) / 2;
        Button create = this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.create"),
                b -> this.minecraft.setScreen(ProfileScreen.forCreate(this))).bounds(left, bottom, w, 20).build());
        Button refresh = this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.refresh"),
                b -> refresh(true)).bounds(left + w + 4, bottom, w, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.close"),
                b -> onClose()).bounds(left + 2 * (w + 4), bottom, w, 20).build());
        create.active = pendingOp == 0;
        refresh.active = pendingOp == 0;
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); })
                .bounds(this.width / 2 - 70, bottom - 24, 20, 20).build()).active = page > 0;
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
                .bounds(this.width / 2 + 50, bottom - 24, 20, 20).build()).active = (page + 1) * pageSize() < rows.size();
        int top = 44;
        int start = page * pageSize();
        for (int i = start; i < Math.min(rows.size(), start + pageSize()); i++) {
            Row row = rows.get(i);
            int y = top + (i - start) * ROW_HEIGHT;
            boolean editable = row.state() == RowState.OK;
            boolean registrable = row.state() == RowState.NOT_REGISTERED && "custom".equals(row.mode());
            String key = editable ? "npcortex.manager.edit" : registrable ? "npcortex.manager.register" : "npcortex.manager.view";
            Button action = this.addRenderableWidget(Button.builder(Component.translatable(key), b -> open(row))
                    .bounds(this.width - 100, y + 4, 80, 20).build());
            action.active = pendingOp == 0 && (editable || registrable);
            // Stage 4: no-op world action (changes nothing in the world) and the release of an unresolved one.
            this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.noop"),
                    b -> noop(row, false)).bounds(this.width - 100 - 94, y + 4, 52, 20).build()).active = pendingOp == 0 && row.active();
            this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.noop.reconcile"),
                    b -> noop(row, true)).bounds(this.width - 100 - 40, y + 4, 36, 20).build()).active = pendingOp == 0 && row.active();
            // Stage 4 native world actions: the SERVER picks the target at Preview; Confirm sends only the nonce.
            this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.native.break"),
                    b -> nativeRequest(row, ManagerProtocol.NATIVE_PREVIEW_BREAK, "")).bounds(this.width - 100 - 124, y + 4, 28, 20).build()).active = pendingOp == 0 && row.active();
            this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.native.pickup"),
                    b -> nativeRequest(row, ManagerProtocol.NATIVE_PREVIEW_PICKUP, "")).bounds(this.width - 100 - 156, y + 4, 28, 20).build()).active = pendingOp == 0 && row.active();
        }
        if (!previewNonce.isEmpty()) {
            Row target = rows.stream().filter(r -> r.name().equalsIgnoreCase(previewNpc)).findFirst().orElse(null);
            if (target != null) {
                Button confirm = this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.native.confirm", previewNpc),
                        b -> nativeRequest(target, ManagerProtocol.NATIVE_CONFIRM, previewNonce)).bounds(this.width / 2 - 104, bottom - 48, 100, 20).build());
                Button cancel = this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.native.cancel"),
                        b -> nativeRequest(target, ManagerProtocol.NATIVE_CANCEL, "")).bounds(this.width / 2 + 4, bottom - 48, 100, 20).build());
                confirm.active = pendingOp == 0;
                cancel.active = pendingOp == 0;
            }
        }
    }

    private String lastNativeNpc = "";

    private void nativeRequest(Row row, int action, String nonce) {
        pendingOp = ManagerClient.nextOpId();
        sentAt = System.currentTimeMillis();
        lastNativeNpc = row.name();
        previewNpc = "";                      // a new request replaces any preview and a confirm is single-use
        previewNonce = "";
        if (!ManagerClient.send(new ManagerProtocol.NativeReq(pendingOp, row.name(), action, nonce))) {
            pendingOp = 0;
            setStatus(Component.translatable("npcortex.manager.status.notsent"), 0xFF5555);
        } else {
            setStatus(Component.translatable("npcortex.manager.status.loading"), 0xFFFF55);
        }
        rebuildWidgets();
    }

    private void open(Row row) {
        if (row.state() == RowState.OK) this.minecraft.setScreen(ProfileScreen.forEdit(this, row));
        else if (row.state() == RowState.NOT_REGISTERED) this.minecraft.setScreen(ProfileScreen.forRegister(this, row));
    }

    private void noop(Row row, boolean reconcile) {
        pendingOp = ManagerClient.nextOpId();
        sentAt = System.currentTimeMillis();
        if (!ManagerClient.send(new ManagerProtocol.NoopReq(pendingOp, row.name(), reconcile))) {
            pendingOp = 0;
            setStatus(Component.translatable("npcortex.manager.status.notsent"), 0xFF5555);
        } else {
            setStatus(Component.translatable("npcortex.manager.status.loading"), 0xFFFF55);
        }
        rebuildWidgets();
    }

    private void refresh(boolean rebuild) {
        pendingOp = ManagerClient.nextOpId();
        sentAt = System.currentTimeMillis();
        if (!ManagerClient.send(new ManagerProtocol.ListReq(pendingOp))) {
            pendingOp = 0;
            setStatus(Component.translatable("npcortex.manager.status.notsent"), 0xFF5555);
        } else {
            setStatus(Component.translatable("npcortex.manager.status.loading"), 0xFFFF55);
        }
        if (rebuild) rebuildWidgets();
    }

    private void setStatus(Component text, int color) { status = text; statusColor = color; }

    @Override public void tick() {
        // Wall-clock on purpose: game time does not advance while the game is paused.
        if (pendingOp != 0 && System.currentTimeMillis() - sentAt > TIMEOUT_MS) {
            pendingOp = 0;
            setStatus(Component.translatable("npcortex.manager.status.unknown"), 0xFFAA00);
            rebuildWidgets();
        }
    }

    @Override public void onMessage(ManagerProtocol.ToClient message) {
        if (message.opId() != pendingOp) return; // stale or foreign reply
        pendingOp = 0;
        if (message instanceof ManagerProtocol.Denied denied) {
            rows = List.of();
            setStatus(Component.translatable("npcortex.manager.status.denied"), 0xFF5555);
        } else if (message instanceof ManagerProtocol.NativeRep rep) {
            setStatus(Component.literal("[" + rep.status() + "] " + rep.message()),
                    rep.status() == ManagerProtocol.Status.OK ? 0x55FF55 : 0xFFAA00);
            if (!rep.nonce().isEmpty()) { previewNpc = lastNativeNpc; previewNonce = rep.nonce(); }
            rebuildWidgets();
            return;
        } else if (message instanceof ManagerProtocol.OpRep rep && (rep.op() == ManagerProtocol.Op.NOOP || rep.op() == ManagerProtocol.Op.NATIVE)) {
            setStatus(Component.literal("[" + rep.status() + "] " + rep.message()),
                    rep.status() == ManagerProtocol.Status.OK ? 0x55FF55 : 0xFFAA00);
            rebuildWidgets();
            return;
        } else if (message instanceof ManagerProtocol.ListRep list) {
            rows = new ArrayList<>(list.rows());
            page = Math.min(page, Math.max(0, (rows.size() - 1) / pageSize()));
            switch (list.status()) {
                case OK -> setStatus(Component.translatable("npcortex.manager.status.loaded", rows.size()), 0x55FF55);
                case OFFLINE -> setStatus(Component.translatable("npcortex.manager.status.offline"), 0xFF5555);
                case BUSY -> setStatus(Component.translatable("npcortex.manager.status.busy"), 0xFFFF55);
                default -> setStatus(Component.literal(list.message()), 0xFF5555);
            }
        }
        rebuildWidgets();
    }

    @Override public void onClose() {
        ManagerClient.detach(this);
        this.minecraft.setScreen(parent);
    }

    @Override public void removed() { ManagerClient.detach(this); }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        g.drawCenteredString(this.font, Component.translatable("npcortex.manager.subtitle"), this.width / 2, 26, 0x888888);
        int top = 44;
        int start = page * pageSize();
        if (rows.isEmpty() && pendingOp == 0)
            g.drawCenteredString(this.font, Component.translatable("npcortex.manager.empty"), this.width / 2, top + 20, 0xAAAAAA);
        for (int i = start; i < Math.min(rows.size(), start + pageSize()); i++) {
            Row row = rows.get(i);
            int y = top + (i - start) * ROW_HEIGHT;
            g.fill(20, y, this.width - 262, y + ROW_HEIGHT - 4, 0x55000000);
            g.drawString(this.font, row.name() + (row.active() ? "" : " (inactive)"), 26, y + 3, 0xFFFFFF);
            g.drawString(this.font, stateText(row), 26, y + 14, stateColor(row.state()));
            String detail = this.font.plainSubstrByWidth(row.mode() + " | " + row.dimension() + " | " + row.detail(), this.width - 296);
            g.drawString(this.font, detail, 26, y + 24 - 2, 0x888888);
        }
        if (rows.size() > pageSize())
            g.drawCenteredString(this.font, (page + 1) + "/" + (((rows.size() - 1) / pageSize()) + 1), this.width / 2, this.height - 46, 0xAAAAAA);
        g.drawCenteredString(this.font, status, this.width / 2, this.height - 62, statusColor);
        super.render(g, mouseX, mouseY, partial);
    }

    private static Component stateText(Row row) {
        return switch (row.state()) {
            case OK -> Component.translatable("npcortex.manager.state.ok", row.revision(), row.displayName());
            case NOT_REGISTERED -> Component.translatable("npcortex.manager.state.notregistered");
            case LEGACY -> Component.translatable("npcortex.manager.state.legacy");
            case OFFLINE -> Component.translatable("npcortex.manager.state.offline");
        };
    }

    private static int stateColor(RowState state) {
        return switch (state) {
            case OK -> 0x55FF55;
            case NOT_REGISTERED -> 0xFFFF55;
            case LEGACY -> 0xAAAAAA;
            case OFFLINE -> 0xFF5555;
        };
    }

    static int colorOf(Status status) {
        return switch (status) {
            case OK -> 0x55FF55;
            case CONFLICT, BUSY -> 0xFFFF55;
            case UNKNOWN -> 0xFFAA00;
            default -> 0xFF5555;
        };
    }
}
