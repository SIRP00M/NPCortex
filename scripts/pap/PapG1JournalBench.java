package io.github.sirp00m.npcortex;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * PAP-G1 TEST-ONLY harness. Never ships, never touches a live world: it writes one disposable NBT file under a temp dir.
 * Compiled against the REAL Minecraft jars with C1InventoryJournal/C1InventoryData copied from production; for the
 * 2048/4096 variants the copy's MAX_RECEIPTS is rewritten (test instrumentation, labelled SIMULATED in the report).
 * args: <label> <tempDir>
 */
public final class PapG1JournalBench {
    static int checks, failures;
    static void check(boolean ok, String what) { checks++; if (!ok) { failures++; System.out.println("  FAIL " + what); } }

    static ListTag inventory() {
        ListTag list = new ListTag(); // 41 occupied slots: the largest realistic player-shaped snapshot
        for (int i = 0; i < 41; i++) {
            CompoundTag t = new CompoundTag();
            t.putByte("Slot", (byte) i); t.putString("id", "minecraft:cobblestone"); t.putByte("Count", (byte) 64);
            list.add(t);
        }
        return list;
    }

    static C1InventoryData build(int actors, int receipts) {
        C1InventoryData data = new C1InventoryData();
        for (int a = 0; a < actors; a++) {
            C1InventoryData.Record r = data.ensure(UUID.nameUUIDFromBytes(("actor" + a).getBytes()));
            r.snapshot = inventory();
            for (int i = 0; i < receipts; i++) {
                UUID id = UUID.randomUUID();
                // same shape as NativeEffectRuntime.commit: "native-pickup|<attempt>" and "break <block> -> <block>"
                r.journal.commit(id, "native-pickup|" + id, "pickup minecraft:oak_log x1 -> inventory slot 3 (verified)");
            }
        }
        return data;
    }

    static double ms(long nanos) { return nanos / 1e6; }
    static double median(List<Long> v) { List<Long> s = new ArrayList<>(v); s.sort(null); return ms(s.get(s.size() / 2)); }
    static double p95(List<Long> v) { List<Long> s = new ArrayList<>(v); s.sort(null); return ms(s.get(Math.min(s.size() - 1, (int) (s.size() * 0.95)))); }

    public static void main(String[] args) throws Exception {
        String label = args[0];
        File dir = new File(args[1]);
        dir.mkdirs();
        int cap = C1InventoryJournal.MAX_RECEIPTS;
        System.out.println("== label=" + label + " MAX_RECEIPTS=" + cap);
        Method load = C1InventoryData.class.getDeclaredMethod("load", CompoundTag.class);
        load.setAccessible(true);

        // ---- behaviour at the cap (one actor) ----
        C1InventoryJournal j = new C1InventoryJournal();
        UUID first = null; String firstFp = null;
        for (int i = 0; i < cap; i++) {
            UUID id = UUID.randomUUID(); String fp = "fp|" + id;
            check(j.lookup(id, fp) == C1InventoryJournal.Lookup.NEW, "NEW before cap #" + i);
            j.commit(id, fp, "d");
            if (i == 0) { first = id; firstFp = fp; }
        }
        UUID extra = UUID.randomUUID();
        check(j.size() == cap, "size == cap");
        check(j.lookup(extra, "x") == C1InventoryJournal.Lookup.FULL, "FULL at cap for a new id");
        check(j.lookup(first, firstFp) == C1InventoryJournal.Lookup.REPLAY, "REPLAY still answered when FULL");
        check(j.lookup(first, "other") == C1InventoryJournal.Lookup.CONFLICT, "CONFLICT still answered when FULL");
        boolean threw = false;
        try { j.commit(extra, "x", "d"); } catch (IllegalStateException e) { threw = true; }
        check(threw && j.size() == cap && j.find(first) != null, "commit at FULL throws; nothing evicted");
        long rev = j.revision();
        C1InventoryJournal back = new C1InventoryJournal();
        back.restore(rev, j.receipts());
        check(back.size() == cap && back.revision() == rev && back.lookup(first, firstFp) == C1InventoryJournal.Lookup.REPLAY, "restore round trip keeps exact replay");
        threw = false;
        try { new C1InventoryJournal().restore(rev + 1, withExtra(j, extra, rev + 1)); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "restore of cap+1 receipts rejected");
        System.out.println("  behaviour checks=" + checks + " failures=" + failures);

        // ---- SavedData-shaped save/load cost: real NBT classes, one actor and ten actors ----
        for (int actors : new int[] {1, 10}) {
            for (int fill : fills(cap)) {
                C1InventoryData data = build(actors, fill);
                CompoundTag root = data.save(new CompoundTag());
                File f = new File(dir, label + "-a" + actors + "-r" + fill + ".dat");
                CompoundTag wrapper = new CompoundTag(); wrapper.put("data", root);
                NbtIo.writeCompressed(wrapper, f);
                long compressed = f.length();
                java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
                NbtIo.write(wrapper, new java.io.DataOutputStream(raw));
                List<Long> saveT = new ArrayList<>(), writeT = new ArrayList<>(), readT = new ArrayList<>(), loadT = new ArrayList<>();
                for (int i = 0; i < 40; i++) { // first 10 are warm-up and are dropped
                    long t0 = System.nanoTime(); CompoundTag r2 = data.save(new CompoundTag()); long t1 = System.nanoTime();
                    CompoundTag w2 = new CompoundTag(); w2.put("data", r2);
                    NbtIo.writeCompressed(w2, f); long t2 = System.nanoTime();
                    CompoundTag read = NbtIo.readCompressed(f); long t3 = System.nanoTime();
                    C1InventoryData loaded = (C1InventoryData) load.invoke(null, read.getCompound("data")); long t4 = System.nanoTime();
                    if (i >= 10) { saveT.add(t1 - t0); writeT.add(t2 - t1); readT.add(t3 - t2); loadT.add(t4 - t3); }
                    if (i == 39) {
                        for (C1InventoryData.Record rec : records(loaded)) check(rec.journal.size() == fill, "loaded journal size");
                        check(loaded.blockage(UUID.nameUUIDFromBytes("actor0".getBytes())) == null, "loaded not blocked");
                    }
                }
                System.out.printf("  actors=%-2d receipts/actor=%-5d raw=%8d B  gz-file=%8d B  save=%.2f/%.2f ms  write=%.2f/%.2f ms  read=%.2f/%.2f ms  load=%.2f/%.2f ms  (median/p95)%n",
                        actors, fill, raw.size(), compressed, median(saveT), p95(saveT), median(writeT), p95(writeT), median(readT), p95(readT), median(loadT), p95(loadT));
            }
        }
        System.out.println("  TOTAL checks=" + checks + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    static java.util.Collection<C1InventoryData.Record> records(C1InventoryData d) throws Exception {
        java.lang.reflect.Field f = C1InventoryData.class.getDeclaredField("records");
        f.setAccessible(true);
        return ((java.util.Map<UUID, C1InventoryData.Record>) f.get(d)).values();
    }

    static int[] fills(int cap) { return cap == 512 ? new int[] {0, 128, 512} : new int[] {0, 512, cap}; }

    static List<C1InventoryJournal.Receipt> withExtra(C1InventoryJournal j, UUID id, long rev) {
        List<C1InventoryJournal.Receipt> l = new ArrayList<>(j.receipts());
        l.add(new C1InventoryJournal.Receipt(id, "x", rev, "d"));
        return l;
    }
}
