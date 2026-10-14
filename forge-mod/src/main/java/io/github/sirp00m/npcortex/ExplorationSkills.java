package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import io.github.sirp00m.npcortex.agent.Navigation3D;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import static io.github.sirp00m.npcortex.AgenticTaskController.*;

/** Receding observation frontiers: Qwen chooses direction, the motor follows
 * existing supported terrain, then the next observation scans around the NEW pose.
 * No omniscient map, terrain creation or chunk forcing. Recent visited cells are
 * saved in NBT; revisiting them is not offered as new exploration. */
public final class ExplorationSkills {
    private ExplorationSkills() {}
    private static long sector(BlockPos p) {
        return new BlockPos(Math.floorDiv(p.getX(),8),Math.floorDiv(p.getY(),4),Math.floorDiv(p.getZ(),8)).asLong();
    }
    private static boolean seen(CompoundTag task, BlockPos p) {
        long key=sector(p);
        for(long entry:task.getLongArray("exploredCells")) if(entry==key) return true;
        return false;
    }
    public static void visited(CompanionEntity npc, CompoundTag task) {
        long key=sector(npc.blockPosition());
        long[] old=task.getLongArray("exploredCells");
        for(long entry:old) if(entry==key) return;
        // Remember recent 512 regions. This bounds memory, not travel radius.
        int retained=Math.min(511,old.length);
        long[] next=new long[retained+1];
        System.arraycopy(old,old.length-retained,next,0,retained); next[retained]=key;
        task.putLongArray("exploredCells",next);
        task.putInt("exploredSegments",task.getInt("exploredSegments")+1);
    }
    public static JsonArray observe(ServerPlayer owner, CompanionEntity npc, CompoundTag task) {
        JsonArray out=new JsonArray();
        if(!npc.onGround()) return out;
        visited(npc,task);
        BlockPos center=npc.blockPosition();
        // Offer only REAL connected standing cells. One flood fill serves all
        // eight directions; isolated safe tiles must not become Qwen options.
        var routes=Navigation3DAccess.terrainRoutes(owner,npc,task);
        Set<BlockPos> offered=new HashSet<>();
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
            BlockPos choice=null;
            double best=Double.NEGATIVE_INFINITY;
            for(Navigation3D.Cell c:routes.destinations()) {
                BlockPos candidate=new BlockPos(c.x(),c.y(),c.z());
                int dx=c.x()-center.getX(),dy=c.y()-center.getY(),dz=c.z()-center.getZ();
                int forward=dx*d[0]+dz*d[1];
                int lateral=Math.abs(dx*d[1]-dz*d[0]);
                if(forward<4 || lateral>Math.max(2,forward/2)
                        || Math.abs(dy)>4 || seen(task,candidate) || offered.contains(candidate)) continue;
                double score=forward-1.5*lateral-0.7*Math.abs(dy);
                if(score>best) { choice=candidate; best=score; }
            }
            if(choice==null) continue;
            offered.add(choice);
            JsonObject row=new JsonObject(); row.addProperty("ref","e"+out.size());
            row.addProperty("dx",choice.getX()-center.getX());
            row.addProperty("dy",choice.getY()-center.getY());
            row.addProperty("dz",choice.getZ()-center.getZ());
            out.add(row);
        }
        return out;
    }
    public static BlockPos resolve(JsonObject world, String ref, BlockPos origin) {
        if(ref==null || !ref.matches("e[0-7]") || !world.has("frontiers")) return null;
        int index=Integer.parseInt(ref.substring(1));
        var rows=world.getAsJsonArray("frontiers"); if(index>=rows.size()) return null;
        var row=rows.get(index).getAsJsonObject();
        if(!ref.equals(row.get("ref").getAsString())) return null;
        return origin.offset(row.get("dx").getAsInt(),row.get("dy").getAsInt(),row.get("dz").getAsInt());
    }
    public static BlockPos workAnchor(CompoundTag task) {
        return BlockPos.of(task.getLong("workAnchor"));
    }
    public static void explore(BrainSkillRegistry.Context c, String ref) {
        BlockPos p=resolve(c.world(),ref,c.origin());
        if(p==null) { result(c.npc(),c.task(),"explore",ref,"[Action blocked: stale frontier.]",false); return; }
        if(!c.task().contains("workAnchor")) c.task().putLong("workAnchor",c.origin().asLong());
        start(c,"explore",ref,p);
    }
    public static void return_to_work(BrainSkillRegistry.Context c, String ref) {
        start(c,"return_to_work",ref,workAnchor(c.task()));
    }
    private static void start(BrainSkillRegistry.Context c,String skill,String ref,BlockPos p) {
        String outcome=Navigation3DAccess.startWaypoint(c.owner(),c.npc(),c.task(),p);
        c.task().putString("lastChoice",skill+(ref.isEmpty()?"":"("+ref+")"));
        if(outcome.startsWith("[Action started:")) {
            c.task().putString("state","access_3d"); c.task().putString("lastResult",outcome);
            save(c.npc(),c.task());
        } else result(c.npc(),c.task(),skill,ref,outcome,false);
    }
}
