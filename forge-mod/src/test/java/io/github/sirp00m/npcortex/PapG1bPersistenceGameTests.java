package io.github.sirp00m.npcortex;

import static io.github.sirp00m.npcortex.NativeGameTests.need;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * PAP-G1b TEST-ONLY. Real Minecraft SavedData save/load of the PRODUCTION C1InventoryData/C1InventoryJournal (512 cap) inside the
 * throw-away GameTest world (scripts\pap\run-pap-g1b.ps1 wipes forge-mod\run-gametest\world first). Own namespace; never part of
 * the default run. Lines containing "PAPG1B|" are the measurements. GameTests execute on the server thread, so a timed call here is
 * a real server-thread stall.
 */
@GameTestHolder("npcortexpap")
@PrefixGameTestTemplate(false)
public final class PapG1bPersistenceGameTests {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    static final String KEY = "npcortex_c1_p1b_inventory";
    static final int ITER = 12, WARM = 3;

    private PapG1bPersistenceGameTests() {}

    static void out(String line) { LOG.info("PAPG1B|" + line); }

    static ListTag inventory() {
        ListTag list = new ListTag();
        for (int i = 0; i < 41; i++) {
            CompoundTag t = new CompoundTag();
            t.putByte("Slot", (byte) i);
            t.putString("id", "minecraft:cobblestone");
            t.putByte("Count", (byte) 64);
            list.add(t);
        }
        return list;
    }

    /** Production journal commits: same fingerprint/detail shape as NativeEffectRuntime. */
    static void fill(C1InventoryData data, int actors, int receipts) {
        for (int a = 0; a < actors; a++) {
            C1InventoryData.Record r = data.ensure(UUID.nameUUIDFromBytes(("pap-actor-" + a).getBytes()));
            r.snapshot = inventory();
            for (int i = r.journal.size(); i < receipts; i++) {
                UUID id = UUID.randomUUID();
                r.journal.commit(id, "native-pickup|" + id, "pickup minecraft:oak_log x1 -> inventory slot 3 (verified)");
            }
        }
        data.setDirty();
    }

    static Function<CompoundTag, C1InventoryData> loader() throws Exception {
        Method m = C1InventoryData.class.getDeclaredMethod("load", CompoundTag.class);
        m.setAccessible(true);
        return tag -> {
            try { return (C1InventoryData) m.invoke(null, tag); } catch (Exception e) { throw new IllegalStateException(e); }
        };
    }

    /** A fresh storage on the same folder is exactly what a restarted server reads from disk. */
    static C1InventoryData loadFrom(File dir, MinecraftServer server) throws Exception {
        return new DimensionDataStorage(dir, server.getFixerUpper()).computeIfAbsent(loader(), C1InventoryData::new, KEY);
    }

    static double median(List<Long> v) { List<Long> s = new ArrayList<>(v); s.sort(null); return s.get(s.size() / 2) / 1e6; }
    static double max(List<Long> v) { return v.stream().mapToLong(Long::longValue).max().orElse(0) / 1e6; }

    /** A: real DimensionDataStorage.save()/computeIfAbsent() on a private directory, 1/3/5/10 NPCs x 512 receipts. */
    @GameTest(batch = "pap_a", template = "arena", templateNamespace = "npcortexpap", timeoutTicks = 400)
    public static void realSavedDataSaveLoad(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        File root = server.getWorldPath(LevelResource.ROOT).toFile();
        for (int actors : new int[] {1, 3, 5, 10}) {
            File dir = new File(root, "pap-g1b-a" + actors);
            dir.mkdirs();
            DimensionDataStorage storage = new DimensionDataStorage(dir, server.getFixerUpper());
            C1InventoryData data = new C1InventoryData();
            storage.set(KEY, data);
            fill(data, actors, C1InventoryJournal.MAX_RECEIPTS);
            List<Long> saveT = new ArrayList<>(), loadT = new ArrayList<>();
            File file = new File(dir, KEY + ".dat");
            for (int i = 0; i < ITER; i++) {
                data.setDirty();
                long t0 = System.nanoTime();
                storage.save();
                long t1 = System.nanoTime();
                C1InventoryData back = loadFrom(dir, server);
                long t2 = System.nanoTime();
                if (i >= WARM) { saveT.add(t1 - t0); loadT.add(t2 - t1); }
                if (i == ITER - 1) {
                    int ok = 0;
                    for (int a = 0; a < actors; a++) {
                        C1InventoryData.Record r = back.find(UUID.nameUUIDFromBytes(("pap-actor-" + a).getBytes()));
                        if (r != null && r.journal.size() == C1InventoryJournal.MAX_RECEIPTS
                                && r.journal.revision() == C1InventoryJournal.MAX_RECEIPTS) ok++;
                    }
                    need(ok == actors, "every actor's 512 receipts survived a real save + load (" + ok + "/" + actors + ")");
                }
            }
            out(String.format("A|label=PRODUCTION-512 real DimensionDataStorage|actors=%d|receipts=512|file_bytes=%d|save_ms_median=%.2f|save_ms_max=%.2f|load_ms_median=%.2f|load_ms_max=%.2f",
                    actors, file.length(), median(saveT), max(saveT), median(loadT), max(loadT)));
        }
        helper.succeed();
    }

    /** B: the server's OWN storage and a real ServerLevel.save (SavedData + chunks + entities), on the server thread. */
    @GameTest(batch = "pap_b", template = "arena", templateNamespace = "npcortexpap", timeoutTicks = 600)
    public static void realServerLevelSave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var level = server.overworld();
        List<Long> clean = new ArrayList<>();
        for (int i = 0; i < ITER; i++) {
            long t = System.nanoTime();
            level.save(null, false, false);
            if (i >= WARM) clean.add(System.nanoTime() - t);
        }
        out(String.format("B|baseline level.save, no C1 data dirty|ms_median=%.2f|ms_max=%.2f|tick_budget_ms=50", median(clean), max(clean)));
        File file = new File(server.getWorldPath(LevelResource.ROOT).toFile(), "data/" + KEY + ".dat");
        C1InventoryData data = C1InventoryData.get(server);
        for (int actors : new int[] {1, 3, 5, 10}) {
            fill(data, actors, C1InventoryJournal.MAX_RECEIPTS);
            List<Long> dirty = new ArrayList<>();
            for (int i = 0; i < ITER; i++) {
                data.setDirty();
                long t = System.nanoTime();
                level.save(null, false, false);
                if (i >= WARM) dirty.add(System.nanoTime() - t);
            }
            out(String.format("B|PRODUCTION-512 level.save, C1 dirty|actors=%d|file_bytes=%d|ms_median=%.2f|ms_max=%.2f|tick_budget_ms=50",
                    actors, file.length(), median(dirty), max(dirty)));
            need(file.exists() && file.length() > 0, "the server's own storage wrote npcortex_c1_p1b_inventory.dat");
        }
        helper.succeed();
    }

    /** C: X2 semantics with real storage. A receipt committed in memory is NOT on disk until a save. */
    @GameTest(batch = "pap_c", template = "arena", templateNamespace = "npcortexpap", timeoutTicks = 200)
    public static void memoryOnlyWindow(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        File dir = new File(server.getWorldPath(LevelResource.ROOT).toFile(), "pap-g1b-x2");
        dir.mkdirs();
        DimensionDataStorage storage = new DimensionDataStorage(dir, server.getFixerUpper());
        C1InventoryData data = new C1InventoryData();
        storage.set(KEY, data);
        UUID profile = UUID.nameUUIDFromBytes("x2".getBytes());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        C1InventoryData.Record r = data.ensure(profile);
        r.snapshot = inventory();
        r.journal.commit(a, "fp|" + a, "A");
        data.setDirty();
        storage.save();
        r.journal.commit(b, "fp|" + b, "B");
        data.setDirty();                                                     // in memory, NO save: the X2 window
        C1InventoryJournal j1 = loadFrom(dir, server).find(profile).journal; // what a crash here leaves on disk
        boolean aDurable = j1.lookup(a, "fp|" + a) == C1InventoryJournal.Lookup.REPLAY;
        boolean bLost = j1.lookup(b, "fp|" + b) == C1InventoryJournal.Lookup.NEW;
        storage.save();
        boolean bAfterSave = loadFrom(dir, server).find(profile).journal.lookup(b, "fp|" + b) == C1InventoryJournal.Lookup.REPLAY;
        out("C|X2 memory-only window (real storage; a fresh-storage read is the restart view)|saved_receipt_durable=" + aDurable
                + "|unsaved_receipt_absent_after_crash=" + bLost + "|receipt_present_after_save=" + bAfterSave);
        need(aDurable && bLost && bAfterSave, "receipts are durable only after a real save");
        helper.succeed();
    }
}
