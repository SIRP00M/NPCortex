package io.github.sirp00m.npcortex.agent;

import java.util.ArrayList;
import java.util.List;

/** Task-local failure evidence keyed by stable target identity and observed conditions. */
public final class FailureHistory {
    public record Entry(String key,long fingerprint,int count,String reason) {}
    private final List<Entry> rows=new ArrayList<>();
    public FailureHistory(List<Entry> saved) {
        for(var row:saved) {
            if(row.key()==null || row.reason()==null || row.key().length()>160 || row.reason().length()>180
                    || row.count()<1 || row.count()>2) continue;
            if(rows.size()==32) rows.remove(0);
            rows.add(row);
        }
    }
    public boolean blocked(String key,long fingerprint) {
        return rows.stream().anyMatch(r->r.key().equals(key)&&r.fingerprint()==fingerprint&&r.count()>=2);
    }
    public void record(String key,long fingerprint,String reason) {
        if(key==null || key.isEmpty() || key.length()>160 || reason==null) return;
        String bounded=reason.substring(0,Math.min(180,reason.length()));
        // A failed bounded route search with the current pose/terrain/materials
        // is deterministic. Repeating it immediately cannot produce a path.
        // Mark this hypothesis exhausted after ONE attempt; a changed world,
        // inventory, pose or target changes the fingerprint and permits retry.
        // Do not fast-block transient route changes, stalls or other failures.
        boolean deterministicNoRoute = (key.startsWith("navigate_3d_drop/drop/")
                && bounded.contains("no safe 3D pickup route found within limits"))
                || (key.startsWith("navigate_3d/block/")
                && bounded.contains("bounded 3D search found no safe supported route"));
        int firstCount = deterministicNoRoute ? 2 : 1;
        for(int i=0;i<rows.size();i++) {
            Entry old=rows.get(i);
            if(old.key().equals(key)&&old.fingerprint()==fingerprint) {
                rows.set(i,new Entry(key,fingerprint,Math.min(2,old.count()+firstCount),bounded)); return;
            }
        }
        if(rows.size()==32) rows.remove(0);
        rows.add(new Entry(key,fingerprint,firstCount,bounded));
    }
    public List<Entry> entries() { return List.copyOf(rows); }
}
