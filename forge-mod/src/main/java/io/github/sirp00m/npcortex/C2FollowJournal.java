package io.github.sirp00m.npcortex;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** C2-F1 audit journal ONLY; NOT a C0 action. Never replays a follow session.
 * Loading marks prior live records STOPPED_AFTER_RESTART without creating movement.
 */
final class C2FollowJournal extends SavedData {
    record Row(UUID actor,UUID session,UUID target,String state,String reason) { }
    private final Map<UUID,Row> rows=new LinkedHashMap<>();
    static C2FollowJournal get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                C2FollowJournal::load,C2FollowJournal::new,"npcortex_c2_follow");
    }
    Row find(UUID actor) { return rows.get(actor); }
    void record(UUID actor,UUID session,UUID target,String state,String reason) {
        if (actor==null || session==null || target==null || state==null || reason==null)
            throw new IllegalArgumentException("Invalid follow journal");
        rows.put(actor,new Row(actor,session,target,state.substring(0,Math.min(48,state.length())),
                reason.substring(0,Math.min(180,reason.length()))));
        setDirty();
    }
    private static C2FollowJournal load(CompoundTag tag) {
        C2FollowJournal journal=new C2FollowJournal();
        ListTag entries=tag.getList("sessions",Tag.TAG_COMPOUND);
        for(int i=0;i<entries.size();i++) {
            CompoundTag item=entries.getCompound(i);
            if(!item.hasUUID("actor") || !item.hasUUID("session") || !item.hasUUID("target")) continue;
            UUID actor=item.getUUID("actor"),session=item.getUUID("session"),target=item.getUUID("target");
            String state=item.getString("state"),reason=item.getString("reason");
            if(!"STOPPED".equals(state) && !"FAILED".equals(state)) {
                state="STOPPED_AFTER_RESTART";
                reason="C2-F1 follow is ephemeral; no motion replay after restart";
            }
            journal.rows.put(actor,new Row(actor,session,target,state,reason));
        }
        journal.setDirty();
        return journal;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        ListTag items=new ListTag();
        for(Row row:rows.values()) {
            CompoundTag item=new CompoundTag();
            item.putUUID("actor",row.actor());item.putUUID("session",row.session());
            item.putUUID("target",row.target());item.putString("state",row.state());
            item.putString("reason",row.reason());items.add(item);
        }
        tag.put("sessions",items);
        return tag;
    }
}
