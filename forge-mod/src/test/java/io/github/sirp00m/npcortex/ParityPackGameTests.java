package io.github.sirp00m.npcortex;

import static io.github.sirp00m.npcortex.NativeGameTests.need;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Data Pack execution evidence (NOT the Owner's gameplay campaign): the pack NPCortex_Parity_Acceptance is loaded by the real Minecraft
 * function manager (scripts\parity\run-parity.ps1 -Mode Auto copies it into the throw-away GameTest world before boot), every function file
 * must be REGISTERED (a parse error drops a function), and each scenario's setup -> wait -> check is EXECUTED against a real avatar
 * through the real command dispatcher. The verdict is whatever the pack's own check function stored in the scoreboard; this class never
 * writes a verdict. Scenarios that need a human, or have no action contract, stay PENDING / NOT TESTED by design. A pack FAIL fails the test.
 */
@GameTestHolder("npcortexparitypack")
@PrefixGameTestTemplate(false)
public final class ParityPackGameTests {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    // Forge enables a GameTest by the namespace of its TEMPLATE, so this class owns a copy of the arena template.
    private static final String NS = "npcortexparitypack";
    private static final Path FUNCTIONS = Path.of("..", "..", "integrations", "datapacks", "NPCortex_Parity_Acceptance", "data", "npcortex_parity", "functions")
            .toAbsolutePath().normalize();

    private ParityPackGameTests() {}

    private static List<String> allFunctionIds() throws java.io.IOException {
        List<String> ids = new ArrayList<>();
        try (Stream<Path> s = Files.walk(FUNCTIONS)) {
            s.filter(p -> p.toString().endsWith(".mcfunction")).forEach(p ->
                    ids.add(FUNCTIONS.relativize(p).toString().replace('\\', '/').replaceFirst("\\.mcfunction$", "")));
        }
        return ids;
    }

    private static String id(String path) { return "npcortex_parity:" + path; }

    @GameTest(batch = "pack_exec", template = "arena", templateNamespace = NS, timeoutTicks = 6000)
    public static void packLoadsAndScenariosExecute(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        var server = f.server;
        List<String> ids;
        try { ids = allFunctionIds(); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        need(ids.size() >= 80, "pack files found on disk: " + ids.size() + " in " + FUNCTIONS);
        List<String> missing = new ArrayList<>();
        for (String p : ids) if (server.getFunctions().get(new ResourceLocation(id(p))).isEmpty()) missing.add(p);
        need(missing.isEmpty(), "functions NOT registered by Minecraft (parse error or pack not loaded): " + missing);
        LOG.info("PARITY|PACK|registered={}", ids.size());

        C1SurvivalParity.autoPickup = true;   // the production default: the pack's T05 expects vanilla touch pickup (the fixture had pinned it off)
        C1PlayerAvatar cmdr = f.npc("cmdr", 2, 1, 2, null);
        // Command feedback (tellraw / command replies) is logged as PARITY|PACKSAY so refusals such as "Respawn requires DEAD" are visible.
        // The commander avatar stands next to every cell. Vanilla pickup would let it take the NPC's death drops (a human standing there would
        // too), which hides T12's drops: a full inventory keeps the harness commander out of the item economy.
        for (int i = 0; i < cmdr.getInventory().items.size(); i++) cmdr.getInventory().items.set(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
        var capture = new net.minecraft.commands.CommandSource() {
            @Override public void sendSystemMessage(net.minecraft.network.chat.Component c) { LOG.info("PARITY|PACKSAY|{}", c.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        var source = cmdr.createCommandSourceStack().withPermission(4).withSource(capture);
        run(server, source, "lib/init");

        List<Integer> scenarios = new ArrayList<>();
        for (int i = 1; i <= 25; i++) scenarios.add(i);
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        // GameTest worlds do not advance scheduled events (ServerLevel.tickTime is off), so `schedule function` never fires here: this loop
        // plays the scheduler for lib/sample only. A real world schedules it itself (the Owner campaign).
        helper.onEachTick(() -> { if (helper.getTick() % 5 == 0) run(server, source, "lib/sample"); });
        step(helper, f, source, scenarios, 0, ids, verdicts);
    }

    private static int run(net.minecraft.server.MinecraftServer server, net.minecraft.commands.CommandSourceStack source, String path) {
        var fn = server.getFunctions().get(new ResourceLocation(id(path)));
        if (fn.isEmpty()) return -1;
        return server.getFunctions().execute(fn.get(), source);
    }

    private static String suffix(List<String> ids, String kind, int n) {
        String base = kind + "/t" + String.format("%02d", n);
        if (ids.contains(base)) return base;
        String three = base + "_3";   // T24 is split by NPC count; run the smallest, the 5/10 NPC variants are the Owner's
        return ids.contains(three) ? three : null;
    }

    /** One scenario at a time: reset -> setup -> 130 ticks -> [stage2 -> 60 ticks] -> check -> read the pack's own verdict. */
    private static void step(GameTestHelper helper, NativeGameTests.Fx f, net.minecraft.commands.CommandSourceStack source, List<Integer> scenarios, int index,
                             List<String> ids, Map<String, Integer> verdicts) {
        if (index >= scenarios.size()) { finish(f, verdicts); return; }
        int n = scenarios.get(index);
        String setup = suffix(ids, "setup", n), check = suffix(ids, "check", n);
        String stage2 = ids.contains("setup/t" + String.format("%02d", n) + "_stage2") ? "setup/t" + String.format("%02d", n) + "_stage2" : null;
        String key = "T" + String.format("%02d", n);
        run(f.server, source, "reset");
        if (setup == null || check == null) { verdicts.put(key, -2); step(helper, f, source, scenarios, index + 1, ids, verdicts); return; }
        run(f.server, source, setup);
        helper.runAfterDelay(130, () -> {
            Runnable doCheck = () -> {
                var npc = C1P0Runtime.activeAvatar(f.server, "Parity");
                LOG.info("PARITY|PACKSTATE|{}|alive={}|hp={}|food={}|sat={}|air={}|pos={}", key, npc != null, npc == null ? "-" : npc.getHealth(),
                        npc == null ? "-" : npc.getFoodData().getFoodLevel(), npc == null ? "-" : npc.getFoodData().getSaturationLevel(),
                        npc == null ? "-" : npc.getAirSupply(), npc == null ? "-" : npc.blockPosition());
                run(f.server, source, check);
                verdicts.put(key, score(f.server, "#t" + String.format("%02d", n)));
                helper.runAfterDelay(40, () -> step(helper, f, source, scenarios, index + 1, ids, verdicts));
            };
            if (stage2 != null) { run(f.server, source, stage2); helper.runAfterDelay(60, doCheck); } else doCheck.run();
        });
    }

    private static int score(net.minecraft.server.MinecraftServer server, String holder) {
        var board = server.getScoreboard();
        var objective = board.getObjective("pp");
        return objective == null || !board.hasPlayerScore(holder, objective) ? 0 : board.getOrCreatePlayerScore(holder, objective).getScore();
    }

    private static void finish(NativeGameTests.Fx f, Map<String, Integer> verdicts) {
        String[] label = {"NONE", "PASS", "FAIL", "NOT TESTED", "PENDING"};
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, Integer> e : verdicts.entrySet()) {
            int v = e.getValue();
            String text = v >= 0 && v < label.length ? label[v] : "NO FUNCTION";
            LOG.info("PARITY|PACK|{}|{}", e.getKey(), text);
            if (v == 2) failed.add(e.getKey());
        }
        run(f.server, f.avatars.get(0).createCommandSourceStack().withPermission(4), "reset");
        need(failed.isEmpty(), "the pack's own check functions reported FAIL for: " + failed);
        f.finish();
    }
}
