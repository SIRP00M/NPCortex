package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.world.level.storage.LevelResource;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** M04 Gate 0/1 READ-ONLY preflight.
 * No approval of native mining or pickup and NO world mutating commands here.
 * This bridge deliberately reports HOLD until a correlated backend actor
 * admission, supported permission profile and native cancellation are proven.
 */
final class CPlusM04Runtime {
    // Bounded evidence IO is separate from the server thread. A captured
    // observation is NOT a mutation permit or a gameplay PASS verdict.
    private static final ExecutorService EVIDENCE_IO = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
            task -> { Thread thread = new Thread(task, "npcortex-m04-acceptance");
                thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private CPlusM04Runtime() {}

    static LiteralArgumentBuilder<CommandSourceStack> commands() {
        return Commands.literal("m04").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(c -> output(c.getSource(),
                        "Gate0/1 foundation candidate; mutation DISABLED; G2/G3 historical fences preserved; "
                        + "Gate3 native-cancel proof HOLD")))
                .then(Commands.literal("capture")
                        .then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> capture(c.getSource(), StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("preview-auto")
                        .then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> previewAuto(c.getSource(), StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("preview")
                        .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("block", BlockPosArgument.blockPos())
                        .executes(c -> preview(c.getSource(),
                                StringArgumentType.getString(c, "name"),
                                BlockPosArgument.getBlockPos(c, "block"))))));
    }

    private static int output(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("NPCortex M04: " + message), false);
        return 1;
    }

    private static int preview(CommandSourceStack source, String name, BlockPos pos) {
        var operator = source.getPlayer();
        if (operator == null || !source.hasPermission(2)
                || source.getServer().getPlayerList().getPlayer(operator.getUUID()) != operator)
            return output(source, "HOLD: requires a live OP; original command provenance not yet attested");
        var server = source.getServer();
        var record = C1IdentityData.get(server).find(name);
        var npc = C1P0Runtime.activeAvatar(server, name);
        if (record == null || npc == null || !record.active() || !record.profileId().equals(npc.getUUID()))
            return output(source, "HOLD: inactive or mismatched NPC identity");
        ServerLevel level = npc.serverLevel();
        if (level != operator.serverLevel() || level.isOutsideBuildHeight(pos))
            return output(source, "HOLD: dimension or build height mismatch");
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4))
            return output(source, "HOLD: target chunk is not loaded (no force-loading)");
        if (CPlusE3G2Runtime.pending(server, record.actorId())
                || CPlusE3G3Runtime.pending(server, record.actorId())
                || CPlusTransportRecovery.busy(server, record.actorId())
                || C2AgentRuntime.hasActiveActor(record.actorId())
                || C2FollowRuntime.hasActiveActor(record.actorId()))
            return output(source, "HOLD: existing local movement/follow/transport ownership");
        Vec3 from = npc.getEyePosition();
        Vec3 to = Vec3.atCenterOf(pos);
        if (from.distanceToSqr(to) > 25.0)
            return output(source, "HOLD: target beyond conservative 5-block eye reach");
        BlockHitResult ray = level.clip(new ClipContext(from, to,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, npc));
        if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(pos))
            return output(source, "HOLD: no clear block line of sight");
        var state = level.getBlockState(pos);
        if (state.isAir()) return output(source, "HOLD: target is air");
        // Information only. No Forge break events are fired as permission probes.
        return output(source, "OBSERVED block=" + state.getBlock().getDescriptionId()
                + " pos=" + pos.toShortString() + " actor=" + record.actorId()
                + " world=" + C1IdentityData.get(server).worldId()
                + "; HOLD permission=UNKNOWN/backend-claim=UNVERIFIED, no mutation");
    }

    /** Read-only, NPC-derived target; no operator XYZ and no force-loading. */
    private static int previewAuto(CommandSourceStack source, String name) {
        var npc = C1P0Runtime.activeAvatar(source.getServer(), name);
        if (npc == null || npc.isRemoved()) return output(source, "HOLD: NPC not active");
        Vec3 from = npc.getEyePosition();
        Vec3 toward = from.add(npc.getLookAngle().scale(4.5));
        var hit = npc.serverLevel().clip(new ClipContext(from, toward,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, npc));
        if (hit.getType() != HitResult.Type.BLOCK)
            return output(source, "HOLD: no loaded solid block in NPC view (no mutation)");
        return preview(source, name, hit.getBlockPos());
    }

    /** Store live observations without trusting them as completed actions.
     * Capturing requires an online operator, but does not claim proof of
     * original command packet provenance (required for future mutation).
     */
    private static int capture(CommandSourceStack source, String name) {
        var operator = source.getPlayer();
        if (operator == null || !source.hasPermission(2)
                || source.getServer().getPlayerList().getPlayer(operator.getUUID()) != operator)
            return output(source, "HOLD: capture needs a live operator");
        var server = source.getServer();
        var identity = C1IdentityData.get(server).find(name);
        var npc = C1P0Runtime.activeAvatar(server, name);
        if (identity == null || npc == null || npc.isRemoved() || !identity.active()
                || !identity.profileId().equals(npc.getUUID()))
            return output(source, "HOLD: actor identity not live or mismatched");
        final JsonObject observation;
        try {
            observation = CPlusF2WorldSampler.capture(server, name);
            observation.addProperty("capture_kind", "M04_PREVIEW_ONLY");
            observation.addProperty("acceptance", "PENDING_NOT_VERIFIED");
            observation.addProperty("mutation_permitted", false);
            observation.addProperty("requester_id", operator.getUUID().toString());
            observation.addProperty("npc_name", name);
            observation.addProperty("npc_x", npc.getX());
            observation.addProperty("npc_y", npc.getY());
            observation.addProperty("npc_z", npc.getZ());
            observation.addProperty("profile_id", npc.getUUID().toString());
            observation.addProperty("c2_active", C2AgentRuntime.hasActiveActor(identity.actorId()));
            observation.addProperty("follow_active", C2FollowRuntime.hasActiveActor(identity.actorId()));
            observation.addProperty("g2_pending", CPlusE3G2Runtime.pending(server, identity.actorId()));
            observation.addProperty("g3_pending", CPlusE3G3Runtime.pending(server, identity.actorId()));
            observation.addProperty("transport_recovery_busy", CPlusTransportRecovery.busy(server, identity.actorId()));
            observation.addProperty("inventory", C1P1InventoryRuntime.inventory(server, name));
            observation.addProperty("equipment", C1P1InventoryRuntime.equipment(server, name));
            observation.addProperty("ledger", C1P1InventoryRuntime.ledger(server, name));
            observation.addProperty("backend_identity_match", "NOT_CHECKED_BY_FORGE_CAPTURE");
        } catch (RuntimeException error) {
            return output(source, "HOLD: observation unavailable; " + error.getClass().getSimpleName());
        }
        // Build the immutable byte payload on the server thread; never access
        // world objects or mutable NBT from the background worker.
        final byte[] payload = new GsonBuilder().setPrettyPrinting().create()
                .toJson(observation).getBytes(StandardCharsets.UTF_8);
        if (payload.length > 1024 * 1024)
            return output(source, "HOLD: bounded snapshot exceeds 1 MiB");
        Path directory = server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("npcortex").resolve("m04-evidence");
        String filename = "m04-" + UUID.randomUUID() + ".json";
        try {
            EVIDENCE_IO.execute(() -> {
                try {
                    Files.createDirectories(directory);
                    Files.write(directory.resolve(filename), payload, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                } catch (Exception ex) {
                    // No false acknowledgement: the asynchronous error goes to server log.
                    org.slf4j.LoggerFactory.getLogger(CPlusM04Runtime.class)
                            .error("NPCortex M04 evidence capture failed {}", filename, ex);
                }
            });
        } catch (RejectedExecutionException rejected) {
            return output(source, "HOLD: acceptance evidence queue full");
        }
        return output(source, "capture QUEUED file=" + filename
                + " (verify file on disk; NOT gameplay PASS; no mutation)");
    }
}
