package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/** PAP-G3 deterministic approach + PAP-G5 skill chaining + the in-game natural-language path ("Reika, place cobblestone").
 *
 * AI proposal -> authoritative validation -> target preview -> Owner confirmation -> durable execution -> verified result:
 *  - the chat text is mapped by {@link PlayerIntent} to ONE verb (no coordinates, no authority, no model call);
 *  - the target is the block the requester looks at, grounded on the server; the requester must be on the Owner allow-list;
 *  - walking is the accepted C2 navigation ({@link C2AgentRuntime#guardedNavigate}: its planner, cancellation, terminal witness and
 *    recovery are reused unchanged); NO block or item claim is held while walking: claims are taken only by Confirm -> RESERVE,
 *    after the NPC arrived and the target was re-resolved and re-validated;
 *  - every world effect needs an explicit "confirm" from the requester, one confirmation per effect (a chain only automates the
 *    approach and the next PREVIEW; it never confirms for the Owner).
 * Chains: break -> (approach the drop) -> pickup preview. Outcomes come from the flows' typed receipts, never invented here.
 */
final class PlayerTaskRuntime {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    static final int APPROACH_DEADLINE_TICKS = 900;
    static final int REACH_BLOCKS = 4;              // eye distance used to decide "already close enough"; the facade re-checks vanilla reach
    private static final Pattern ADDRESS = Pattern.compile("^\\s*@?([A-Za-z0-9_]{1,16})\\s*[,:]\\s*(.{1,120})$");

    private enum Phase { APPROACHING, RETRY_PREVIEW, AWAITING_CONFIRM, EXECUTING }

    private static final class Task {
        final UUID owner, actorId;
        final String npc;
        PlayerIntent.Intent intent;
        final PlayerActionRuntime.Look look;
        Phase phase = Phase.APPROACHING;
        UUID goal;
        long deadline, expires, nextTry;
        String nonce = "";
        boolean pap;
        boolean collected;                         // the break -> pickup chain already moved on to the pickup
        BlockPos dropAt;

        Task(UUID owner, UUID actorId, String npc, PlayerIntent.Intent intent, PlayerActionRuntime.Look look) {
            this.owner = owner; this.actorId = actorId; this.npc = npc; this.intent = intent; this.look = look;
        }
    }

    private static final Map<MinecraftServer, Map<UUID, Task>> TASKS = new IdentityHashMap<>();
    private static final Map<UUID, BlockPos> BREAK_PINS = new ConcurrentHashMap<>();
    /** Test seam: every text sent to the requester is also given here. */
    static volatile Consumer<String> messageSink;

    private PlayerTaskRuntime() {}

    static BlockPos pinnedBlock(UUID actorId) { return BREAK_PINS.get(actorId); }

    static void clearForTest(MinecraftServer server) {
        Map<UUID, Task> tasks = TASKS.remove(server);
        if (tasks != null) for (Task t : tasks.values()) if (t.goal != null) C2AgentRuntime.cancelGuardedGoal(server, t.npc, t.goal);
        BREAK_PINS.clear();
        messageSink = null;
    }

    static boolean hasTask(MinecraftServer server, UUID actorId) { return tasks(server).containsKey(actorId); }

    private static Map<UUID, Task> tasks(MinecraftServer server) { return TASKS.computeIfAbsent(server, s -> new HashMap<>()); }

    private static void msg(MinecraftServer server, UUID owner, String text) {
        Consumer<String> sink = messageSink;
        if (sink != null) sink.accept(text);
        NativeEffectRuntime.env(server).notifyRequester(owner, "[NPCortex] " + text);
    }

    // ------------------------------------------------------------------------------------------------ chat
    static void onChat(ServerChatEvent event) {
        ServerPlayer p = event.getPlayer();
        Matcher m = ADDRESS.matcher(event.getRawText());
        if (p == null || p.getServer() == null || !m.matches()) return;
        handleChat(p.getServer(), p.getUUID(), m.group(1), m.group(2), () -> lookOf(p));          // the chat line itself is left alone
    }

    private static PlayerActionRuntime.Look lookOf(ServerPlayer p) {
        HitResult hit = p.pick(8.0, 1.0f, false);
        return hit.getType() == HitResult.Type.BLOCK ? new PlayerActionRuntime.Look(((BlockHitResult) hit).getBlockPos(), ((BlockHitResult) hit).getDirection()) : null;
    }

    /** Server thread. Returns true when the text was an NPC task intent (handled here), false for ordinary conversation. */
    static boolean handleChat(MinecraftServer server, UUID requester, String npcName, String text, Supplier<PlayerActionRuntime.Look> look) {
        PlayerIntent.Intent intent = PlayerIntent.parse(text);
        C1IdentityData.Entry id = C1IdentityData.get(server).find(npcName);
        if (intent.kind() == PlayerIntent.Kind.UNKNOWN || id == null) return false;
        try {
            ServerThread.require(server, "player task");
            C1PlayerAvatar av = C1P0Runtime.activeAvatar(server, id.character());
            if (av == null || av.isRemoved() || !av.isAlive()) { msg(server, requester, id.character() + " is not live."); return true; }
            NativeActionPolicy policy = NativeEffectRuntime.env(server).policy(true);
            if (!policy.allowsRequester(requester, id.dimension(), id.character())) {
                msg(server, requester, "Denied: you are not on the Owner allow-list for " + id.character() + ".");
                return true;
            }
            Map<UUID, Task> tasks = tasks(server);
            Task current = tasks.get(id.actorId());
            switch (intent.kind()) {
                case CANCEL -> cancel(server, requester, id, current);
                case CONFIRM -> confirm(server, requester, id, current);
                default -> {
                    if (current != null) { msg(server, requester, id.character() + " is busy with a task; say \"cancel\" first."); return true; }
                    if (NativeEffectRuntime.pending(server, id.actorId())) { msg(server, requester, id.character() + " has an unresolved world action; use Rec. in the Manager."); return true; }
                    start(server, requester, id, av, intent, look.get());
                }
            }
        } catch (RuntimeException e) {
            LOG.error("NPCortex player task failed", e);
            msg(server, requester, "Internal error; nothing was changed.");
        }
        return true;
    }

    private static void cancel(MinecraftServer server, UUID owner, C1IdentityData.Entry id, Task t) {
        if (t != null) {
            if (t.goal != null && C2AgentRuntime.hasActiveActor(t.actorId)) C2AgentRuntime.cancelGuardedGoal(server, id.character(), t.goal);
            tasks(server).remove(t.actorId);
            BREAK_PINS.remove(t.actorId);
            PlayerActionRuntime.unpin(t.actorId);
        }
        NativeEffectFlow.Result a = PlayerActionRuntime.cancel(server, owner, id.character());
        NativeEffectFlow.Result b = NativeEffectRuntime.cancel(server, owner, id.character()).join();
        boolean did = t != null || a.kind() == NativeEffectFlow.Kind.OK || b.kind() == NativeEffectFlow.Kind.OK;
        msg(server, owner, did ? "Cancelled. Nothing that already ran is undone; check the reported outcome." : "Nothing to cancel.");
    }

    private static void confirm(MinecraftServer server, UUID owner, C1IdentityData.Entry id, Task t) {
        if (t == null || t.phase != Phase.AWAITING_CONFIRM || !t.owner.equals(owner)) { msg(server, owner, "Nothing to confirm."); return; }
        t.phase = Phase.EXECUTING;
        (t.pap ? PlayerActionRuntime.confirm(server, owner, id.character(), t.nonce) : NativeEffectRuntime.confirm(server, owner, id.character(), t.nonce))
                .thenAccept(r -> server.execute(() -> {
                    msg(server, owner, r.message());
                    if (r.kind() != NativeEffectFlow.Kind.OK) tasks(server).remove(t.actorId, t);
                }));
    }

    // ------------------------------------------------------------------------------------------------ start + approach
    private static void start(MinecraftServer server, UUID owner, C1IdentityData.Entry id, C1PlayerAvatar av, PlayerIntent.Intent intent, PlayerActionRuntime.Look look) {
        if (intent.kind() == PlayerIntent.Kind.PICKUP) { startPickup(server, owner, id, av, intent); return; }
        if (look == null) { msg(server, owner, "Look at a block (within 8 blocks) and say it again."); return; }
        Task t = new Task(owner, id.actorId(), id.character(), intent, look);
        t.deadline = server.overworld().getGameTime() + APPROACH_DEADLINE_TICKS;
        tasks(server).put(id.actorId(), t);
        if (reachable(av, Vec3.atCenterOf(look.pos()))) afterArrival(server, t, av);
        else approach(server, t, av, look.pos(), look.pos());
    }

    private static void startPickup(MinecraftServer server, UUID owner, C1IdentityData.Entry id, C1PlayerAvatar av, PlayerIntent.Intent intent) {
        ItemEntity best = null;
        for (ItemEntity ie : av.serverLevel().getEntitiesOfClass(ItemEntity.class, av.getBoundingBox().inflate(12.0)))
            if (!ie.isRemoved() && ie.isAlive() && (best == null || ie.distanceToSqr(av) < best.distanceToSqr(av))) best = ie;
        if (best == null) { msg(server, owner, "No item nearby to pick up."); return; }
        Task t = new Task(owner, id.actorId(), id.character(), intent, null);
        t.dropAt = best.blockPosition();
        t.deadline = server.overworld().getGameTime() + APPROACH_DEADLINE_TICKS;
        tasks(server).put(id.actorId(), t);
        if (av.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(best.getBoundingBox())) afterArrival(server, t, av);
        else approach(server, t, av, t.dropAt, null);
    }

    private static boolean reachable(C1PlayerAvatar av, Vec3 center) { return av.getEyePosition().distanceTo(center) <= REACH_BLOCKS - 0.4; }

    private static void approach(MinecraftServer server, Task t, C1PlayerAvatar av, BlockPos target, BlockPos exclude) {
        ServerLevel level = av.serverLevel();
        BlockPos stand = t.intent.kind() == PlayerIntent.Kind.PICKUP ? target : chooseStand(level, av, target, exclude, t.look == null ? null : t.look.pos().relative(t.look.face()));
        if (stand == null) {
            msg(server, t.owner, "No standing position next to that target (blocked or unreachable); nothing was started.");
            tasks(server).remove(t.actorId);
            return;
        }
        C2AgentRuntime.NavigationAdmission adm = C2AgentRuntime.guardedNavigate(server, t.npc, stand.getX(), stand.getY(), stand.getZ());
        if (!adm.accepted()) {
            msg(server, t.owner, "Cannot walk there: " + adm.detail() + " (nothing was started).");
            tasks(server).remove(t.actorId);
            return;
        }
        t.goal = adm.goalId();
        t.phase = Phase.APPROACHING;
        t.deadline = server.overworld().getGameTime() + APPROACH_DEADLINE_TICKS;
        msg(server, t.owner, t.npc + " walks to " + stand.toShortString() + " first. No block or item is claimed while walking; the target is re-checked on arrival.");
    }

    /** A standing cell near the target: solid floor, two free blocks, 1..3 blocks away, close enough for vanilla reach; nearest to the NPC. */
    static BlockPos chooseStand(ServerLevel level, C1PlayerAvatar av, BlockPos target, BlockPos excludeA, BlockPos excludeB) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 center = Vec3.atCenterOf(target);
        for (int dy = -1; dy <= 1; dy++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos feet = target.offset(dx, dy, dz);
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat < 1.0 || flat > 3.0 || feet.equals(excludeA) || feet.equals(excludeB) || feet.above().equals(excludeA) || feet.above().equals(excludeB)) continue;
            if (!level.hasChunkAt(feet)) continue;
            BlockState floor = level.getBlockState(feet.below());
            if (!floor.isFaceSturdy(level, feet.below(), Direction.UP)) continue;
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
            if (!level.getBlockState(feet).getFluidState().isEmpty()) continue;
            Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
            if (eye.distanceTo(center) > REACH_BLOCKS - 0.6) continue;
            double d = av.position().distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            if (d < bestDist) { bestDist = d; best = feet; }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ tick
    static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        Map<UUID, Task> tasks = TASKS.get(server);
        if (tasks == null || tasks.isEmpty()) return;
        long now = server.overworld().getGameTime();
        for (Task t : new ArrayList<>(tasks.values())) {
            try {
                if (!NativeEffectRuntime.env(server).online(t.owner)) { dropTask(server, t, "The requester left; the task was dropped."); continue; }
                switch (t.phase) {
                    case APPROACHING -> {
                        if (!C2AgentRuntime.hasActiveActor(t.actorId)) arrived(server, t, now);
                        else if (now > t.deadline) {
                            C2AgentRuntime.cancelGuardedGoal(server, t.npc, t.goal);
                            dropTask(server, t, "The walk took too long and was cancelled; nothing was started.");
                        }
                    }
                    case RETRY_PREVIEW -> { if (now >= t.nextTry) arrived(server, t, now); }
                    case AWAITING_CONFIRM -> { if (now > t.expires) dropTask(server, t, "The preview expired; nothing was run."); }
                    case EXECUTING -> executing(server, t);
                }
            } catch (RuntimeException e) {
                LOG.error("NPCortex player task tick failed", e);
                dropTask(server, t, "Internal error; the task was dropped.");
            }
        }
    }

    private static void dropTask(MinecraftServer server, Task t, String text) {
        tasks(server).remove(t.actorId, t);
        BREAK_PINS.remove(t.actorId);
        PlayerActionRuntime.unpin(t.actorId);
        msg(server, t.owner, text);
    }

    private static void arrived(MinecraftServer server, Task t, long now) {
        C1PlayerAvatar av = C1P0Runtime.activeAvatar(server, t.npc);
        if (av == null || av.isRemoved() || !av.isAlive()) { dropTask(server, t, t.npc + " is no longer live; the task was dropped."); return; }
        afterArrival(server, t, av);
    }

    /** The NPC is close: re-resolve the target from scratch (Preview), NOW. Nothing was claimed before this point. */
    private static void afterArrival(MinecraftServer server, Task t, C1PlayerAvatar av) {
        long now = server.overworld().getGameTime();
        NativeEffectFlow.Result r;
        switch (t.intent.kind()) {
            case PLACE, USE -> {
                PlayerActionRuntime.pin(t.actorId, t.look, t.intent.blockHint());
                try {
                    r = PlayerActionRuntime.preview(server, t.owner, t.npc, t.intent.kind() == PlayerIntent.Kind.PLACE ? PlayerActionFlow.Action.PLACE : PlayerActionFlow.Action.USE).join();
                } finally {
                    PlayerActionRuntime.unpin(t.actorId);
                }
                t.pap = true;
            }
            case BREAK -> {
                BREAK_PINS.put(t.actorId, t.look.pos());
                try {
                    r = NativeEffectRuntime.preview(server, t.owner, t.npc, NativeEffectFlow.Action.BREAK).join();
                } finally {
                    BREAK_PINS.remove(t.actorId);
                }
                t.pap = false;
            }
            default -> {
                r = NativeEffectRuntime.preview(server, t.owner, t.npc, NativeEffectFlow.Action.PICKUP).join();
                t.pap = false;
            }
        }
        LOG.info("NPCortex player task: preview {} -> {} / {} (now {}, deadline {})", t.intent.kind(), r.kind(), r.message(), now, t.deadline);
        if (t.intent.kind() == PlayerIntent.Kind.PICKUP && r.kind() != NativeEffectFlow.Kind.OK && now % 50 < 10)
            for (ItemEntity ie : av.serverLevel().getEntitiesOfClass(ItemEntity.class, av.getBoundingBox().inflate(6.0))) {
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                ie.addAdditionalSaveData(tag);
                LOG.info("NPCortex player task: drop {} at {} delay={} alive={} zone={} thrower={} owner={} age={} npc={}", ie.getItem(), ie.position(),
                        ie.hasPickUpDelay(), ie.isAlive(), ie.getBoundingBox().intersects(av.getBoundingBox().inflate(1.0, 0.5, 1.0)),
                        tag.hasUUID("Thrower"), tag.hasUUID("Owner"), tag.getShort("Age"), av.position());
            }
        boolean dropNotReady = t.intent.kind() == PlayerIntent.Kind.PICKUP && r.kind() == NativeEffectFlow.Kind.REJECTED && r.message().contains("No pickable item");
        if ((r.kind() == NativeEffectFlow.Kind.BUSY || dropNotReady) && now < t.deadline + 100) {   // binding in progress or a fresh drop still has its pickup delay
            t.phase = Phase.RETRY_PREVIEW;
            t.nextTry = now + 10;
            return;
        }
        if (r.kind() != NativeEffectFlow.Kind.OK) {
            dropTask(server, t, "Cannot proceed: " + r.message());
            return;
        }
        t.nonce = r.nonce();
        t.phase = Phase.AWAITING_CONFIRM;
        t.expires = now + NativeEffectFlow.PREVIEW_TTL_TICKS;
        msg(server, t.owner, r.message() + " Say \"" + t.npc + ", confirm\" to run it or \"" + t.npc + ", cancel\".");
    }

    /** After Confirm: wait for the flows' typed terminal, then (break only) chain to the drop. */
    private static void executing(MinecraftServer server, Task t) {
        if (NativeEffectRuntime.pending(server, t.actorId)) return;
        if (t.intent.kind() == PlayerIntent.Kind.BREAK && !t.collected && t.look != null
                && server.overworld().getBlockState(t.look.pos()).isAir()) {
            t.collected = true;
            C1PlayerAvatar av = C1P0Runtime.activeAvatar(server, t.npc);
            ItemEntity drop = null;
            if (av != null) for (ItemEntity ie : av.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(t.look.pos()).inflate(4.0)))
                if (!ie.isRemoved() && (drop == null || ie.distanceToSqr(Vec3.atCenterOf(t.look.pos())) < drop.distanceToSqr(Vec3.atCenterOf(t.look.pos())))) drop = ie;
            if (drop != null) {
                Task next = new Task(t.owner, t.actorId, t.npc, new PlayerIntent.Intent(PlayerIntent.Kind.PICKUP, ""), null);
                next.dropAt = drop.blockPosition();
                next.collected = true;
                next.deadline = server.overworld().getGameTime();       // the chain only PREVIEWS: it retries for 5 s while the drop settles, never walks
                tasks(server).put(t.actorId, next);
                msg(server, t.owner, "Break verified. " + t.npc + " previews picking up the drop; you will be asked to confirm. If the drop landed out of reach say \"" + t.npc + ", pick up\" to send the NPC to it.");
                afterArrival(server, next, av);
                return;
            }
        }
        tasks(server).remove(t.actorId, t);
    }

    // ------------------------------------------------------------------------------------------------ lifecycle
    static void onServerStopping(ServerStoppingEvent event) {
        TASKS.remove(event.getServer());
        BREAK_PINS.clear();
    }
}
