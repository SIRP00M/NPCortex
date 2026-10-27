package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.ManagerProtocol.Draft;
import io.github.sirp00m.npcortex.ManagerProtocol.Op;
import io.github.sirp00m.npcortex.ManagerProtocol.Row;
import io.github.sirp00m.npcortex.ManagerProtocol.Status;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Create an NPC with its Character Profile, retry registering a pending one, or edit a native profile.
 * The draft lives in this screen: a failed or unknown result never clears what the player typed.
 */
final class ProfileScreen extends Screen implements ManagerClient.Listener {
    enum Mode { CREATE, REGISTER, EDIT }

    // Above the server worst case (world + actor + read-back, 15 s each) so a slow success is not shown as UNKNOWN.
    private static final long TIMEOUT_MS = 50_000;

    private final Screen parent;
    private Mode mode;
    private String npcName = "";
    private int revision;
    private Draft baseline;           // last saved/loaded state; null until there is one
    private boolean editLoaded;       // EDIT: the server version has been loaded at least once

    private String nameText = "";
    private boolean structured = true;
    private String displayName = "";
    private String personality = "";
    private String speech = "";
    private String backstory = "";
    private String raw = "";
    private int tab;

    private boolean suppress;
    private boolean forceClose;
    private boolean confirming;
    private boolean changed;
    private long pendingOp;
    private long sentAt;
    private boolean loadStarted;
    private Component status = Component.empty();
    private int statusColor = 0xAAAAAA;
    private MultiLineEditBox textBox;

    private ProfileScreen(Screen parent, Mode mode) {
        super(Component.translatable("npcortex.manager.profile.title." + mode.name().toLowerCase(java.util.Locale.ROOT)));
        this.parent = parent;
        this.mode = mode;
    }

    static ProfileScreen forCreate(Screen parent) { return new ProfileScreen(parent, Mode.CREATE); }

    static ProfileScreen forRegister(Screen parent, Row row) {
        ProfileScreen screen = new ProfileScreen(parent, Mode.REGISTER);
        screen.npcName = row.name();
        screen.displayName = row.name();
        return screen;
    }

    static ProfileScreen forEdit(Screen parent, Row row) {
        ProfileScreen screen = new ProfileScreen(parent, Mode.EDIT);
        screen.npcName = row.name();
        screen.revision = row.revision();
        return screen;
    }

    @Override public boolean isPauseScreen() { return ManagerScreen.PAUSE_WHILE_OPEN; }

    // ------------------------------------------------------------------ draft handling

    private Draft currentDraft() {
        return structured ? Draft.structured(displayName, personality, speech, backstory) : Draft.raw(displayName, raw);
    }

    private boolean dirty() {
        if (baseline == null)
            return !nameText.isEmpty() && mode == Mode.CREATE || !displayName.isEmpty() || !personality.isEmpty()
                    || !speech.isEmpty() || !backstory.isEmpty() || !raw.isEmpty();
        return !ManagerProtocol.normalised(currentDraft()).equals(ManagerProtocol.normalised(baseline));
    }

    private String active() {
        return structured ? (tab == 0 ? personality : tab == 1 ? speech : backstory) : raw;
    }

    private void setActive(String value) {
        if (!structured) raw = value;
        else if (tab == 0) personality = value;
        else if (tab == 1) speech = value;
        else backstory = value;
    }

    private int limit() {
        return !structured ? ManagerProtocol.PROMPT_MAX
                : tab == 0 ? ManagerProtocol.PERSONALITY_MAX : tab == 1 ? ManagerProtocol.SPEECH_STYLE_MAX : ManagerProtocol.BACKSTORY_MAX;
    }

    private String placeholderKey() {
        return !structured ? "raw" : tab == 0 ? "personality" : tab == 1 ? "speech" : "backstory";
    }

    private void applyDraft(Draft draft) {
        structured = draft.structured();
        displayName = draft.displayName();
        personality = draft.personality();
        speech = draft.speechStyle();
        backstory = draft.backstory();
        raw = draft.systemPrompt();
        tab = 0;
    }

    private void setStatus(Component text, int color) { status = text; statusColor = color; }

    // ------------------------------------------------------------------ widgets

    @Override protected void init() {
        ManagerClient.attach(this);
        int left = 20;
        int fieldWidth = this.width - 40;
        EditBox nameBox = null;
        if (mode == Mode.CREATE) {
            nameBox = new EditBox(this.font, left + 70, 30, 130, 18, Component.translatable("npcortex.manager.field.name"));
            nameBox.setMaxLength(ManagerProtocol.NAME_MAX);
            nameBox.setFilter(s -> s.matches("[A-Za-z0-9_]*"));
            nameBox.setValue(nameText);
            nameBox.setHint(Component.translatable("npcortex.manager.hint.name"));
            nameBox.setResponder(s -> nameText = s);
            this.addRenderableWidget(nameBox);
        }
        EditBox displayBox = new EditBox(this.font, left + 70, 52, Math.min(220, fieldWidth - 70), 18,
                Component.translatable("npcortex.manager.field.display"));
        displayBox.setMaxLength(ManagerProtocol.DISPLAY_NAME_MAX);
        displayBox.setValue(displayName);
        displayBox.setHint(Component.translatable("npcortex.manager.hint.display"));
        displayBox.setResponder(s -> displayName = s);
        this.addRenderableWidget(displayBox);

        int rowY = 76;
        this.addRenderableWidget(Button.builder(Component.translatable(structured ? "npcortex.manager.mode.structured" : "npcortex.manager.mode.raw"),
                b -> { structured = !structured; tab = 0; rebuildWidgets(); })
                .bounds(left, rowY, 110, 18).build()).active = pendingOp == 0;
        if (structured) {
            String[] keys = {"npcortex.manager.tab.personality", "npcortex.manager.tab.speech", "npcortex.manager.tab.backstory"};
            for (int i = 0; i < 3; i++) {
                final int index = i;
                Button tabButton = this.addRenderableWidget(Button.builder(Component.translatable(keys[i]),
                        b -> { tab = index; rebuildWidgets(); }).bounds(left + 116 + i * 82, rowY, 80, 18).build());
                tabButton.active = tab != i;
            }
        }
        int boxTop = rowY + 24;
        int boxHeight = Math.max(40, this.height - boxTop - 66);
        textBox = new MultiLineEditBox(this.font, left, boxTop, fieldWidth, boxHeight,
                Component.translatable("npcortex.manager.placeholder." + placeholderKey()), Component.empty());
        textBox.setCharacterLimit(limit());
        suppress = true;
        textBox.setValue(active());
        suppress = false;
        textBox.setValueListener(s -> { if (!suppress) setActive(s); });
        this.addRenderableWidget(textBox);

        boolean idle = pendingOp == 0;
        int by = this.height - 28;
        int w = 100;
        int x0 = this.width / 2 - (w * 3 + 8) / 2;
        String primaryKey = mode == Mode.CREATE ? "npcortex.manager.action.create"
                : mode == Mode.REGISTER ? "npcortex.manager.action.register" : "npcortex.manager.action.save";
        Button primary = this.addRenderableWidget(Button.builder(Component.translatable(primaryKey), b -> submit())
                .bounds(x0, by, w, 20).build());
        primary.active = idle && (mode != Mode.EDIT || editLoaded);
        Button reload = this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.action.reload"), b -> reload())
                .bounds(x0 + w + 4, by, w, 20).build());
        reload.active = idle && mode == Mode.EDIT;
        reload.visible = mode == Mode.EDIT;
        this.addRenderableWidget(Button.builder(Component.translatable("npcortex.manager.action.cancel"), b -> onClose())
                .bounds(x0 + 2 * (w + 4), by, w, 20).build());
        if (mode == Mode.EDIT && !loadStarted) { loadStarted = true; startLoad(); }
    }

    // ------------------------------------------------------------------ actions

    private boolean send(ManagerProtocol.ToServer message, Component waiting) {
        sentAt = System.currentTimeMillis();
        if (!ManagerClient.send(message)) {
            pendingOp = 0;
            setStatus(Component.translatable("npcortex.manager.status.notsent"), 0xFF5555);
            return false;
        }
        setStatus(waiting, 0xFFFF55);
        return true;
    }

    private void startLoad() {
        pendingOp = ManagerClient.nextOpId();
        send(new ManagerProtocol.LoadReq(pendingOp, npcName), Component.translatable("npcortex.manager.status.loading"));
    }

    private void reload() {
        if (dirty()) {
            confirming = true;
            this.minecraft.setScreen(new ConfirmScreen(yes -> {
                confirming = false;
                this.minecraft.setScreen(this);
                if (yes) { startLoad(); rebuildWidgets(); }
            }, Component.translatable("npcortex.manager.confirm.reload.title"),
                    Component.translatable("npcortex.manager.confirm.reload.body")));
            return;
        }
        startLoad();
        rebuildWidgets();
    }

    private void submit() {
        String error = mode == Mode.CREATE ? ManagerProtocol.validateName(nameText) : null;
        if (error == null) error = ManagerProtocol.validateDraft(currentDraft());
        if (error != null) {
            setStatus(Component.literal(error), 0xFF5555);
            return;
        }
        pendingOp = ManagerClient.nextOpId();
        Draft draft = ManagerProtocol.normalised(currentDraft());
        ManagerProtocol.ToServer request = switch (mode) {
            case CREATE -> new ManagerProtocol.CreateReq(pendingOp, nameText, draft);
            case REGISTER -> new ManagerProtocol.RegisterReq(pendingOp, npcName, draft);
            case EDIT -> new ManagerProtocol.SaveReq(pendingOp, npcName, revision, draft);
        };
        send(request, Component.translatable("npcortex.manager.status.working"));
        rebuildWidgets();
    }

    @Override public void tick() {
        // Wall-clock on purpose: game time does not advance while the game is paused.
        if (pendingOp != 0 && System.currentTimeMillis() - sentAt > TIMEOUT_MS) {
            pendingOp = 0;
            changed = true; // the request may have been applied: make the list re-read the truth
            setStatus(Component.translatable("npcortex.manager.status.unknown"), 0xFFAA00);
            rebuildWidgets();
        }
    }

    @Override public void onMessage(ManagerProtocol.ToClient message) {
        if (message.opId() != pendingOp) return; // stale reply from an earlier request
        pendingOp = 0;
        if (message instanceof ManagerProtocol.Denied) {
            setStatus(Component.translatable("npcortex.manager.status.denied"), 0xFF5555);
        } else if (message instanceof ManagerProtocol.OpRep reply) {
            apply(reply);
        }
        rebuildWidgets();
    }

    private void apply(ManagerProtocol.OpRep reply) {
        Component text = Component.literal(reply.message());
        int color = ManagerScreen.colorOf(reply.status());
        if (reply.op() == Op.LOAD) {
            if (reply.status() == Status.OK && reply.draft() != null) {
                applyDraft(reply.draft());
                baseline = reply.draft();
                revision = reply.revision();
                editLoaded = true;
            }
            setStatus(text, color);
            return;
        }
        if (reply.status() == Status.OK) {
            changed = true;
            if (reply.op() == Op.CREATE) npcName = nameText;
            if (mode != Mode.EDIT) {
                mode = Mode.EDIT;
                editLoaded = true;
            }
            revision = reply.revision();
            baseline = currentDraft();
            setStatus(text, color);
            return;
        }
        if (reply.op() == Op.CREATE && reply.row() != null) {
            // The NPC exists locally but its profile is NOT registered: keep the draft, offer Retry.
            changed = true;
            mode = Mode.REGISTER;
            npcName = nameText;
        }
        setStatus(text, color);
    }

    // ------------------------------------------------------------------ closing

    @Override public void onClose() {
        if (dirty() && !forceClose) {
            confirming = true;
            this.minecraft.setScreen(new ConfirmScreen(discard -> {
                confirming = false;
                if (discard) { forceClose = true; leave(); }
                else this.minecraft.setScreen(this);
            }, Component.translatable("npcortex.manager.confirm.discard.title"),
                    Component.translatable("npcortex.manager.confirm.discard.body")));
            return;
        }
        leave();
    }

    private void leave() {
        ManagerClient.detach(this);
        // pendingOp != 0: an answer may have been lost while closing; make the list re-read the truth.
        if ((changed || pendingOp != 0) && parent instanceof ManagerScreen manager) manager.markStale();
        this.minecraft.setScreen(parent);
    }

    // A confirmation dialog replaces this screen temporarily: keep listening so a reply is not lost.
    @Override public void removed() { if (!confirming) ManagerClient.detach(this); }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, Component.translatable("npcortex.manager.profile.title." + mode.name().toLowerCase(java.util.Locale.ROOT)), this.width / 2, 10, 0xFFFFFF);
        if (mode == Mode.CREATE) g.drawString(this.font, Component.translatable("npcortex.manager.field.name"), 20, 35, 0xCCCCCC);
        else g.drawString(this.font, Component.translatable("npcortex.manager.field.npc", npcName,
                mode == Mode.EDIT ? Component.translatable("npcortex.manager.revision", revision) : Component.translatable("npcortex.manager.field.pending")),
                20, 35, 0xCCCCCC);
        g.drawString(this.font, Component.translatable("npcortex.manager.field.display"), 20, 57, 0xCCCCCC);
        if (dirty()) g.drawString(this.font, Component.translatable("npcortex.manager.unsaved"), this.width - 110, 10, 0xFFAA00);
        super.render(g, mouseX, mouseY, partial);
        String counter = ManagerProtocol.length(active()) + "/" + limit();
        g.drawString(this.font, counter, this.width - 20 - this.font.width(counter), this.height - 62, 0x888888);
        g.drawCenteredString(this.font, status, this.width / 2, this.height - 46, statusColor);
    }
}
