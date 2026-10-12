package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

/** Persistent, bounded task state. Every state transition runs on the server game thread.
 *  Paused work is saved in the NPC's Forge persistent entity data. Never loads chunks.
 */
public final class GatherTaskController {
    private static final String TAG = "NPCortexGatherV081";
    private static final int POLL_TICKS = 10;
    private static final String SEARCH = "search";
    private static final String APPROACH = "approach";
    private static final String PICKUP = "pickup";
    private static final String RETURN = "return"; // legacy saved tasks only; never deliver automatically
    private static final String PAUSED = "paused";
    private static final String BUILD = "pillar_jump";
    private static final String CLEANUP = "pillar_cleanup";

    private GatherTaskController() {}

    private static CompoundTag data(CompanionEntity npc) {
        return npc.getPersistentData().getCompound(TAG);
    }
    public static boolean isBusy(CompanionEntity npc) {
        return npc.getPersistentData().contains(TAG);
    }
    private static void save(CompanionEntity npc, CompoundTag data) {
        npc.getPersistentData().put(TAG, data);
    }
    private static void clear(CompanionEntity npc) {
        npc.getPersistentData().remove(TAG);
        npc.getNavigation().stop();
    }
    public static String status(CompanionEntity npc) {
        if (!isBusy(npc)) return "[Task: idle.]";
        CompoundTag t = data(npc);
        return "[Task " + t.getString("state") + ": " + t.getString("item") + " "
                + t.getInt("collected") + "/" + t.getInt("goal")
                + " collected (" + t.getInt("pendingLogs") + " mined drops pending); "
                + "scaffold " + TreeScaffold.size(t) + "/" + ScaffoldPolicy.MAX_HEIGHT
                + (t.getBoolean("treeLocked") ? "; locked oak trunk at "
                        + t.getInt("treeX") + "," + t.getInt("treeZ") : "")
                + "; inventory retained by NPC (give/drop separately)"
                + (t.getString("reason").isBlank() ? "" : "; " + t.getString("reason")) + ".]";
    }
    public static String start(ServerPlayer owner, CompanionEntity npc, String item, int count) {
        if (owner == null || npc == null || !owner.getUUID().equals(npc.getOwnerUUID())
                || npc.level() != owner.level() || owner.distanceToSqr(npc) > 64.0D
                || !owner.isAlive() || !npc.isAlive()) return "[Task blocked: owner must be close to their NPC.]";
        if (!NPCortexConfig.WORLD_EDITS_ENABLED.get() || !owner.getServer().isSingleplayer()
                || !owner.hasPermissions(2)) return "[Task blocked: world edits require singleplayer, cheats and opt-in.]";
        if (!GatherTaskPolicy.valid(item, count)) return "[Task blocked: unsupported resource or count (1–16).]";
        if (isBusy(npc) || AgenticTaskController.isBusy(npc) || ActionPlanRunner.isBusy(npc))
            return "[Task busy: cancel the current task/plan first.]";
        CompoundTag t = new CompoundTag();
        t.putString("item", item);
        t.putInt("goal", count);
        t.putInt("collected", 0);
        t.putInt("pendingLogs", 0); // blocks broken but drops not yet collected
        t.putInt("leavesCleared", 0);
        t.putString("state", SEARCH);
        t.putInt("homeX", npc.blockPosition().getX());
        t.putInt("homeY", npc.blockPosition().getY());
        t.putInt("homeZ", npc.blockPosition().getZ());
        t.putBoolean("previousFollow", npc.isFollowing());
        t.putLong("phaseTick", npc.level().getGameTime());
        t.putString("reason", "");
        npc.setFollowing(false);
        save(npc, t);
        return "[Task started: gather " + count + " " + item
                + "; store in NPC bag. Delivery requires a separate give/drop request.]";
    }
    public static String cancel(CompanionEntity npc) {
        if (!isBusy(npc)) return "[Task: no active gather task.]";
        boolean follow = data(npc).getBoolean("previousFollow");
        CompoundTag t = data(npc);
        if (TreeScaffold.size(t) > 0) {
            t.putBoolean("cancelRequested", true);
            t.putString("cleanupAfter", "finish");
            change(npc, t, CLEANUP, npc.level().getGameTime());
            return "[Task cancelled; safely dismantling NPC-owned scaffold before restoring follow. "
                    + "Collected items stay in NPC bag.]";
        }
        clear(npc);
        npc.setFollowing(follow);
        return "[Task cancelled. Existing collected items remain in the NPC bag.]";
    }
    public static String resume(CompanionEntity npc) {
        if (!isBusy(npc)) return "[Task: no saved task to resume.]";
        CompoundTag t = data(npc);
        if (!PAUSED.equals(t.getString("state"))) return status(npc);
        String from = t.getString("pausedFrom");
        t.putString("state", CLEANUP.equals(from) || BUILD.equals(from) ? CLEANUP : SEARCH);
        t.putString("reason", "");
        t.putLong("phaseTick", npc.level().getGameTime());
        save(npc, t);
        return "[Task resumed: " + status(npc) + "]";
    }
    private static void change(CompanionEntity npc, CompoundTag t, String state, long now) {
        t.putString("state", state);
        t.putLong("phaseTick", now);
        t.putString("reason", "");
        save(npc, t);
    }
    private static void pause(ServerPlayer owner, CompanionEntity npc, CompoundTag t, String reason, long now) {
        npc.getNavigation().stop();
        t.putString("pausedFrom", t.getString("state"));
        t.putString("reason", reason);
        change(npc, t, PAUSED, now);
        t.putString("reason", reason);
        save(npc, t);
        owner.sendSystemMessage(Component.literal("NPCortex " + npc.getName().getString()
                + ": paused gathering — " + reason + ". Use /npcortex task resume."));
    }
    private static void notice(ServerPlayer owner, CompanionEntity npc, String message) {
        owner.sendSystemMessage(Component.literal("NPCortex " + npc.getName().getString() + ": " + message));
    }
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        boolean poll = level.getGameTime() % POLL_TICKS == 0;
        // Iterate loaded entities only; never force-load regions on behalf of an NPC.
        int visited = 0;
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof CompanionEntity npc) || !isBusy(npc)) continue;
            if (++visited > 16) break; // bounded scheduler work per dimension
            String state = data(npc).getString("state");
            if (!poll && !BUILD.equals(state) && !CLEANUP.equals(state)) continue;
            if (!npc.isAlive() || npc.isRemoved() || npc.getOwnerUUID() == null) continue;
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(npc.getOwnerUUID());
            if (owner == null || owner.level() != level || !owner.isAlive()
                    || !GatherTaskPolicy.ownerPresent(owner.distanceToSqr(npc))) {
                npc.getNavigation().stop(); // saved task can resume when owner comes back
                continue;
            }
            step(level, owner, npc);
        }
    }
    private static void step(ServerLevel level, ServerPlayer owner, CompanionEntity npc) {
        CompoundTag t = data(npc);
        String item = t.getString("item");
        int goal = t.getInt("goal");
        int count = t.getInt("collected");
        int pending = t.getInt("pendingLogs");
        if (!GatherTaskPolicy.valid(item, goal) || count < 0 || count > goal
                || pending < 0 || pending > goal) {
            clear(npc); return;
        }
        String state = t.getString("state");
        long now = level.getGameTime();
        if (PAUSED.equals(state)) return;
        if (!NPCortexConfig.WORLD_EDITS_ENABLED.get() || !owner.getServer().isSingleplayer()
                || !owner.hasPermissions(2)) {
            pause(owner, npc, t, "world edits disabled", now);
            return;
        }
        // Scaffold state persists in entity NBT. Never touch unrelated world blocks.
        if (BUILD.equals(state)) {
            String result = TreeScaffold.tickJump(level, owner, npc, t, now);
            if (TreeScaffold.DONE.equals(result)) change(npc, t, SEARCH, now);
            else if (result.startsWith(TreeScaffold.BLOCKED))
                pause(owner, npc, t, result.substring(TreeScaffold.BLOCKED.length()), now);
            else save(npc, t);
            return;
        }
        if (CLEANUP.equals(state)) {
            // Avoid trying to dismantle a scaffold while the NPC is still falling.
            String result = TreeScaffold.dismantle(level, npc, t);
            if (TreeScaffold.DONE.equals(result)) {
                t.remove("pillarX"); t.remove("pillarY"); t.remove("pillarZ");
                if ("finish".equals(t.getString("cleanupAfter"))) {
                    boolean follow = t.getBoolean("previousFollow");
                    boolean cancelled = t.getBoolean("cancelRequested");
                    clear(npc); npc.setFollowing(follow);
                    notice(owner, npc, cancelled ? "task cancelled; scaffold dismantled"
                            : "gather complete — " + count + "/" + goal + " " + item
                            + " stored in NPC bag; scaffold dismantled");
                } else {
                    if ("pickup".equals(t.getString("cleanupAfter"))) {
                        change(npc, t, PICKUP, now);
                    } else {
                        t.remove("treeLocked");
                        change(npc, t, SEARCH, now);
                    }
                }
            } else if (result.startsWith(TreeScaffold.BLOCKED)) {
                pause(owner, npc, t, result.substring(TreeScaffold.BLOCKED.length()), now);
            } else save(npc, t);
            return;
        }
        // Explicit two-stage inventory policy: gather NEVER pushes items to the player.
        // This also safely migrates v0.8.1 jobs persisted in the RETURN state.
        if (RETURN.equals(state) && count < goal) {
            change(npc, t, SEARCH, now); // legacy inconsistent progress: keep collecting
            state = SEARCH;
        }
        if (count >= goal) {
            if (TreeScaffold.size(t) > 0) {
                t.putString("cleanupAfter", "finish");
                change(npc, t, CLEANUP, now);
                return;
            }
            boolean follow = t.getBoolean("previousFollow");
            clear(npc);
            npc.setFollowing(follow);
            notice(owner, npc, "gather complete — " + count + "/" + goal + " " + item
                    + " stored in NPC inventory. Say 'give me all the logs' when ready.");
            return;
        }
        // Count mined-but-uncollected logs as pending work, not inventory items.
        // On a pillar, finish/cleanup safely BEFORE retrieving ground-level drops.
        if (count + pending >= goal && !PICKUP.equals(state)) {
            if (TreeScaffold.size(t) > 0) {
                t.putString("cleanupAfter", "pickup");
                change(npc, t, CLEANUP, now);
            } else change(npc, t, PICKUP, now);
            return;
        }
        if (PICKUP.equals(state)) {
            int received = WorldActions.pickupForGather(owner, npc, item,
                    GatherTaskPolicy.remaining(goal, count));
            if (received > 0) {
                count += received;
                t.putInt("collected", count);
                t.putInt("pendingLogs", Math.max(0, pending - received));
                change(npc, t, SEARCH, now);
                notice(owner, npc, "gathered " + count + "/" + goal + " " + item);
            } else if (TreeScaffold.size(t) > 0) {
                // Never navigate off a pillar chasing drops that fell below us.
                if (now - t.getLong("phaseTick") > 20) change(npc, t, SEARCH, now);
            } else if (now - t.getLong("phaseTick") > 80) {
                if (pending > 0) pause(owner, npc, t,
                        "waiting for " + pending + " mined drops; they were not collected", now);
                else change(npc, t, SEARCH, now);
            } else navigateToDrop(level, npc, item);
            return;
        }
        BlockPos home = new BlockPos(t.getInt("homeX"), t.getInt("homeY"), t.getInt("homeZ"));
        // Pick up existing matching drops from the same local task area, too.
        int picked = WorldActions.pickupForGather(owner, npc, item,
                GatherTaskPolicy.remaining(goal, count));
        if (picked > 0) {
            t.putInt("collected", count + picked);
            t.putInt("pendingLogs", Math.max(0, pending - picked));
            change(npc, t, SEARCH, now);
            notice(owner, npc, "gathered " + (count + picked) + "/" + goal + " " + item);
            return;
        }
        if (!WorldActions.canStoreGather(npc, item)) {
            pause(owner, npc, t, "NPC bag is full; free a slot before resuming", now);
            return;
        }
        if (!GatherTaskPolicy.withinHome(npc.position().distanceToSqr(Vec3.atCenterOf(home)))) {
            pause(owner, npc, t, "NPC moved outside home boundary", now);
            return;
        }
        // A single exact position is selected and revalidated before EACH edit.
        // The generic 'mine_nearby' command could otherwise mine a different block.
        // Never switch oak trees while ANY logs remain in the chosen 1x1 trunk.
        // If a tree was completely cut down, dismantle our temporary pillar first.
        if ("minecraft:oak_log".equals(item) && t.getBoolean("treeLocked")
                && !TreeScaffold.treeHasLogs(level, t)) {
            if (TreeScaffold.size(t) > 0) {
                t.putString("cleanupAfter", pending > 0 ? "pickup" : "search");
                change(npc, t, CLEANUP, now);
                return;
            }
            if (pending > 0) {
                change(npc, t, PICKUP, now);
                return;
            }
            t.remove("treeLocked");
            t.remove("pillarX"); t.remove("pillarY"); t.remove("pillarZ");
            save(npc, t);
        }
        Target target = locate(level, owner, npc, home, item, t);
        if (target == null) {
            if ("minecraft:oak_log".equals(item) && t.getBoolean("treeLocked")) {
                startClimbing(level, owner, npc, t, now);
            } else {
                pause(owner, npc, t, "no reachable/visible " + item + " in loaded area", now);
            }
            return;
        }
        if ("minecraft:oak_log".equals(item) && !t.getBoolean("treeLocked")) {
            lockTree(level, t, target.block());
            save(npc, t);
        }
        if (target.miningNow()) {
            String mined = WorldBlockActions.mineAt(owner, npc, item, target.block());
            if (mined.startsWith("[Action confirmed:")) {
                npc.getNavigation().stop();
                if ("minecraft:oak_log".equals(item))
                    t.putInt("pendingLogs", t.getInt("pendingLogs") + 1);
                change(npc, t, PICKUP, now);
                return;
            }
        }
        if (target.stand() != null) {
            if (SEARCH.equals(state) || npc.getNavigation().isDone()) {
                BlockPos stand = target.stand();
                boolean started = npc.getNavigation().moveTo(stand.getX() + 0.5D, stand.getY(),
                        stand.getZ() + 0.5D, 1.1D);
                if (!started && now - t.getLong("phaseTick") > GatherTaskPolicy.MAX_WAIT_TICKS) {
                    if (tryClearOccludingLeaf(owner, npc, t, target.block(), now)) return;
                    if ("minecraft:oak_log".equals(item) && t.getBoolean("treeLocked"))
                        startClimbing(level, owner, npc, t, now);
                    else pause(owner, npc, t, "no navigable route around tree/leaves", now);
                    return;
                }
                if (SEARCH.equals(state) && started) change(npc, t, APPROACH, now);
            }
        } else if (target.leaf() != null) {
            if (tryClearOccludingLeaf(owner, npc, t, target.block(), now)) return;
        }
        if (now - t.getLong("phaseTick") > GatherTaskPolicy.MAX_WAIT_TICKS * 3L) {
            if (tryClearOccludingLeaf(owner, npc, t, target.block(), now)) return;
            if ("minecraft:oak_log".equals(item) && t.getBoolean("treeLocked"))
                startClimbing(level, owner, npc, t, now);
            else pause(owner, npc, t, "target unreachable or navigation timed out", now);
        }
    }

    private static void lockTree(ServerLevel level, CompoundTag t, BlockPos log) {
        int base = log.getY();
        for (int y = log.getY() - 1; y >= Math.max(level.getMinBuildHeight(), log.getY() - 8); y--) {
            BlockPos below = new BlockPos(log.getX(), y, log.getZ());
            if (!level.isLoaded(below) || !level.getBlockState(below).is(net.minecraft.world.level.block.Blocks.OAK_LOG)) break;
            base = y;
        }
        t.putBoolean("treeLocked", true);
        t.putInt("treeX", log.getX());
        t.putInt("treeZ", log.getZ());
        t.putInt("treeBaseY", base);
    }

    private static void startClimbing(ServerLevel level, ServerPlayer owner,
            CompanionEntity npc, CompoundTag t, long now) {
        String result = TreeScaffold.ascend(level, owner, npc, t, now);
        if (TreeScaffold.DONE.equals(result)) change(npc, t, BUILD, now);
        else if (result.startsWith(TreeScaffold.BLOCKED))
            pause(owner, npc, t, result.substring(TreeScaffold.BLOCKED.length()), now);
        else save(npc, t);
    }

    /** Last-resort foliage step after checking visible log faces and safe stands. */
    private static boolean tryClearOccludingLeaf(ServerPlayer owner, CompanionEntity npc,
            CompoundTag t, BlockPos log, long now) {
        if (!"minecraft:oak_log".equals(t.getString("item"))) return false;
        int cleared = t.getInt("leavesCleared");
        if (cleared >= TreeHarvestPolicy.MAX_LEAF_CLEAR) return false;
        BlockPos leaf = WorldBlockActions.blockingNaturalOakLeaf(npc, log);
        if (leaf == null) return false;
        String status = WorldBlockActions.clearNaturalOakLeaf(owner, npc, leaf, log);
        if (!status.startsWith("[Action confirmed:")) return false;
        npc.getNavigation().stop();
        t.putInt("leavesCleared", cleared + 1);
        change(npc, t, SEARCH, now);
        return true;
    }

    /** A target includes the actual log, the safe standing point or one natural
     *  leaf that physically blocks the current ray. Never mine by item ID only. */
    private record Target(BlockPos block, BlockPos stand, BlockPos leaf, boolean miningNow) {}
    private record Stand(BlockPos pos, double travelSq) {}

    private static Target locate(ServerLevel level, ServerPlayer owner, CompanionEntity npc,
            BlockPos home, String item, CompoundTag task) {
        BlockPos center = npc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int scan = GatherTaskPolicy.SEARCH_RADIUS;
        boolean locked = "minecraft:oak_log".equals(item) && task.getBoolean("treeLocked");
        // No chunk loading. Sort a bounded candidate list by proximity first,
        // then consider which trunk face/height can actually be reached.
        if (!locked) for (int dx = -scan; dx <= scan; dx++)
            for (int dy = -2; dy <= 6; dy++)
                for (int dz = -scan; dz <= scan; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (locked && !ScaffoldPolicy.sameTrunk(pos.getX(), pos.getZ(),
                            task.getInt("treeX"), task.getInt("treeZ"))) continue;
                    if (!level.isLoaded(pos) || pos.equals(center.below())
                            || !GatherTaskPolicy.withinHome(Vec3.atCenterOf(pos).distanceToSqr(
                                    Vec3.atCenterOf(home)))) continue;
                    BlockState block = level.getBlockState(pos);
                    if (level.getBlockEntity(pos) == null &&
                            BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString().equals(item))
                        candidates.add(pos.immutable());
                }
        if (locked) {
            candidates.clear();
            for (int y = task.getInt("treeBaseY") - 1;
                    y <= task.getInt("treeBaseY") + ScaffoldPolicy.MAX_TREE_HEIGHT; y++) {
                BlockPos p = new BlockPos(task.getInt("treeX"), y, task.getInt("treeZ"));
                if (level.isLoaded(p) && level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.OAK_LOG))
                    candidates.add(p);
            }
        }
        // Avoid expensive raycasts/path checks for every block in a whole forest.
        candidates.sort(Comparator.comparingDouble(p -> npc.distanceToSqr(Vec3.atCenterOf(p))));
        int considered = 0;
        Target best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        boolean oakTree = "minecraft:oak_log".equals(item);
        double reachSq = InteractionReach.squared(owner.isCreative());
        for (BlockPos pos : candidates) {
            if (++considered > TreeHarvestPolicy.MAX_CANDIDATES) break;
            double distanceSq = npc.distanceToSqr(Vec3.atCenterOf(pos));
            boolean nowReach = npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reachSq;
            boolean nowVisible = nowReach && WorldBlockActions.visible(npc, pos);
            Stand stand = nowVisible ? null : findVisibleStand(level, npc, pos, reachSq);
            BlockPos leaf = oakTree && !nowVisible && stand == null && nowReach
                    ? WorldBlockActions.blockingNaturalOakLeaf(npc, pos) : null;
            if (!nowVisible && stand == null && leaf == null) continue;
            // Position-independent tier prevents leaf destruction when walking
            // around the trunk gives a clean line of sight.
            double travel = stand != null ? stand.travelSq() : distanceSq;
            double score = TreeHarvestPolicy.priority(nowVisible, stand != null, leaf != null,
                    pos.getY(), npc.blockPosition().getY(), travel);
            if (score < bestScore) {
                bestScore = score;
                best = new Target(pos, stand == null ? null : stand.pos(), leaf, nowVisible);
            }
        }
        return best;
    }

    private static Stand findVisibleStand(ServerLevel level, CompanionEntity npc,
            BlockPos target, double reachSq) {
        Stand best = null;
        int[][] sides = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        // +/- 1 level allows hillsides and naturally elevated approaches;
        // no flight, teleport, scaffolds or unsafe pillar building.
        for (int dy = -1; dy <= 1; dy++) for (int[] side : sides) {
            BlockPos stand = target.offset(side[0], dy, side[1]);
            if (!level.isLoaded(stand) || !level.isLoaded(stand.below())
                    || !level.isLoaded(stand.above())
                    || !level.getBlockState(stand).isAir()
                    || !level.getBlockState(stand.above()).isAir()
                    || !level.getBlockState(stand.below()).isSolidRender(level, stand.below())) continue;
            Vec3 eye = new Vec3(stand.getX()+0.5D, stand.getY()+(npc.getEyePosition().y - npc.getY()),
                    stand.getZ()+0.5D);
            if (eye.distanceToSqr(Vec3.atCenterOf(target)) > reachSq
                    || !WorldBlockActions.visibleFrom(npc, eye, target)) continue;
            double sq = npc.distanceToSqr(Vec3.atCenterOf(stand));
            if (best == null || sq < best.travelSq()) best = new Stand(stand.immutable(), sq);
        }
        return best;
    }
    private static void navigateToDrop(ServerLevel level, CompanionEntity npc, String id) {
        double reach = GatherTaskPolicy.SEARCH_RADIUS;
        level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                npc.getBoundingBox().inflate(reach), e -> e.isAlive() && !e.getItem().isEmpty()
                    && BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString().equals(id))
            .stream().min(Comparator.comparingDouble(npc::distanceToSqr))
            .ifPresent(drop -> {
                if (npc.getNavigation().isDone()) npc.getNavigation().moveTo(drop, 1.1D);
            });
    }

}
