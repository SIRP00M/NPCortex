package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.cplus.CPlusF2ReadOnlyPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * C-F2 opt-in trusted, READ-ONLY world observation. Server thread only.
 * Never calls getChunk(), block entities, use/break hooks, or force-loads a chunk.
 * This is a sidecar diagnostic; legacy C0/C2 snapshots are unchanged.
 */
final class CPlusF2WorldSampler {
    private static final int RADIUS = 1;
    private static final int MAX_ENTITIES = 8;
    private static final int MAX_DROPS = 8;
    private static final Map<MinecraftServer, UUID> SESSIONS = new WeakHashMap<>();

    /** The per-JVM-lifetime session identity used by every C+ snapshot. Server thread only (WeakHashMap, no locking). */
    static UUID session(MinecraftServer server) {
        return SESSIONS.computeIfAbsent(server, ignored -> UUID.randomUUID());
    }
    private CPlusF2WorldSampler() {}

    static JsonObject capture(MinecraftServer server, String name) {
        if (server == null || !server.isSameThread()) throw new IllegalStateException("server thread required");
        C1IdentityData.Entry identity = C1IdentityData.get(server).find(name);
        if (identity == null) throw new IllegalStateException("unknown NPC");
        C1PlayerAvatar avatar = C1P0Runtime.activeAvatar(server,name);
        if (avatar == null || avatar.isRemoved() || !avatar.getUUID().equals(identity.profileId())
                || !C1P1CLifecycleRuntime.mayRestore(server,identity.profileId()))
            throw new IllegalStateException("actor not live/alive");
        ServerLevel level=avatar.serverLevel();
        String dimension=level.dimension().location().toString();
        if (!dimension.equals(identity.dimension())) throw new IllegalStateException("dimension mismatch");
        UUID session=SESSIONS.computeIfAbsent(server, ignored -> UUID.randomUUID());
        BlockPos center=avatar.blockPosition();
        JsonObject out=new JsonObject();
        out.addProperty("schema_version",2);
        out.addProperty("world_id",C1IdentityData.get(server).worldId().toString());
        out.addProperty("dimension_id",dimension);
        out.addProperty("actor_id",identity.actorId().toString());
        out.addProperty("session_id",session.toString());
        out.addProperty("observation_id",UUID.randomUUID().toString());
        out.addProperty("capture_tick",level.getGameTime());
        out.addProperty("source","SERVER_WORLD");
        out.addProperty("read_only",true);
        out.add("center",pos(center));
        out.addProperty("radius",RADIUS);
        JsonArray blocks=new JsonArray();
        int unknown=0;
        CPlusF2ReadOnlyPolicy.Status centerStatus=null;
        for (int dy=-RADIUS;dy<=RADIUS;dy++) for (int dx=-RADIUS;dx<=RADIUS;dx++)
            for (int dz=-RADIUS;dz<=RADIUS;dz++) {
                BlockPos p=center.offset(dx,dy,dz);
                CPlusF2ReadOnlyPolicy.Status status=classify(level,p);
                if (dx==0&&dy==0&&dz==0) centerStatus=status;
                JsonObject b=new JsonObject();
                b.add("position",pos(p));
                b.addProperty("status",status.name());
                if (status==CPlusF2ReadOnlyPolicy.Status.OBSERVED) {
                    BlockState state=level.getBlockState(p);
                    b.addProperty("block_id",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    JsonObject properties=new JsonObject();
                    for (Property<?> property:state.getProperties())
                        properties.addProperty(property.getName(),propertyValue(state,property));
                    b.add("properties",properties);
                } else unknown++;
                blocks.add(b);
            }
        out.add("blocks",blocks);
        JsonObject coverage=new JsonObject();
        coverage.addProperty("requested",blocks.size());
        coverage.addProperty("recorded",blocks.size());
        coverage.addProperty("unknown",unknown);
        coverage.addProperty("complete",unknown==0);
        coverage.addProperty("truncated",false);
        out.add("coverage",coverage);
        // Entity queries are confined to a small local AABB; no NBT, owner, player names,
        // item provenance or capability data is captured.
        AABB region=new AABB(center).inflate(3.0,2.0,3.0);
        var drops=level.getEntitiesOfClass(ItemEntity.class,region,e->e.isAlive()&&!e.getItem().isEmpty());
        JsonArray observedDrops=new JsonArray();
        for (int i=0;i<Math.min(MAX_DROPS,drops.size());i++) {
            ItemEntity e=drops.get(i);
            JsonObject d=new JsonObject();
            d.addProperty("entity_id",e.getUUID().toString());
            d.addProperty("item_id",BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString());
            d.addProperty("count",e.getItem().getCount());
            d.add("position",coords(e.getX(),e.getY(),e.getZ()));
            observedDrops.add(d);
        }
        out.add("nearby_drops",observedDrops);
        out.addProperty("drops_truncated",drops.size()>MAX_DROPS);
        var entities=level.getEntitiesOfClass(Entity.class,region,e->e.isAlive()&&e!=avatar&&!(e instanceof ItemEntity));
        JsonArray observedEntities=new JsonArray();
        for (int i=0;i<Math.min(MAX_ENTITIES,entities.size());i++) {
            Entity e=entities.get(i);
            JsonObject info=new JsonObject();
            info.addProperty("entity_id",e.getUUID().toString());
            info.addProperty("type_id",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            info.add("position",coords(e.getX(),e.getY(),e.getZ()));
            observedEntities.add(info);
        }
        out.add("nearby_entities",observedEntities);
        out.addProperty("entities_truncated",entities.size()>MAX_ENTITIES);
        JsonObject permission=new JsonObject();
        var decision=CPlusF2ReadOnlyPolicy.decide(centerStatus,"break_block");
        permission.addProperty("operation","break_block");
        permission.add("position",pos(center));
        permission.addProperty("decision",decision.verdict().name());
        permission.addProperty("reason",decision.reason());
        permission.addProperty("checked_tick",level.getGameTime());
        permission.addProperty("evidence_id",out.get("observation_id").getAsString());
        out.add("permission",permission);
        if (out.toString().getBytes(StandardCharsets.UTF_8).length > 16000)
            throw new IllegalStateException("C-F2 bounded diagnostic payload exceeded; HOLD without partial claims");
        return out;
    }

    private static CPlusF2ReadOnlyPolicy.Status classify(ServerLevel level,BlockPos p) {
        if (p.getY()<level.getMinBuildHeight() || p.getY()>=level.getMaxBuildHeight())
            return CPlusF2ReadOnlyPolicy.Status.OUT_OF_HEIGHT;
        if (!level.getWorldBorder().isWithinBounds(p))
            return CPlusF2ReadOnlyPolicy.Status.OUT_OF_BORDER;
        // ServerChunkCache.hasChunk is a loaded check, not a getChunk/generation request.
        if (!level.getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4))
            return CPlusF2ReadOnlyPolicy.Status.UNKNOWN_CHUNK;
        return CPlusF2ReadOnlyPolicy.Status.OBSERVED;
    }
    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }
    private static JsonArray pos(BlockPos p) {
        JsonArray a=new JsonArray(); a.add(p.getX());a.add(p.getY());a.add(p.getZ());return a;
    }
    private static JsonArray coords(double x,double y,double z) {
        JsonArray a=new JsonArray();a.add(x);a.add(y);a.add(z);return a;
    }
}
