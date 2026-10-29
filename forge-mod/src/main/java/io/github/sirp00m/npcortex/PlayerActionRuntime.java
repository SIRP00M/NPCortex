package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/** Minecraft/Forge binding of {@link PlayerActionFlow} (place_block, use_block) and the PAP-G4 vanilla interaction facade.
 *
 * Authority is the same as for native break/pickup: the authenticated Manager packet sender and the Owner allow-list
 * {@code config/npcortex-native-actions.json} (shared poller, deny-all when stale), plus the built-in safety ceiling in
 * {@link NativeActionPolicy}. An LLM proposal, a backend reply or a command is never authority.
 *
 * Facade rules (verified against Forge 1.20.1 {@code ServerPlayerGameMode.useItemOn}): the call runs Forge's real
 * {@code RightClickBlock} event, {@code onItemUseFirst}, then (unless the avatar is sneaking with a non-empty hand) the block's
 * own {@code use}, then {@code ItemStack.useOn}. PLACE therefore sneaks for the call so a clickable support block is never
 * activated; USE passes an EMPTY stack and does not sneak so an item can never be placed or used by accident. The avatar
 * is NOT a logged-in player: no PlayerList registration, no Player.doTick, reach and line of sight are checked here
 * because vanilla {@code useItemOn} does not check distance.
 */
final class PlayerActionRuntime {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final Map<MinecraftServer, PlayerActionFlow> FLOWS = new IdentityHashMap<>();

    /** Test seam (GameTest only): the support block and clicked face the "requester" looks at. Production never sets it. */
    record Look(BlockPos pos, Direction face) {}
    static volatile java.util.function.Function<NativeEffectFlow.Actor, Look> lookOverride;

    static void clearOverrides() { lookOverride = null; PINS.clear(); HINTS.clear(); }

    /** PAP-G3: the target a deterministic approach walked to. Server-side only (set by PlayerTaskRuntime from the requester's own
     * look at request time); consulted at Preview in place of the live look, never an authority: policy and every check still run. */
    private static final Map<UUID, Look> PINS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, String> HINTS = new java.util.concurrent.ConcurrentHashMap<>();

    static void pin(UUID actorId, Look look, String blockHint) {
        PINS.put(actorId, look);
        if (blockHint == null || blockHint.isEmpty()) HINTS.remove(actorId); else HINTS.put(actorId, blockHint);
    }

    static void unpin(UUID actorId) { PINS.remove(actorId); HINTS.remove(actorId); }

    private PlayerActionRuntime() {}

    // ------------------------------------------------------------------------------------------------ public surface
    static boolean pending(MinecraftServer server, UUID actorId) {
        PlayerActionFlow flow = FLOWS.get(server);
        return flow != null && flow.pending(actorId);
    }

    static boolean pendingProfile(MinecraftServer server, UUID profileId) {
        PlayerActionFlow flow = FLOWS.get(server);
        return flow != null && flow.pendingProfile(profileId);
    }

    static PlayerActionFlow flow(MinecraftServer server) {
        return FLOWS.computeIfAbsent(server, s -> new PlayerActionFlow(new ForgeEnv(s)));
    }

    /** Test seam: forget the flow (a "restart": the in-memory evidence is gone, the ledger is not). */
    static void forgetFlow(MinecraftServer server) {
        PlayerActionFlow flow = FLOWS.remove(server);
        if (flow != null) flow.shutdown();
    }

    /** True when a preview of this requester/NPC is waiting here (the Manager routes Confirm to the right flow). */
    static boolean hasPreview(MinecraftServer server, UUID requester, String npc) {
        PlayerActionFlow flow = FLOWS.get(server);
        return flow != null && flow.hasPreview(requester, npc);
    }

    static CompletableFuture<NativeEffectFlow.Result> preview(MinecraftServer server, UUID requester, String npc, PlayerActionFlow.Action action) {
        try {
            ServerThread.require(server, "player action");
            return CompletableFuture.completedFuture(flow(server).preview(requester, npc, action));
        } catch (RuntimeException e) {
            return failed();
        }
    }

    static CompletableFuture<NativeEffectFlow.Result> confirm(MinecraftServer server, UUID requester, String npc, String nonce) {
        try {
            ServerThread.require(server, "player action");
            return flow(server).confirm(requester, npc, nonce);
        } catch (RuntimeException e) {
            return failed();
        }
    }

    static NativeEffectFlow.Result cancel(MinecraftServer server, UUID requester, String npc) {
        try {
            ServerThread.require(server, "player action");
            return flow(server).cancel(requester, npc);
        } catch (RuntimeException e) {
            return new NativeEffectFlow.Result(NativeEffectFlow.Kind.ERROR, "Player action unavailable; nothing was written.", "");
        }
    }

    static CompletableFuture<NativeEffectFlow.Result> reconcile(MinecraftServer server, UUID requester, String npc) {
        try {
            ServerThread.require(server, "player action");
            return flow(server).reconcile(requester, npc);
        } catch (RuntimeException e) {
            return failed();
        }
    }

    private static CompletableFuture<NativeEffectFlow.Result> failed() {
        return CompletableFuture.completedFuture(new NativeEffectFlow.Result(NativeEffectFlow.Kind.ERROR, "Player action unavailable; nothing was written.", ""));
    }

    static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        PlayerActionFlow flow = FLOWS.get(event.getServer());
        if (flow != null) flow.tick();
    }

    static void onServerStopping(ServerStoppingEvent event) {
        PlayerActionFlow flow = FLOWS.remove(event.getServer());
        if (flow != null) flow.shutdown();
    }

    // ------------------------------------------------------------------------------------------------ env
    private static final class ForgeEnv implements PlayerActionFlow.Env {
        private final MinecraftServer server;
        private final NativeEffectFlow.Env base;

        ForgeEnv(MinecraftServer server) { this.server = server; this.base = NativeEffectRuntime.env(server); }

        @Override public void onServerThread(Runnable task) { base.onServerThread(task); }

        @Override public CompletableFuture<JsonObject> post(String route, JsonObject body, boolean critical) { return base.post(route, body, critical); }

        @Override public NativeEffectFlow.Actor resolve(String npcName) { return base.resolve(npcName); }

        @Override public String ownershipBlock(NativeEffectFlow.Actor a) {
            if (NativeEffectRuntime.nativeFlowPending(server, a.actorId())) return "native break/pickup action active";
            return NativeEffectRuntime.ownershipBlockBase(server, a);
        }

        @Override public NativeActionPolicy policy(boolean fresh) { return base.policy(fresh); }

        @Override public boolean online(UUID player) { return base.online(player); }

        @Override public long tick() { return base.tick(); }

        @Override public UUID newId() { return base.newId(); }

        @Override public void notifyRequester(UUID requester, String text) { base.notifyRequester(requester, text); }

        @Override public boolean bound(UUID session) { return NativeEffectRuntime.flow(server).bound(session); }

        @Override public CompletableFuture<String> ensureBound(NativeEffectFlow.Actor actor) { return NativeEffectRuntime.flow(server).ensureBound(actor); }

        private C1PlayerAvatar avatar(NativeEffectFlow.Actor a) { return C1P0Runtime.activeAvatar(server, a.name()); }

        private ServerLevel level(String dimension) {
            ResourceLocation id = ResourceLocation.tryParse(dimension);
            return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        }

        private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }

        private static String itemId(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }

        private static String sha(BlockState state) { return WorldActionPolicy.sha(state.toString()); }

        private static String blockOf(ItemStack stack) {
            return stack.getItem() instanceof BlockItem bi ? BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString() : null;
        }

        // ---- tool / hotbar selection + target resolution (server-owned) ----
        @Override public NativeEffectFlow.Pick<PlayerActionFlow.Target> pick(PlayerActionFlow.Action action, UUID requester, NativeEffectFlow.Actor actor) {
            C1PlayerAvatar av = avatar(actor);
            if (av == null) return NativeEffectFlow.Pick.no("The NPC is not live.");
            java.util.function.Function<NativeEffectFlow.Actor, Look> forced = lookOverride;       // GameTest seam only
            Look look = forced != null ? forced.apply(actor) : null;
            if (look == null) look = PINS.get(actor.actorId());
            final ServerLevel level;
            final BlockPos hitPos;
            final Direction face;
            if (look != null) {
                level = av.serverLevel();
                hitPos = look.pos();
                face = look.face();
            } else if (forced != null) {
                return NativeEffectFlow.Pick.no("Nothing to look at.");
            } else {
                ServerPlayer p = server.getPlayerList().getPlayer(requester);
                if (p == null) return NativeEffectFlow.Pick.no("The requester is not online.");
                HitResult hit = p.pick(8.0, 1.0f, false);
                if (hit.getType() != HitResult.Type.BLOCK) return NativeEffectFlow.Pick.no("Look at a block (within 8 blocks), then press " + (action == PlayerActionFlow.Action.PLACE ? "Place" : "Use") + ".");
                level = p.serverLevel();
                if (!level.dimension().location().toString().equals(actor.dimension())) return NativeEffectFlow.Pick.no("You and the NPC are in different dimensions.");
                hitPos = ((BlockHitResult) hit).getBlockPos();
                face = ((BlockHitResult) hit).getDirection();
            }
            if (!level.hasChunkAt(hitPos)) return NativeEffectFlow.Pick.no("The target chunk is not loaded.");
            if (action == PlayerActionFlow.Action.USE) {
                BlockState state = level.getBlockState(hitPos);
                String blockId = id(state);
                PlayerActionFlow.Target t = new PlayerActionFlow.Target(actor.dimension(), hitPos.getX(), hitPos.getY(), hitPos.getZ(), blockId, sha(state),
                        hitPos.getX(), hitPos.getY(), hitPos.getZ(), face.getName(), "", 0, -1);
                return new NativeEffectFlow.Pick<>(t, "", "use " + blockId + " at " + hitPos.toShortString() + " (face " + face.getName() + ").");
            }
            BlockPos cell = hitPos.relative(face);
            if (!level.hasChunkAt(cell)) return NativeEffectFlow.Pick.no("The target chunk is not loaded.");
            Inventory inv = av.getInventory();
            NativeActionPolicy policy = base.policy(true);
            int chosen = -1, fallback = -1;
            String hint = HINTS.get(actor.actorId());                         // "place cobblestone": a selector only, never authority
            for (int i = 0; i < 9; i++) {                                    // the selected slot first, then the rest of the hotbar
                int slot = (inv.selected + i) % 9;
                ItemStack stack = inv.items.get(slot);
                String blockId = blockOf(stack);
                if (blockId == null || stack.isEmpty()) continue;
                if (hint != null && !hint.equals(blockId)) continue;
                if (fallback < 0) fallback = slot;
                if (policy.decidePlace(requester, actor.dimension(), actor.name(), blockId, cell.getX(), cell.getY(), cell.getZ(),
                        hitPos.getX(), hitPos.getY(), hitPos.getZ()).allow()) { chosen = slot; break; }
            }
            if (chosen < 0) chosen = fallback;                               // let the flow report the policy refusal for the preferred block
            if (chosen < 0) return NativeEffectFlow.Pick.no(hint != null ? "The NPC holds no " + hint + " in its hotbar." : "The NPC holds no placeable block in its hotbar.");
            ItemStack stack = inv.items.get(chosen);
            String blockId = blockOf(stack);
            PlayerActionFlow.Target t = new PlayerActionFlow.Target(actor.dimension(), cell.getX(), cell.getY(), cell.getZ(), blockId,
                    sha(level.getBlockState(cell)), hitPos.getX(), hitPos.getY(), hitPos.getZ(), face.getName(), itemId(stack), stack.getCount(), chosen);
            return new NativeEffectFlow.Pick<>(t, "", "place " + blockId + " at " + cell.toShortString() + " against the " + face.getName() + " face of "
                    + hitPos.toShortString() + " (hotbar slot " + (chosen + 1) + (chosen != inv.selected ? ", the NPC selects it first" : "") + ", " + stack.getCount() + " in the stack).");
        }

        // ---- the facade: validation of everything vanilla useItemOn does NOT check ----
        @Override public String check(NativeEffectFlow.Actor actor, PlayerActionFlow.Action action, PlayerActionFlow.Target t) {
            C1PlayerAvatar av = avatar(actor);
            if (av == null || av.isRemoved()) return "the NPC is not live";
            if (!av.gameMode.isSurvival()) return "the NPC is not in survival mode";
            ServerLevel level = av.serverLevel();
            if (!level.dimension().location().toString().equals(t.dimension())) return "the NPC is in another dimension";
            BlockPos cell = new BlockPos(t.x(), t.y(), t.z());
            BlockPos support = new BlockPos(t.sx(), t.sy(), t.sz());
            if (!level.hasChunkAt(cell) || !level.hasChunkAt(support)) return "the chunk is not loaded";
            if (!level.mayInteract(av, cell) || !level.mayInteract(av, support)) return "the position is protected (spawn protection or world border)";
            Direction face = Direction.byName(t.face());
            if (face == null) return "unknown face";
            if (!av.canReach(support, 0.5)) return "out of reach: stand next to the block";
            Vec3 eye = av.getEyePosition();
            BlockHitResult clip = level.clip(new ClipContext(eye, Vec3.atCenterOf(support), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, av));
            if (clip.getType() != HitResult.Type.MISS && !clip.getBlockPos().equals(support))
                return "no line of sight to the block (" + level.getBlockState(clip.getBlockPos()).getBlock() + " at " + clip.getBlockPos().toShortString() + " is in the way)";
            BlockState supportState = level.getBlockState(support);
            if (supportState.isAir() || !supportState.getFluidState().isEmpty()) return "the clicked block is air or a fluid";
            if (action == PlayerActionFlow.Action.USE) {
                if (!NativeActionPolicy.useCeilingAllows(id(supportState))) return "that block is not a lever or a button";
                return supportState.hasBlockEntity() ? "the block has a block entity (not supported)" : null;
            }
            if (!support.relative(face).equals(cell)) return "the target cell is not the clicked face";
            BlockState cellState = level.getBlockState(cell);
            if (!(cellState.isAir() || cellState.canBeReplaced()) || !cellState.getFluidState().isEmpty()) return "the target cell is occupied";
            if (t.slot() < 0 || t.slot() > 8) return "no hotbar slot";
            ItemStack stack = av.getInventory().items.get(t.slot());
            if (stack.isEmpty() || !itemId(stack).equals(t.itemKey()) || !(stack.getItem() instanceof BlockItem bi)) return "the hotbar item changed";
            String blockId = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
            if (!blockId.equals(t.blockId())) return "the hotbar item no longer places that block";
            if (NativeActionPolicy.placeCeilingDenies(blockId)) return "that block is refused by the built-in safety ceiling";
            BlockState placed = bi.getBlock().defaultBlockState();
            if (placed.hasBlockEntity()) return "the block has a block entity (not supported in this milestone)";
            if (!level.isUnobstructed(placed, cell, CollisionContext.of(av))) return "an entity is in the way";
            return null;
        }

        @Override public String state(PlayerActionFlow.Target t) {
            ServerLevel level = level(t.dimension());
            BlockPos pos = new BlockPos(t.x(), t.y(), t.z());
            return level == null || !level.hasChunkAt(pos) ? null : sha(level.getBlockState(pos));
        }

        @Override public PlayerActionFlow.Observed observe(PlayerActionFlow.Target t) {
            ServerLevel level = level(t.dimension());
            BlockPos pos = new BlockPos(t.x(), t.y(), t.z());
            if (level == null || !level.hasChunkAt(pos)) return null;
            BlockState s = level.getBlockState(pos);
            return new PlayerActionFlow.Observed(id(s), sha(s));
        }

        // ---- journal (accepted C1 contract: commit() unchanged, REPLAY first, no rollback after an effect) ----
        @Override public String journalPrecheck(NativeEffectFlow.Actor actor, UUID attempt, String fingerprint) {
            C1InventoryData data = C1InventoryData.get(server);
            if (data.blockage(actor.profileId()) != null) return "the inventory SavedData is blocked";
            C1InventoryData.Record record = data.find(actor.profileId());
            if (record == null) return null;
            C1InventoryJournal.Lookup lookup = record.journal.lookup(attempt, fingerprint);
            if (lookup != C1InventoryJournal.Lookup.NEW) return "journal lookup is " + lookup;
            if (record.journal.size() >= C1InventoryJournal.MAX_RECEIPTS) return "the journal holds " + C1InventoryJournal.MAX_RECEIPTS + " receipts (migration required)";
            return null;
        }

        private NativeEffectFlow.Journal commit(C1PlayerAvatar av, UUID attempt, String fingerprint, String detail) {
            try {
                RuntimeException injected = NativeEffectRuntime.journalFault;                    // GameTest seam only
                if (injected != null) throw injected;
                C1InventoryData.get(server).commit(av.getUUID(), av.getInventory(), attempt, fingerprint, detail);
                return NativeEffectFlow.Journal.COMMITTED;
            } catch (RuntimeException e) {
                LOG.error("NPCortex player action: C1 journal commit failed AFTER the effect (no rollback; HOLD)", e);
                return NativeEffectFlow.Journal.FAILED;
            }
        }

        // ---- the native call ----
        @Override public PlayerActionFlow.Outcome execute(PlayerActionFlow.Started marker, NativeEffectFlow.Actor actor, PlayerActionFlow.Action action,
                                                          PlayerActionFlow.Target t, UUID attempt) {
            if (marker == null) throw new IllegalStateException("no durable EXECUTION_STARTED marker");
            C1PlayerAvatar av = avatar(actor);
            ServerLevel level = av.serverLevel();
            BlockPos cell = new BlockPos(t.x(), t.y(), t.z());
            BlockPos support = new BlockPos(t.sx(), t.sy(), t.sz());
            Direction face = Direction.byName(t.face());
            Vec3 hitVec = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
            Inventory inv = av.getInventory();
            boolean sneakBefore = av.isShiftKeyDown();
            int selectedBefore = inv.selected;
            String beforeId = id(level.getBlockState(cell));
            String stateBefore = sha(level.getBlockState(cell));
            ListTag invBefore = inv.save(new ListTag());
            int itemBefore = 0;
            boolean threw = false;
            InteractionResult result = InteractionResult.PASS;
            try {
                av.lookAt(EntityAnchorArgument.Anchor.EYES, hitVec);                          // face the target before touching it
                if (action == PlayerActionFlow.Action.PLACE) {
                    inv.selected = t.slot();                                                  // tool selection: a real hotbar selection
                    ItemStack stack = inv.getSelected();
                    itemBefore = stack.getCount();
                    av.setShiftKeyDown(true);                                                 // never activate a clickable support block
                    result = av.gameMode.useItemOn(av, level, stack, InteractionHand.MAIN_HAND, hit);
                } else {
                    av.setShiftKeyDown(false);
                    result = av.gameMode.useItemOn(av, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
                }
                av.swing(InteractionHand.MAIN_HAND, true);
            } catch (Throwable thrown) {
                threw = true;
                LOG.error("NPCortex player action threw for attempt {}: the world may have changed", attempt, thrown);
            } finally {
                av.setShiftKeyDown(sneakBefore);
            }
            String afterId = id(level.getBlockState(cell));
            String stateAfter = sha(level.getBlockState(cell));
            String interaction = result.consumesAction() ? (result == InteractionResult.SUCCESS ? "SUCCESS" : "CONSUME") : result == InteractionResult.FAIL ? "FAIL" : "PASS";
            int itemAfter = action == PlayerActionFlow.Action.PLACE ? inv.items.get(t.slot()).getCount() : 0;
            if (action == PlayerActionFlow.Action.PLACE && !inv.items.get(t.slot()).isEmpty() && !itemId(inv.items.get(t.slot())).equals(t.itemKey())) itemAfter = 0;
            NativeEffectFlow.Journal journal = NativeEffectFlow.Journal.NOT_REQUIRED;
            if (!threw) {
                try {
                    boolean consumed = action == PlayerActionFlow.Action.PLACE && itemBefore - itemAfter == 1 && interaction.matches("SUCCESS|CONSUME");
                    if (action == PlayerActionFlow.Action.PLACE && !consumed) inv.selected = selectedBefore;     // nothing happened: put the selection back
                    ListTag invAfter = inv.save(new ListTag());
                    boolean changed = !invBefore.equals(invAfter) || inv.selected != selectedBefore;
                    if (changed) journal = commit(av, attempt, action.fingerprintPrefix + attempt,
                            action == PlayerActionFlow.Action.PLACE ? "place " + t.blockId() + " at " + cell.toShortString() + " (slot " + t.slot() + ")" : "use " + t.blockId());
                } catch (RuntimeException e) {
                    journal = NativeEffectFlow.Journal.FAILED;
                    LOG.error("NPCortex player action: inventory snapshot failed", e);
                }
            }
            return new PlayerActionFlow.Outcome(threw, interaction, beforeId, afterId, stateBefore, stateAfter, itemBefore, itemAfter, t.slot() < 0 ? 0 : t.slot(), journal);
        }
    }
}
