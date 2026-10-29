package io.github.sirp00m.npcortex;

import static io.github.sirp00m.npcortex.NativeGameTests.need;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * PAP-G1b TEST-ONLY X2 with a REAL process kill (Runtime.halt) of the disposable GameTest server. scripts\pap\run-pap-g1b.ps1 runs
 * this namespace twice on the same throw-away world. Phase 1 (no marker): receipt A is saved, receipt B is committed in memory,
 * the JVM is halted. Phase 2 (marker present): the restarted server must hold A and must NOT hold B.
 */
@GameTestHolder("npcortexpapkill")
@PrefixGameTestTemplate(false)
public final class PapG1bKillGameTests {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();

    private static final net.minecraft.core.BlockPos PROBE = new net.minecraft.core.BlockPos(8, 200, 8);

    private PapG1bKillGameTests() {}

    @GameTest(batch = "pap_k", template = "arena", templateNamespace = "npcortexpapkill", timeoutTicks = 300)
    public static void killBetweenCommitAndSave(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        File marker = new File(server.getWorldPath(LevelResource.ROOT).toFile(), "pap-kill-marker.txt");
        UUID profile = UUID.nameUUIDFromBytes("kill".getBytes());
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        C1InventoryData data = C1InventoryData.get(server);
        if (!marker.exists()) {
            C1InventoryData.Record r = data.ensure(profile);
            r.snapshot = PapG1bPersistenceGameTests.inventory();
            r.journal.commit(a, "fp|a", "A");
            data.setDirty();
            server.overworld().getDataStorage().save();                       // A is on disk
            server.overworld().setBlock(PROBE, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 3);   // effect applied, chunk NOT saved
            r.journal.commit(b, "fp|b", "B");
            data.setDirty();
            server.overworld().getDataStorage().save();                       // B is on disk although the block change is not
            r.journal.commit(c, "fp|c", "C");
            data.setDirty();                                                  // C only in memory
            Files.writeString(marker.toPath(), "phase1 done; killing JVM now");
            LOG.info("PAPG1B|K1|A and B saved (block change not), C memory-only; halting the JVM");
            System.out.flush();
            System.err.flush();
            Thread.sleep(500);
            Runtime.getRuntime().halt(137);
        }
        C1InventoryData.Record r = data.find(profile);
        boolean aDurable = r != null && r.journal.lookup(a, "fp|a") == C1InventoryJournal.Lookup.REPLAY;
        boolean bDurable = r != null && r.journal.lookup(b, "fp|b") == C1InventoryJournal.Lookup.REPLAY;
        boolean cLost = r != null && r.journal.lookup(c, "fp|c") == C1InventoryJournal.Lookup.NEW;
        boolean blockLost = server.overworld().getBlockState(PROBE).isAir();
        LOG.info("PAPG1B|K2|after a real process kill and restart|saved_receipt_A_present=" + aDurable + "|saved_receipt_B_present=" + bDurable
                + "|memory_only_receipt_C_absent=" + cLost + "|block_change_made_before_B_save_is_lost_in_the_chunk=" + blockLost);
        need(aDurable && bDurable && cLost, "saved receipts survived the kill, the memory-only one did not");
        helper.succeed();
    }
}
