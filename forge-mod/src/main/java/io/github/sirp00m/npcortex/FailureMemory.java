package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;

/** Persistent, bounded evidence about failures. Snapshot refs are never identities. */
public final class FailureMemory {
    private static final String KEY="skillFailures";
    private FailureMemory() {}
    public static String identity(String skill, String ref, JsonObject world, BlockPos origin, String item) {
        BlockPos block=ref.startsWith("e") ? ExplorationSkills.resolve(world,ref,origin) : ref.startsWith("m")
                ? BrainObservation.resolveMaterial(world,ref,origin)
                : BrainObservation.resolveBlock(world,ref,origin,item);
        if(block!=null) return skill+"/block/"+block.asLong();
        if(ref.startsWith("d")) for(var e:world.getAsJsonArray("drops")) {
            var d=e.getAsJsonObject();
            if(ref.equals(d.get("ref").getAsString())) return skill+"/drop/"+d.get("uuid").getAsString();
        }
        return skill+"/goal";
    }
    public static long fingerprint(CompanionEntity npc, CompoundTag task, BlockPos target) {
        long hash=npc.blockPosition().asLong();
        // Pose, nearby geometry and relevant material inventory invalidate a failed hypothesis.
        BlockPos center=target==null ? npc.blockPosition() : target;
        for(int x=-1;x<=1;x++) for(int y=-1;y<=2;y++) for(int z=-1;z<=1;z++) {
            BlockPos p=center.offset(x,y,z);
            hash=31*hash+(npc.level().isLoaded(p) ? npc.level().getBlockState(p).toString().hashCode() : -1);
        }
        for(int x=-2;x<=2;x++) for(int y=-1;y<=2;y++) for(int z=-2;z<=2;z++) {
            BlockPos p=npc.blockPosition().offset(x,y,z);
            hash=31*hash+(npc.level().isLoaded(p) ? npc.level().getBlockState(p).toString().hashCode() : -1);
        }
        for(int i=0;i<npc.bag().getContainerSize();i++) {
            var s=npc.bag().getItem(i);
            hash=31*hash+BuiltInRegistries.ITEM.getKey(s.getItem()).hashCode(); hash=31*hash+s.getCount();
        }
        return 31*hash+TreeScaffold.size(task)+Navigation3DAccess.size(task);
    }
    private static io.github.sirp00m.npcortex.agent.FailureHistory read(CompoundTag task) {
        var rows=new java.util.ArrayList<io.github.sirp00m.npcortex.agent.FailureHistory.Entry>();
        for(var tag:task.getList(KEY,Tag.TAG_COMPOUND)) {
            var r=(CompoundTag)tag;
            rows.add(new io.github.sirp00m.npcortex.agent.FailureHistory.Entry(
                r.getString("key"),r.getLong("fingerprint"),r.getInt("count"),r.getString("reason")));
        }
        return new io.github.sirp00m.npcortex.agent.FailureHistory(rows);
    }
    public static boolean blocked(CompoundTag task,String key,long fingerprint) {
        return read(task).blocked(key,fingerprint);
    }
    public static void remember(CompoundTag task,String reason) {
        var memory=read(task);
        memory.record(task.getString("activeSkillKey"),task.getLong("activeFingerprint"),reason);
        ListTag rows=new ListTag();
        for(var entry:memory.entries()) {
            CompoundTag row=new CompoundTag(); row.putString("key",entry.key());
            row.putLong("fingerprint",entry.fingerprint()); row.putInt("count",entry.count());
            row.putString("reason",entry.reason()); rows.add(row);
        }
        task.put(KEY,rows);
    }
    public static JsonArray observation(CompoundTag task) {
        JsonArray out=new JsonArray();
        var rows=task.getList(KEY,Tag.TAG_COMPOUND);
        for(int i=Math.max(0,rows.size()-8);i<rows.size();i++) {
            var row=rows.getCompound(i); JsonObject obj=new JsonObject();
            // No coordinates exposed. Stable opaque task-local identity, diagnostic only.
            obj.addProperty("attempt",i); obj.addProperty("reason",row.getString("reason"));
            obj.addProperty("count",row.getInt("count")); out.add(obj);
        }
        return out;
    }
}
