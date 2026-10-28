"""Static source contracts of the visible-name + G5-recovery milestone (read-only checks of repository files).

They pin the promises that cannot be exercised without Minecraft: the GameProfile / identity code is untouched, the name feature only
changes the CLIENT display name, and the G5 harness never resets journals, never defaults to the live database and keeps MAX_ACTORS.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "forge-mod" / "src" / "main" / "java" / "io" / "github" / "sirp00m" / "npcortex"
SCRIPTS = ROOT / "scripts"
PACK = ROOT / "integrations" / "datapacks" / "NPCortex_G5" / "data" / "npcortex_g5" / "functions"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class VisibleNameContract(unittest.TestCase):
    def test_game_profile_identity_is_untouched(self):
        runtime = text(JAVA / "C1P0Runtime.java")
        self.assertIn("new GameProfile(entry.profileId(), entry.profileName())", runtime)
        identity = text(JAVA / "C1IdentityData.java")
        self.assertIn('String profileName = "N" + name.substring(0, Math.min(8, name.length()))', identity)
        self.assertIn('+ "_" + profile.toString().substring(0, 6);', identity)

    def test_name_feature_never_renames_the_profile_or_the_entity(self):
        for name in ("NpcNameClient", "NpcNameNetwork", "NpcNameMap", "NpcNameCodec"):
            source = text(JAVA / f"{name}.java")
            code = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
            for forbidden in ("setCustomName", "new GameProfile", "setCustomNameVisible", "getGameProfile().getName", "ClientboundPlayerInfoUpdatePacket", "setName("):
                self.assertNotIn(forbidden, code, f"{name} must not use {forbidden}")

    def test_client_handler_is_scoped(self):
        client = text(JAVA / "NpcNameClient.java")
        self.assertIn("value = Dist.CLIENT", client)
        self.assertIn("PlayerEvent.NameFormat", client)
        self.assertRegex(client, r"if \(player == null \|\| !player\.level\(\)\.isClientSide\(\)\) return;")
        self.assertRegex(client, r"String name = NAMES\.nameFor\(player\.getUUID\(\)\);\s*if \(name != null\) event\.setDisplayname")
        self.assertIn("ClientPlayerNetworkEvent.LoggingOut", client)
        self.assertIn("ClientPlayerNetworkEvent.LoggingIn", client)

    def test_separate_channel_and_c1_sync_protocol_unchanged(self):
        names = text(JAVA / "NpcNameNetwork.java")
        self.assertIn('"names-1"', names)
        self.assertIn('new ResourceLocation(NPCortex.MOD_ID, "names")', names)
        self.assertIn("NetworkDirection.PLAY_TO_CLIENT", names)
        self.assertNotIn("PLAY_TO_SERVER", names)  # the client can never send a name to the server
        c1 = text(JAVA / "C1P0Network.java")
        self.assertIn('private static final String VERSION = "c1-r1-death-1";', c1)
        self.assertNotIn("NpcName", c1)

    def test_lifecycle_hooks_cover_login_rejoin_respawn_dimension_and_spawn(self):
        main = text(JAVA / "NPCortex.java")
        for hook in ("NpcNameNetwork::onLogin", "NpcNameNetwork::onRespawn", "NpcNameNetwork::onDimensionChange", "NpcNameNetwork.register()"):
            self.assertIn(hook, main)
        runtime = text(JAVA / "C1P0Runtime.java")
        announce, spawn = runtime.index("NpcNameNetwork.announce(server, entry);"), runtime.index("new C1PlayerAvatar(server, level,")
        self.assertLess(announce, spawn, "the name must be announced before the entity can be created on any client")
        names = text(JAVA / "NpcNameNetwork.java")
        self.assertIn("realPlayer(player)", names)  # avatars never receive name packets

    def test_only_logical_c1_names_are_sent(self):
        names = text(JAVA / "NpcNameNetwork.java")
        self.assertIn("names.put(entry.profileId(), entry.character())", names)
        self.assertIn("NpcNameCodec.validName(entry.character())", names)


class G5HarnessContract(unittest.TestCase):
    def test_collector_has_no_default_database(self):
        collector = text(SCRIPTS / "collect-g5-evidence.ps1")
        self.assertNotIn("backend/data/npcortex.sqlite3", collector)
        self.assertIn("Test-G5Isolation", collector)
        self.assertLess(collector.index("Test-G5Isolation"), collector.index("New-Item -ItemType Directory -Force -Path (Join-Path $stage $d)"))

    def test_nothing_in_the_harness_resets_or_repairs_a_journal(self):
        for path in list(SCRIPTS.glob("*g5*")) + list(PACK.rglob("*.mcfunction")):
            if path.suffix not in (".ps1", ".mcfunction", ".js") or path.name.startswith("test-"):
                continue  # test scripts build and delete THEIR OWN temp fixtures; the harness proper is what must never edit a database
            code = "\n".join(line for line in text(path).splitlines() if not line.lstrip().startswith(("#", "//")))
            self.assertNotRegex(code, r"c2 reset", f"{path.name} must never issue c2 reset")
            self.assertNotRegex(code, r"DELETE FROM|DROP TABLE|UPDATE c2_|Remove-Item[^\n]*sqlite", f"{path.name} must not edit databases")

    def test_fresh_lane_identities_everywhere(self):
        for path in list(SCRIPTS.glob("*g5*")) + list(SCRIPTS.glob("*.js")) + list(PACK.rglob("*.mcfunction")):
            if path.name == "test-g5-isolation.ps1" or path.suffix not in (".ps1", ".js", ".mcfunction"):
                continue
            body = text(path)
            self.assertNotIn("g5n0", body, path.name)
            self.assertNotIn("g5n10", body, path.name)
        self.assertIn("g5a{0:d2}", text(SCRIPTS / "g5-common.ps1"))

    def test_cancel_now_snapshots_in_the_cancel_tick_and_never_relies_on_a_function_status_echo(self):
        cancel = text(PACK / "lib" / "cancel_now.mcfunction")
        self.assertLess(cancel.index("m03 snapshot yui"), cancel.index("c2 cancel yui"))
        for path in PACK.rglob("*.mcfunction"):
            code = "\n".join(line for line in text(path).splitlines() if not line.lstrip().startswith("#"))
            self.assertNotIn("c2 status", code, f"{path.name}: command feedback of a function is suppressed by vanilla (zero C2= lines in the Rev5 log)")

    def test_p10_cancel_cannot_land_after_completion(self):
        # revision 6: the cancel is dispatched by the per-tick watch only while the NPC is >= 25 blocks from the target
        watch = text(PACK / "lib" / "p10_watch.mcfunction")
        self.assertRegex(watch, r"positioned 152\.5 -60 2\.5 unless entity @s\[distance=\.\.25\] run function npcortex_g5:lib/p10_cancel")
        self.assertIn("navlong yui 152 -60 2", text(PACK / "ph" / "p10_cancel_quiescing.mcfunction"))
        collector = text(SCRIPTS / "collect-g5-evidence.ps1")
        for needed in ("cancellation_safety", "leg_quiescing_coverage", "LEG_QUIESCING race coverage"):
            self.assertIn(needed, collector)
        self.assertIn("'UNVERIFIED'", collector)

    def test_gate_go_is_not_an_acceptance_verdict(self):
        gate = text(SCRIPTS / "g5-gate.ps1")
        self.assertIn("SAFE_PROGRESSION_ONLY_NOT_AN_ACCEPTANCE_VERDICT", gate)
        self.assertIn("It is never an acceptance verdict", gate)
        self.assertIn("preflight $($script:PreflightState)", gate)

    def test_max_actors_is_still_ten_and_production_c2_files_are_not_part_of_the_harness(self):
        self.assertIn("MAX_ACTORS = 10;", text(JAVA / "C2AgentRuntime.java"))
        self.assertIn("MAX_ACTORS = 10;", text(JAVA / "C2FollowPolicy.java"))
        # the only C2 production edit of the Character Profile milestone is the registration payload/status text; the scheduler stays FIFO-free P0
        self.assertIn("P0 explicitly rejects contention", text(JAVA / "C2Scheduler.java"))


class Rev6Contract(unittest.TestCase):
    """G5 Rev6: D1 diagnostics are log-only, the TAB name is server-side display only, p10 is bounded, the collector is hardened and deferred items stay untouched."""

    # sha256 of C2AgentRuntime.java (LF-normalised) BEFORE the two D1 log statements were added. With the D1 lines removed the file must be byte-identical:
    # that is the proof that the diagnostics changed no behaviour. A LATER milestone that edits C2AgentRuntime deliberately updates this baseline in its own review.
    C2_BASELINE_SHA256 = "7ecded071f9c1fda51b450d59af7391e04de740d19312965ad4d738acf4cabf0"

    def test_d1_diagnostics_are_log_only_and_the_rest_of_c2_is_byte_identical(self):
        import hashlib
        source = text(JAVA / "C2AgentRuntime.java").replace("\r\n", "\n")
        lines = source.split("\n")
        d1 = [line for line in lines if "C2Diagnostics." in line]
        self.assertEqual(len(d1), 2, "exactly two D1 statements")
        self.assertTrue(all(line.strip().startswith('LOG.info("{}",C2Diagnostics.') for line in d1))
        without = "\n".join(line for line in lines if "C2Diagnostics." not in line)
        self.assertEqual(hashlib.sha256(without.encode("utf-8")).hexdigest(), self.C2_BASELINE_SHA256, "C2AgentRuntime changed beyond the two D1 log lines")
        # D1-B: inside cancel(), after the active run is resolved and BEFORE any mutation
        cancel = source[source.index("static String cancel(MinecraftServer server, String name) {"):]
        cancel = cancel[:cancel.index("\n    }\n")]
        self.assertLess(cancel.index("if (run == null)"), cancel.index("C2Diagnostics.cancel("))
        for mutation in ("C1P1MotionRuntime.stop(", "run.replanning.stop()", "ACTIVE.release(", "C2CancellationRecovery.begin(", "write(run,"):
            self.assertLess(cancel.index("C2Diagnostics.cancel("), cancel.index(mutation), f"D1-B must precede {mutation}")
        # D1-A: inside result(), after the ownership guard and BEFORE the ACTION_FINISHED event / journal write / terminal transition
        result = source[source.index("private static void result(Run run,boolean succeeded,String detail) {"):]
        result = result[:result.index("\n    }\n")]
        self.assertLess(result.index("ACTIVE.get(run.actor)!=run"), result.index("C2Diagnostics.result("))
        for later in ("EVENTS.publish(", 'write(run,"RESULT_PENDING"', "send(run,"):
            self.assertLess(result.index("C2Diagnostics.result("), result.index(later), f"D1-A must precede {later}")
        helper = text(JAVA / "C2Diagnostics.java")
        code = re.sub(r"/\*.*?\*/|//[^\n]*", "", helper, flags=re.S)
        self.assertEqual(sorted(set(re.findall(r"^import ([\w.]+);", code, flags=re.M))), ["java.util.UUID"], "the formatter touches no Minecraft/C2 state")
        self.assertNotRegex(code, r"\bstatic\s+(?!final)\w+(<[^>]*>)?\s+\w+\s*(=|;)", "no mutable static state")

    def test_deferred_items_are_untouched(self):
        m03 = text(JAVA / "CPlusM03Runtime.java")
        self.assertEqual(m03.count("tickStart"), 3, "the M03 first-sample defect is deferred: tickStart is not repaired in Rev6")
        self.assertIn("if(e.phase==TickEvent.Phase.START){s.tickStart=System.nanoTime();return;}", m03)
        self.assertIn("MAX_ACTORS = 10;", text(JAVA / "C2AgentRuntime.java"))
        collector = text(SCRIPTS / "collect-g5-evidence.ps1")
        self.assertIn("MEASURED_NOT_ACCEPTED", collector)
        self.assertNotRegex(collector, r"tick_event_max_ms\w*\s*/|\$pMax\s*/|\$pMean\s*-\s*[$\d(]", "contaminated mean/max are never recalculated")

    def test_tab_name_is_server_side_display_only(self):
        tab = text(JAVA / "NpcTabName.java")
        code = re.sub(r"/\*.*?\*/|//[^\n]*", "", tab, flags=re.S)
        self.assertIn("PlayerEvent.TabListNameFormat", code)
        self.assertIn("event.getEntity() instanceof C1PlayerAvatar", code)          # real players are never matched
        self.assertIn("NpcNameCodec.validName(entry.character())", code)            # the existing name-validation contract
        self.assertIn("catch (RuntimeException ex)", code)                          # falls back to the profile name
        for forbidden in ("refreshTabListName", "new GameProfile", "getGameProfile().getName", "setCustomName", "ClientboundPlayerInfo", "setDisplayName(Component.literal(avatar", "PacketDistributor", "listed"):
            self.assertNotIn(forbidden, code, f"NpcTabName must not use {forbidden}")
        self.assertIn("MinecraftForge.EVENT_BUS.addListener(NpcTabName::onTabListName);", text(JAVA / "NPCortex.java"))
        # nothing else in the name pipeline changed: GameProfile identity, the C1 sync channel and the name channel version
        self.assertIn("new GameProfile(entry.profileId(), entry.profileName())", text(JAVA / "C1P0Runtime.java"))
        self.assertIn('"names-1"', text(JAVA / "NpcNameNetwork.java"))
        self.assertNotIn("TabList", text(JAVA / "C1P0Runtime.java"))

    def test_p10_is_bounded_and_dispatches_one_cancel_per_attempt(self):
        phase = text(PACK / "ph" / "p10_cancel_quiescing.mcfunction")
        self.assertEqual(sorted(set(re.findall(r"NOTE attempt (\d)$", phase, flags=re.M))), ["1", "2", "3", "4", "5"])
        self.assertNotIn("NOTE attempt 6", phase)
        self.assertEqual(len(re.findall(r"if score #att g5 matches \.\.4 if score #result g5 matches \d+ run function npcortex_g5:lib/p10_next", phase)), 3, "the next attempt starts only while #att <= 4")
        self.assertRegex(phase, r"#tick g5 matches 150\.\. .*set #result g5 2", "bounded per-attempt timeout")
        cancel = text(PACK / "lib" / "p10_cancel.mcfunction")
        self.assertEqual(cancel.count("c2 cancel yui"), 1)
        self.assertLess(cancel.index("scoreboard players set #w g5 0"), cancel.index("c2 cancel yui"))
        self.assertLess(cancel.index("m03 snapshot yui"), cancel.index("c2 cancel yui"))
        timeout = text(PACK / "lib" / "p10_timeout.mcfunction")
        self.assertEqual(timeout.count("c2 cancel yui"), 1)
        self.assertIn("NOTE cleanup_cancel", timeout)
        watch = text(PACK / "lib" / "p10_watch.mcfunction")
        self.assertEqual(watch.count("schedule function npcortex_g5:lib/p10_watch 1t replace"), 1)
        self.assertIn("execute if score #w g5 matches 1 run schedule function npcortex_g5:lib/p10_watch 1t replace", watch)
        for guard in ("if score #w g5 matches 1", "if score #cancelled g5 matches 0", "if score #qrun g5 matches 10..", "unless entity @s[distance=..25]"):
            self.assertIn(guard, watch)
        self.assertEqual(len(re.findall(r"Pos\[\d\] 1000", watch)), 4, "milli-block position, both selector kinds")
        self.assertIn("schedule clear npcortex_g5:lib/p10_watch", text(PACK / "setup.mcfunction"))
        self.assertIn("schedule clear npcortex_g5:lib/p10_watch", text(PACK / "stop.mcfunction"))
        arm = text(PACK / "lib" / "p10_arm.mcfunction")
        self.assertIn("#w g5 1", arm)

    def test_p18_stops_for_the_owner_typed_finalize_and_never_finalizes_by_itself(self):
        phase = text(PACK / "ph" / "p18_regress_e3_taskmove.mcfunction")
        code = "\n".join(line for line in phase.splitlines() if not line.lstrip().startswith("#"))
        self.assertIn("taskmovefinalize yui", code)
        self.assertIn("taskmovestatus yui", code)
        self.assertIn("MOVED_AWAITING_TYPED_FINALIZE", code)
        self.assertNotRegex(code, r"run npcortex debug cplus taskmove", "the pack never types the finalize itself: its answer would be suppressed")
        collector = text(SCRIPTS / "collect-g5-evidence.ps1")
        for needed in ("durable ACK; actor fence released", "cplus_e3_g3_terminals", "G3 durable terminal recorded", "another task"):
            self.assertIn(needed, collector)
        self.assertIn("finalizeSeen", text(SCRIPTS / "g5-gate.ps1"))

    def test_p08_reason_comes_only_from_the_production_diagnostic(self):
        collector = text(SCRIPTS / "collect-g5-evidence.ps1")
        self.assertIn("NPCortex C2 diag result actor=", collector)
        self.assertIn("NPCortex C2 diag cancel actor=", collector)
        self.assertIn("scenario expectation not met", collector)
        self.assertIn("no D1 diagnostic", collector)

    def test_collector_hardening_contracts(self):
        collector = text(SCRIPTS / "collect-g5-evidence.ps1")
        self.assertNotIn("[IO.File]::ReadAllText(", collector, "every read of a possibly-open file goes through Read-G5Shared")
        self.assertNotIn("[IO.File]::ReadAllLines(", collector)
        common = text(SCRIPTS / "g5-isolation.ps1")
        self.assertIn("[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete", common)
        self.assertIn("function Resolve-G5Path", common)
        self.assertIn("(Get-Location).ProviderPath", common)
        self.assertIn("'BackendLog'", collector)                                   # the relative -BackendLog is resolved by Resolve-G5Path
        self.assertIn("backend-run.json", collector)
        self.assertIn("backend-stop.json", collector)
        self.assertIn("[string]$r.run_id -eq [string]$iso.marker.run_id", collector)  # run-scoped gate evidence
        self.assertIn("first_sample_contamination", collector)
        self.assertIn("$CollectorVersion = 'rev6.0'", collector)
        gate = text(SCRIPTS / "g5-gate.ps1")
        self.assertIn("run_id = $gateRunId", gate)
        self.assertIn("SAFE_PROGRESSION_ONLY_NOT_AN_ACCEPTANCE_VERDICT", gate)
        launcher = text(SCRIPTS / "start-g5-backend.ps1")
        self.assertIn("health_before_stop", launcher)

    def test_p11_uses_the_checkpoint_correlated_snapshot(self):
        collector = text(SCRIPTS / "collect-g5-evidence.ps1")
        self.assertNotRegex(collector, r"-le 1\.5 \}\) \| Select-Object -First 1\s*\n\s*if \(\$atC -and \$atC\.state -in 'MOVING'")
        self.assertRegex(collector, r"Sort-Object @\{ Expression = \{ \[math\]::Abs\(\(\$_\.t - \$cp\.t\)\.TotalSeconds\) \}; Ascending = \$true \}")
        self.assertIn("-le 1.5 -and $_.goal -and ($own -contains $_.goal)", collector)   # same +-1.5 s window, plus the goal correlation; the rule is not loosened
        self.assertTrue((SCRIPTS / "fixtures" / "m03-real-p11-rev5").is_dir())
        self.assertEqual(len(list((SCRIPTS / "fixtures" / "m03-real-p11-rev5").glob("*.json"))), 2)


if __name__ == "__main__":
    unittest.main()
