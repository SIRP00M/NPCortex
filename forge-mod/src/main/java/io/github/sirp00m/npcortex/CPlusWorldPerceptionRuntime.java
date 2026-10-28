package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Minecraft binding of {@link CPlusWorldPerception}: READ-ONLY, server thread only, never loads a chunk.
 * It reads blocks only through {@code ServerChunkCache.getChunkNow} (null when the chunk is not loaded) and
 * {@code LevelChunk.getBlockState}; it never calls getChunk/getBlockState on the level, which would load or generate. */
final class CPlusWorldPerceptionRuntime {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    static final int DEFAULT_BELOW = 4, DEFAULT_ABOVE = 12, DEFAULT_RESULTS = 16;

    private CPlusWorldPerceptionRuntime() {}

    record Observation(CPlusWorldPerception.Result result, UUID worldId, UUID actorId) {
        JsonObject json() {
            return CPlusWorldPerception.toJson(result, worldId, actorId, CPlusWorldPerception.DEFAULT_MAX_AGE_TICKS);
        }
    }

    /** Observe around the named NPC's own block position. Throws IllegalStateException off the server thread. */
    static Observation observe(MinecraftServer server, String name, int radius, List<String> blockIds,
                               int below, int above, int maxResults, int maxExamined) {
        ServerThread.require(server, "world perception");
        C1IdentityData.Entry identity = C1IdentityData.get(server).find(name);
        C1PlayerAvatar avatar = identity == null ? null : C1P0Runtime.activeAvatar(server, name);
        if (identity == null || avatar == null || avatar.isRemoved() || !avatar.getUUID().equals(identity.profileId())
                || !C1P1CLifecycleRuntime.mayRestore(server, identity.profileId()))
            return new Observation(CPlusWorldPerception.rejected(CPlusWorldPerception.Reject.ACTOR_NOT_LIVE,
                    "actor not live/alive", null), null, null);
        ServerLevel level = avatar.serverLevel();
        String dimension = level.dimension().location().toString();
        UUID world = C1IdentityData.get(server).worldId();
        if (!dimension.equals(identity.dimension()))
            return new Observation(CPlusWorldPerception.rejected(CPlusWorldPerception.Reject.DIMENSION_MISMATCH,
                    "actor dimension differs from its registered dimension", null), world, identity.actorId());
        BlockPos center = avatar.blockPosition();
        CPlusWorldPerception.Request request = new CPlusWorldPerception.Request(dimension, center.getX(), center.getY(),
                center.getZ(), radius, below, above, blockIds, maxResults, maxExamined);
        BooleanSupplier cancelled = () -> !server.isRunning();
        CPlusWorldPerception.Result result;
        try {
            result = CPlusWorldPerception.scan(new LevelView(level, dimension), request, UUID::randomUUID, cancelled);
        } catch (IllegalStateException chunkGone) {
            result = CPlusWorldPerception.rejected(CPlusWorldPerception.Reject.INVALID_REQUEST,
                    "world changed during the scan; no observation", request);
        }
        return new Observation(result, world, identity.actorId());
    }

    static void log(String origin, Observation observation) {
        LOG.info("NPCortex M04 observe origin={} {}", origin, observation.json());
    }

    private static final class LevelView implements CPlusWorldPerception.View {
        private final ServerLevel level;
        private final String dimension;
        LevelView(ServerLevel level, String dimension) { this.level = level; this.dimension = dimension; }
        @Override public String dimension() { return dimension; }
        @Override public int minY() { return level.getMinBuildHeight(); }
        @Override public int maxYExclusive() { return level.getMaxBuildHeight(); }
        @Override public long gameTime() { return level.getGameTime(); }
        @Override public boolean chunkLoaded(int chunkX, int chunkZ) { return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null; }

        @Override public CPlusWorldPerception.Probe probe(List<String> blockIds) {
            Block[] resolved = new Block[blockIds.size()];
            for (int i = 0; i < resolved.length; i++) {
                ResourceLocation id = ResourceLocation.tryParse(blockIds.get(i));
                Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
                if (block.isEmpty()) throw new IllegalArgumentException("unknown block");
                resolved[i] = block.get();
            }
            Set<Block> wanted = Set.of(resolved);
            return new CPlusWorldPerception.Probe() {
                private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                private LevelChunk chunk;
                private int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;

                private BlockState state(int x, int y, int z) {
                    int cx = x >> 4, cz = z >> 4;
                    if (chunk == null || cx != chunkX || cz != chunkZ) {
                        chunk = level.getChunkSource().getChunkNow(cx, cz);
                        chunkX = cx; chunkZ = cz;
                    }
                    // The core only probes chunks it saw loaded in this same server tick; a null here must never read as air.
                    if (chunk == null) throw new IllegalStateException("chunk unloaded during scan");
                    return chunk.getBlockState(pos.set(x, y, z));
                }
                @Override public boolean matches(int x, int y, int z) { return wanted.contains(state(x, y, z).getBlock()); }
                @Override public String idAt(int x, int y, int z) { return BuiltInRegistries.BLOCK.getKey(state(x, y, z).getBlock()).toString(); }
            };
        }
    }
}
