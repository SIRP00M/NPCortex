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
        # Stage 4 Option D (Owner-approved): exactly ONE admission-guard line was added to startInternal. Everything else is still the
        # Rev5 baseline: remove the two D1 log lines and that one guard line and the hash below must be unchanged.
        guard = [line for line in lines if "NativeEffectRuntime." in line]
        self.assertEqual([line.strip() for line in guard],
                         ['if (NativeEffectRuntime.pending(server, id.actorId())) return "C2 refused: a native world action owns this NPC";'],
                         "exactly one native admission guard in C2AgentRuntime")
        without = "\n".join(line for line in lines if "C2Diagnostics." not in line and "NativeEffectRuntime." not in line)
        self.assertEqual(hashlib.sha256(without.encode("utf-8")).hexdigest(), self.C2_BASELINE_SHA256, "C2AgentRuntime changed beyond the two D1 log lines and the one native guard")
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
        # Superseded by the Owner-approved D3 hardening (2026-10-28): the first-sample defect is now repaired and pinned by
        # FoundationHardeningContract + CPlusM03TickClockSelfTest. Only the metric contract is still pinned here.
        for metric in ("tick_event_mean_ms", "tick_event_p95_ms", "tick_event_max_ms", "MEASURED_NOT_ACCEPTED"):
            self.assertIn(metric, m03)
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


class E3PositionContract(unittest.TestCase):
    """E3/G3 arrival-point alignment: only the two verifiers change; C2, tolerances and the persistence gate stay put."""

    def test_both_verifiers_use_the_centre_helper_and_keep_the_3d_rule(self):
        java = text(JAVA / "CPlusE3G3TerminalContract.java")
        self.assertIn("static double[] arrivalPoint(int x,int y,int z) { return new double[]{x+0.5,y,z+0.5}; }", java)
        self.assertIn("arrivalPoint(coordinate(target,\"x\")", java)
        self.assertIn("if (distanceSq > tolerance*tolerance)", java)          # unchanged comparison
        py = text(ROOT / "backend" / "src" / "npcortex_backend" / "cplus_e3_g3_terminal.py")
        self.assertIn("return [target[0]+0.5, target[1], target[2]+0.5]", py)
        self.assertIn("zip(arrival_point(coords),live))>tolerance*tolerance", py)

    def test_tolerances_and_c2_arrival_are_not_touched(self):
        self.assertIn('body.addProperty("tolerance",0.75)', text(JAVA / "CPlusE3G2Runtime.java"))
        self.assertIn("tolerance < 0.20 || tolerance > 0.75", text(JAVA / "CPlusE3G3TerminalContract.java"))
        self.assertIn("static final double DEFAULT_TOLERANCE = 0.35;", text(JAVA / "C2MoveGeometry.java"))
        self.assertIn("double tx=target.x()+0.5, ty=target.footY(), tz=target.z()+0.5;", text(JAVA / "C2AgentRuntime.java"))

    def test_legacy_attempts_stay_fail_closed_without_a_schema_change(self):
        store = text(ROOT / "backend" / "src" / "npcortex_backend" / "cplus_e3_g2_motion.py")
        self.assertIn("SET state='HOLD',updated_at=? \"", store)                # the restart sweep that fences pre-fix rows
        self.assertIn("if done or row[12] not in ('C2_ACCEPTED','CANCEL_PENDING'):", store)
        self.assertNotIn("ALTER TABLE cplus_e3_g2_moves ADD", store)         # no column/version migration was added
        self.assertTrue((ROOT / "docs" / "contracts" / "e3_g3_position_vectors_v1.json").is_file())


class FoundationHardeningContract(unittest.TestCase):
    """D1/D2/D3 offline hardening: behaviour is tested elsewhere; these pin the wiring that makes it count."""

    def test_d1_host_rejection_drains_a_framed_post_body_and_keeps_every_status(self):
        server = text(ROOT / "backend" / "src" / "npcortex_backend" / "server.py")
        self.assertIn("if not self._host_ok(body_follows=True):", server)           # do_POST
        self.assertRegex(server, r"if body_follows:\s+self\._drain_framed_body\(\)\s+self\._error\(403, \"forbidden_host\"")
        self.assertIn('if self.headers.get("Transfer-Encoding") or len(lengths) != 1:', server)   # unframed bodies are never read
        self.assertIn("MAX_REJECT_DRAIN = 1024 * 1024", server)                     # the byte bound is unchanged
        self.assertIn("REJECT_DRAIN_TIMEOUT_SECONDS = 2", server)                   # and so is the time bound
        self.assertIn('return os.environ.get("NPCORTEX_REQUIRE_TOKEN", "")', server)  # default auth policy untouched

    def test_d2_every_java_selftest_runs_under_check_and_a_failure_fails_the_build(self):
        gradle = text(ROOT / "forge-mod" / "build.gradle")
        self.assertIn("include: '**/*SelfTest.java'", gradle)
        self.assertIn("tasks.named('check') { dependsOn tasks.named('verifySelfTests') }", gradle)
        self.assertNotIn("ignoreExitValue", gradle)                                  # JavaExec default: nonzero exit fails
        self.assertIn("Self-test without a main method cannot run", gradle)          # an unrunnable test is a build error, not a silent skip
        tests = list((ROOT / "forge-mod" / "src" / "test" / "java").rglob("*SelfTest.java"))
        self.assertGreaterEqual(len(tests), 64)
        for path in tests:
            self.assertIn("static void main", text(path), path.name)

    def test_d3_m03_pairs_tick_start_with_end(self):
        runtime = text(JAVA / "CPlusM03Runtime.java")
        self.assertNotIn("tickStart", runtime)
        self.assertIn("if(Double.isNaN(ms))return;", runtime)
        clock = text(JAVA / "CPlusM03TickClock.java")
        self.assertIn("if (!open) { unmatchedEnds++; return Double.NaN; }", clock)
        self.assertNotIn("net.minecraft", clock)                                     # stays pure so it is unit-testable


class Stage4ReadOnlyFoundationContract(unittest.TestCase):
    """Stage 4 read-only milestone: perception is observation only, reachable only through the read-only m04 tree, and the
    mutating skills stay declared-but-unimplemented. Source-shape pins that complement the Java behavioural self-tests."""
    NEW_JAVA = ("CPlusWorldPerception.java", "CPlusWorldPerceptionRuntime.java", "CommandOrigin.java")
    MUTATION_APIS = ("setBlock", "destroyBlock", "removeBlock", "setBlockAndUpdate", "useItemOn", "addFreshEntity", "setChunkForced",
                     "forceChunk", "addTicket", ".getChunk(", "level.getBlockState", "gameMode", "ItemEntity", "playerTouch")

    def test_new_sources_use_no_mutating_or_chunk_loading_api(self):
        for name in self.NEW_JAVA:
            code = re.sub(r"/\*.*?\*/|//[^\n]*", "", text(JAVA / name), flags=re.S)
            for forbidden in self.MUTATION_APIS:
                self.assertNotIn(forbidden, code, f"{name} must not use {forbidden}")
        self.assertIn("getChunkNow", text(JAVA / "CPlusWorldPerceptionRuntime.java"))

    def test_perception_is_reachable_only_through_the_read_only_m04_command(self):
        referencing = sorted(p.name for p in JAVA.rglob("*.java")
                             if "CPlusWorldPerception" in text(p) and not p.name.startswith("CPlusWorldPerception"))
        self.assertEqual(referencing, ["CPlusM04Runtime.java"], "only the m04 read-only command may call perception")
        server = text(ROOT / "backend" / "src" / "npcortex_backend" / "server.py")
        for word in ("perception", "observe_world", "world_observation"):
            self.assertNotIn(word, server, "no backend route may expose or accept observations in this milestone")
        agent = text(ROOT / "backend" / "src" / "npcortex_backend" / "agent_skills.py")
        self.assertNotIn("perception", agent)

    def test_mutating_skills_are_declared_but_still_have_no_forge_executor(self):
        agent = text(ROOT / "backend" / "src" / "npcortex_backend" / "agent_skills.py")
        declared = set(re.findall(r'^\s+"(move_to|break_block|place_block|pickup_item|use_item|equip_item)": "', agent, flags=re.M))
        self.assertEqual(declared, {"move_to", "break_block", "place_block", "pickup_item"})
        registry = text(JAVA / "C2SkillRegistry.java")
        self.assertIn("only move_to is advertised", registry)
        # The only Forge mentions are the read-only F2 evidence policy and the sampler that asks it; neither can act.
        # Stage 4 Option D (Owner-approved) added the NATIVE break_block / pickup_item path: its two pure files name the actions.
        # place_block, use_item and equip_item still appear in NO Forge file except the read-only F2 evidence policy and sampler,
        # and those two read-only files still cannot act.
        mentions = sorted(path.name for path in JAVA.rglob("*.java")
                          if any(f'"{name}"' in text(path) for name in ("break_block", "place_block", "pickup_item")))
        # PAP-G5 (Owner-approved): PlayerActionFlow is the pure place_block / use_block pipeline (separate ledger cplus_pap_*).
        self.assertEqual(mentions, ["CPlusF2ReadOnlyPolicy.java", "CPlusF2WorldSampler.java", "NativeActionPolicy.java", "NativeEffectFlow.java", "PlayerActionFlow.java"])
        for name in ("place_block", "use_item", "equip_item"):
            self.assertEqual(sorted(p.name for p in JAVA.rglob("*.java") if f'"{name}"' in text(p)),
                             ["CPlusF2ReadOnlyPolicy.java", "NativeActionPolicy.java", "PlayerActionFlow.java"] if name == "place_block" else [], name)
        for name in ("CPlusF2ReadOnlyPolicy.java", "CPlusF2WorldSampler.java"):
            code = text(next(JAVA.rglob(name)))
            for api in ("setBlock", "destroyBlock", "removeBlock", "useItemOn", "addFreshEntity", "gameMode", "playerTouch"):
                self.assertNotIn(api, code, f"{name} must not call {api}")
        policy = text(JAVA / "cplus" / "CPlusF2ReadOnlyPolicy.java")
        self.assertIn('new Decision(Verdict.UNKNOWN, "mutation_claim_adapter_unverified")', policy)   # a mutation is never ALLOWed

    def test_design_documents_and_contract_exist_with_the_required_content(self):
        stage4 = ROOT / "docs" / "stage4"
        ledger = text(stage4 / "PERMISSION_AND_WORLD_EFFECT_LEDGER_DESIGN.md")
        self.assertIn("Exactly-once world effects cannot be guaranteed and are not", ledger)
        for row in ("| 1 | Crash before the effect", "| 2 | Crash after the effect", "| 3 | Terminal committed, then chunk rollback",
                    "| 4 | Lost ACK", "| 5 | Cancel during execution", "| 6 | World changed externally"):
            self.assertIn(row, ledger)
        self.assertIn("**Verdict: UNKNOWN for authorization.**", text(stage4 / "COMMAND_ORIGIN_INVESTIGATION.md"))
        contract = text(ROOT / "docs" / "contracts" / "world_observation_v1.json")
        for outcome in ("OK", "PARTIAL_UNLOADED", "PARTIAL_BUDGET", "CANCELLED", "REJECTED"):
            self.assertIn(f'"{outcome}"', contract)


class Stage4NoopFoundationContract(unittest.TestCase):
    """Stage 4 mutation-safety foundation: a NO-OP world action only. Source-shape pins for what the Java and Python
    behavioural tests cannot see: no native mutation API anywhere in the new path, authority only from the packet sender."""
    NEW_JAVA = ("WorldActionPolicy.java", "WorldEffectFlow.java", "WorldEffectRuntime.java")
    MUTATION_APIS = ("setBlock", "destroyBlock", "removeBlock", "setBlockAndUpdate", "useItemOn", "addFreshEntity", "setChunkForced",
                     "forceChunk", "addTicket", ".getChunk(", "level.getBlockState", "gameMode", "playerTouch", "setItem(",
                     "addItem", "removeItem", "setItemSlot", "kill(", "discard(", "teleportTo", "setDeltaMovement", "hurt(")
    BACKEND = ROOT / "backend" / "src" / "npcortex_backend"

    @staticmethod
    def code(path):
        return re.sub(r"/\*.*?\*/|//[^\n]*", "", text(path), flags=re.S)

    def test_new_forge_path_uses_no_mutating_or_chunk_loading_api(self):
        for name in self.NEW_JAVA:
            code = self.code(JAVA / name)
            for forbidden in self.MUTATION_APIS:
                self.assertNotIn(forbidden, code, f"{name} must not use {forbidden}")
        runtime = self.code(JAVA / "WorldEffectRuntime.java")
        self.assertIn("getChunkNow", runtime)
        self.assertNotIn("getBlockState(at", runtime)

    def test_pure_core_has_no_minecraft_dependency(self):
        for name in ("WorldActionPolicy.java", "WorldEffectFlow.java"):
            self.assertNotIn("net.minecraft", text(JAVA / name), f"{name} must stay pure Java")

    def test_only_the_noop_action_exists_anywhere_in_the_new_path(self):
        self.assertIn('Set.of("noop")', text(JAVA / "WorldActionPolicy.java"))
        ledger = text(self.BACKEND / "cplus_we_ledger.py")
        self.assertIn("CHECK (action_kind IN ('noop'))", ledger)
        self.assertIn("ACTIONS = ('noop',)", ledger)
        for name in ("break_block", "place_block", "pickup_item", "use_item", "equip_item"):
            self.assertNotIn(name, ledger)
            for java in self.NEW_JAVA:
                self.assertNotIn(f'"{name}"', text(JAVA / java))

    def test_requester_is_the_authenticated_packet_sender_only(self):
        proto = text(JAVA / "ManagerProtocol.java")
        self.assertIn("record NoopReq(long opId, String name, boolean reconcile) implements ToServer {}", proto)
        core = text(JAVA / "ManagerCore.java")
        self.assertIn("run(player, key, m, noop(player, m))", core)
        self.assertIn("host.worldAction(player, request.name(), request.reconcile())", core)
        self.assertIn("current.handle(sender.getUUID(), message)", text(JAVA / "CharacterManagerService.java"))
        self.assertIn("context.getSender()", text(JAVA / "ManagerNetwork.java"))
        flow = self.code(JAVA / "WorldEffectFlow.java")
        self.assertNotIn("getPlayer()", flow)
        self.assertNotIn("hasPermission", flow)
        self.assertNotIn("CommandSourceStack", self.code(JAVA / "WorldEffectRuntime.java"))

    def test_no_command_or_datapack_entry_point_reaches_the_world_action(self):
        referencing = sorted(p.name for p in JAVA.rglob("*.java")
                             if re.search(r"\bWorldEffect(Runtime|Flow)\b", text(p)) and not p.name.startswith("WorldEffect"))
        # Stage 4 Option D: the native runtime only READS the no-op pending flag (an ownership check); it never starts a no-op action.
        self.assertEqual(referencing, ["CPlusE3G2Runtime.java", "CharacterManagerService.java", "ManagerCore.java", "NPCortex.java", "NativeEffectRuntime.java"])
        self.assertEqual(re.findall(r"WorldEffect(?:Runtime|Flow)\.\w+", self.code(JAVA / "NativeEffectRuntime.java")), ["WorldEffectRuntime.pending"])
        # E3 G2 only READS the pending flag; NPCortex only registers the stop hook; the packet path is the sole starter.
        self.assertIn("WorldEffectRuntime.pending(server,actor)", text(JAVA / "CPlusE3G2Runtime.java"))
        self.assertNotIn("WorldEffectRuntime.request", text(JAVA / "CPlusE3G2Runtime.java"))
        self.assertNotIn("WorldEffectRuntime.request", text(JAVA / "NPCortex.java"))

    def test_policy_lives_in_an_owner_controlled_config_file_not_in_a_database_or_datapack(self):
        runtime = text(JAVA / "WorldEffectRuntime.java")
        self.assertIn('POLICY_FILE = "npcortex-world-actions.json"', runtime)
        self.assertIn("FMLPaths.CONFIGDIR", runtime)
        self.assertIn("LinkOption.NOFOLLOW_LINKS", runtime)

    def test_ledger_is_additive_and_never_rewrites_other_stores(self):
        ledger = text(self.BACKEND / "cplus_we_ledger.py")
        sql = re.sub(r'"""[\s\S]*?"""', "", ledger)
        for forbidden in ("ALTER TABLE", "DROP ", "DELETE FROM", "UPDATE cplus_e3", "UPDATE cplus_m04", "UPDATE cplus_e1",
                          "INSERT INTO cplus_e3", "INSERT INTO cplus_m04", "user_version"):
            self.assertNotIn(forbidden, sql)
        for table in re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)", ledger):
            self.assertTrue(table.startswith("cplus_we_"), table)

    def test_cross_store_fence_is_checked_in_both_directions_inside_the_write_transaction(self):
        g2 = text(self.BACKEND / "cplus_e3_g2_motion.py")
        self.assertIn("cplus_we_runs", g2)
        self.assertIn("cplus_m04_runs", g2)
        self.assertLess(g2.index("BEGIN IMMEDIATE"), g2.index("cplus_we_runs"))
        self.assertIn("cplus_we_runs", text(self.BACKEND / "cplus_m04_store.py"))
        ledger = text(self.BACKEND / "cplus_we_ledger.py")
        for table in ("cplus_e3_g2_moves", "cplus_e1_tasks", "cplus_m04_runs"):
            self.assertIn(table, ledger)
        self.assertGreaterEqual(ledger.count("BEGIN IMMEDIATE"), 7)

    def test_backend_routes_are_token_protected_and_grant_no_authority(self):
        server = text(self.BACKEND / "server.py")
        self.assertRegex(server, r"if self\.path in WE_ROUTES:\s+if not self\._token_ok\(\):")
        self.assertIn("WE_ROUTES", server)
        ledger = text(self.BACKEND / "cplus_we_ledger.py")
        self.assertIn("the backend never grants authority", ledger)
        self.assertNotIn("hasPermission", ledger)

    def test_noop_success_is_never_reported_as_cancelled(self):
        flow = text(JAVA / "WorldEffectFlow.java")
        self.assertIn('"No-op complete: NO_EFFECT_VERIFIED', flow)
        self.assertIn('receipt.addProperty("result", "NO_EFFECT")', flow)
        ledger = text(self.BACKEND / "cplus_we_ledger.py")
        for state in ("NO_EFFECT_VERIFIED", "FAILED_NO_EFFECT", "CANCELLED_NO_EFFECT", "HOLD_IN_DOUBT"):
            self.assertIn(f"'{state}'", ledger)


class Stage4NativeEffectContract(unittest.TestCase):
    """Stage 4 Option D: native break_block / pickup_item. Source-shape pins for what the Java, GameTest and Python behavioural
    tests cannot see: one executor, no marker no call, no server-thread I/O, authority only from the packet sender, accepted
    persistence contracts untouched, nothing but break and pickup."""
    BACKEND = ROOT / "backend" / "src" / "npcortex_backend"
    PURE = ("NativeEffectFlow.java", "NativeActionPolicy.java", "MiningProgress.java")
    # LF-normalised sha256 of accepted files this milestone must NOT modify (C1 inventory persistence contract, outbox, identity).
    UNTOUCHED = {
        "C1InventoryJournal.java": "9373b3dd1e5a7d901c06d5eb0f3c022a75b841e0f35177280196ba51587c4cc3",
        "C1InventoryData.java": "4696a20f7ce0fa0a9808c4361b90946065be6a4606969c893a17ddbf861c670d",
        "CPlusOutbox.java": "824bbd916f1e516898fe339d1b1a80ae1780f1b1a31ba85a874b18e7e9f58eff",
        "C1IdentityData.java": "3184a6a8987ce6303478b0c21f0f26d0fb9927954c5b8ed1b8051212de731b2d",
    }

    @staticmethod
    def code(path):
        return re.sub(r"/\*.*?\*/|//[^\n]*", "", text(path), flags=re.S)

    def test_accepted_persistence_contracts_are_byte_identical(self):
        import hashlib
        for name, digest in self.UNTOUCHED.items():
            body = text(JAVA / name).replace("\r\n", "\n")
            self.assertEqual(hashlib.sha256(body.encode("utf-8")).hexdigest(), digest, f"{name} is an accepted contract and must not change")

    def test_pure_core_has_no_minecraft_or_io_dependency(self):
        for name in self.PURE:
            code = self.code(JAVA / name)
            self.assertNotIn("net.minecraft", code, f"{name} must stay pure Java")
            for forbidden in ("java.nio.file", "java.net.", "HttpClient", "Thread.sleep", "synchronized", ".join()", ".get()", ".getNow(", "Files."):
                self.assertNotIn(forbidden, code, f"{name} must not use {forbidden}: no file, network or blocking wait on the server thread")

    def test_the_native_calls_exist_exactly_once_and_only_in_the_runtime(self):
        owners = {"destroyBlock(": [], "playerTouch(": []}
        for path in JAVA.rglob("*.java"):
            code = self.code(path)
            for api in owners:
                if api in code:
                    owners[api].append(path.name)
        self.assertEqual(owners, {"destroyBlock(": ["NativeEffectRuntime.java"], "playerTouch(": ["NativeEffectRuntime.java"]})
        runtime = self.code(JAVA / "NativeEffectRuntime.java")
        self.assertEqual(runtime.count(".destroyBlock("), 1)
        self.assertEqual(runtime.count(".playerTouch("), 1)
        for forbidden in ("setBlock(", "removeBlock(", "useItemOn", "addFreshEntity", "setItemSlot", "equip"):
            self.assertNotIn(forbidden, runtime, f"the native runtime has no {forbidden}: only break and pickup exist")
        self.assertNotRegex(runtime, r"\.(?:place|use|useItem|placeBlock|startUsingItem)\(", "no place or use call: only break and pickup exist")

    def test_no_marker_no_executor(self):
        flow = self.code(JAVA / "NativeEffectFlow.java")
        self.assertIn("private Started(UUID task)", flow)
        self.assertEqual(flow.count("new Started("), 1, "the only factory of the proof token")
        self.assertRegex(flow, r"static Started startAck\(JsonObject reply, JsonObject sent, UUID task\)")
        self.assertIn("BreakOutcome executeBreak(Started marker,", flow)
        self.assertIn("PickupOutcome executePickup(Started marker,", flow)
        runtime = self.code(JAVA / "NativeEffectRuntime.java")
        self.assertEqual(runtime.count('if (marker == null) throw new IllegalStateException("no durable EXECUTION_STARTED marker");'), 2)
        # the executor is reached from exactly one place per action, after the final precondition, inside the START reply handler
        self.assertEqual(flow.count("env.executeBreak("), 1)
        self.assertEqual(flow.count("env.executePickup("), 1)
        self.assertLess(flow.index("String why = finalPrecondition(a);"), flow.index("finishBreak(a, marker)"))
        self.assertIn("if (a.abort || env.tick() - a.startRequestedTick > START_WAIT_TICKS)", flow)

    def test_the_server_thread_does_no_file_or_network_io(self):
        runtime = self.code(JAVA / "NativeEffectRuntime.java")
        poller = runtime[runtime.index("private static final class PolicyPoller"):runtime.index("private static final class ForgeEnv")]
        outside_poller = runtime.replace(poller, "")
        self.assertTrue("Files." not in outside_poller, "the policy file is read only by the background poller")
        self.assertEqual(outside_poller.count("CPlusLocalAuth.readToken()"), 1)
        send = outside_poller[outside_poller.index("private String send("):]
        self.assertLess(send.index("CPlusLocalAuth.readToken()"), send.index("}"), "the token file is read inside the worker-only send()")
        self.assertTrue("CompletableFuture.supplyAsync(" in outside_poller)
        scanned = outside_poller.replace("NPCortexConfig.BACKEND_URL.get()", "")           # a config value, not a future
        for forbidden in (".join()", ".get()", ".getNow(", "Thread.sleep", ".await("):
            self.assertTrue(forbidden not in scanned, f"NativeEffectRuntime must not use {forbidden}")
        self.assertTrue("server.execute(task)" in runtime)

    def test_native_actions_start_only_from_the_authenticated_manager_packet(self):
        referencing = sorted(p.name for p in JAVA.rglob("*.java")
                             if re.search(r"NativeEffectRuntime\.(preview|confirm|cancel|reconcile)\(", text(p)))
        # PAP-G5 (Owner-approved): the chat path is the second authenticated source. Its requester is the chat SENDER and it checks the
        # Owner allow-list before it does anything; no backend reply, command or model output reaches these calls.
        self.assertEqual(referencing, ["CharacterManagerService.java", "PlayerTaskRuntime.java"])
        task = text(JAVA / "PlayerTaskRuntime.java")
        self.assertIn("policy.allowsRequester(requester, id.dimension(), id.character())", task)
        self.assertIn("handleChat(p.getServer(), p.getUUID(),", task)
        proto = text(JAVA / "ManagerProtocol.java")
        self.assertIn("record NativeReq(long opId, String name, int action, String nonce) implements ToServer {}", proto)
        core = text(JAVA / "ManagerCore.java")
        self.assertIn("run(player, key, m, nativeAction(player, m))", core)
        self.assertIn("host.nativeAction(player, request.name(), request.action(), request.nonce())", core)
        flow = self.code(JAVA / "NativeEffectFlow.java")
        for forbidden in ("getPlayer()", "hasPermission", "CommandSourceStack"):
            self.assertNotIn(forbidden, flow)
        self.assertIn("Preview pv = previews.remove(previewKey(requester, npcName));", flow)
        # the backend can record and refuse; no backend reply is ever a command to Forge
        server = text(self.BACKEND / "server.py")
        self.assertRegex(server, r"if self\.path in WN_ROUTES:\s+if not self\._token_ok\(\):")
        store = text(self.BACKEND / "cplus_wn_store.py")
        self.assertIn("the backend never grants authority", store)

    def test_reverse_guards_are_in_place_in_the_accepted_systems(self):
        expect = {
            "C1P1InventoryRuntime.java": "NativeEffectRuntime.pendingProfile(server, avatar.getUUID())",
            "C1P1MotionRuntime.java": "NativeEffectRuntime.pendingProfile(server, avatar.getUUID())",
            "C2FollowRuntime.java": "NativeEffectRuntime.pending(server,id.actorId())",
            "C2AgentRuntime.java": "NativeEffectRuntime.pending(server, id.actorId())",
            "CPlusE3G2Runtime.java": "NativeEffectRuntime.pending(server,actor)",
            "WorldEffectRuntime.java": "NativeEffectRuntime.pending(server, a.actorId())",
        }
        for name, needle in expect.items():
            self.assertEqual(text(JAVA / name).count(needle), 1, f"{name}: one native guard")
        self.assertIn("WorldEffectRuntime.pending(server, a.actorId())", text(JAVA / "NativeEffectRuntime.java"))

    def test_only_break_and_pickup_exist(self):
        # PAP-G2/G5 (Owner-approved) added place_block / use_block to the Owner's allow-list file; the NATIVE LEDGER below stays break/pickup only.
        self.assertIn('ACTIONS = Set.of("break_block", "pickup_item", "place_block", "use_block")', text(JAVA / "NativeActionPolicy.java"))
        store = text(self.BACKEND / "cplus_wn_store.py")
        self.assertIn("ACTIONS = ('break_block', 'pickup_item')", store)
        self.assertIn("CHECK (action_kind IN ('break_block','pickup_item'))", store)
        for name in ("place_block", "use_item", "equip_item", "use_block"):
            self.assertNotIn(name, store)
            for java in self.PURE + ("NativeEffectRuntime.java",):
                if java == "NativeActionPolicy.java" and name in ("place_block", "use_block"):
                    continue                           # PAP-G5: the Owner allow-list file names the two new kinds (the ledger and the executor above do not)
                self.assertNotIn(f'"{name}"', text(JAVA / java))
        self.assertIn("CHECK (action_kind IN ('noop'))", text(self.BACKEND / "cplus_we_ledger.py"))     # the accepted no-op table is untouched

    def test_store_is_additive_and_atomic(self):
        store = text(self.BACKEND / "cplus_wn_store.py")
        sql = re.sub(r'"""[\s\S]*?"""', "", store)
        for forbidden in ("ALTER TABLE", "DROP ", "DELETE FROM", "UPDATE cplus_e3", "UPDATE cplus_m04", "UPDATE cplus_e1", "UPDATE cplus_we",
                          "INSERT INTO cplus_e3", "INSERT INTO cplus_m04", "INSERT INTO cplus_we", "user_version"):
            self.assertNotIn(forbidden, sql)
        for table in re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)", store):
            self.assertTrue(table.startswith("cplus_wn_"), table)
        for index in re.findall(r"CREATE (?:UNIQUE )?INDEX IF NOT EXISTS (\w+)", store):
            self.assertTrue(index.startswith("cplus_wn_"), index)
        self.assertGreaterEqual(store.count("BEGIN IMMEDIATE"), 12)
        self.assertIn("WHERE kind='block' AND state IN ('ACTIVE','HELD')", store)
        self.assertIn("WHERE kind='item' AND state IN ('ACTIVE','HELD')", store)
        self.assertIn("WHERE state IN ('RESERVED','EXECUTING','HOLD_IN_DOUBT')", store)

    def test_cross_store_fence_is_checked_in_both_directions(self):
        for name in ("cplus_e3_g2_motion.py", "cplus_m04_store.py", "cplus_we_ledger.py"):
            self.assertIn("cplus_wn_runs", text(self.BACKEND / name), name)
        store = text(self.BACKEND / "cplus_wn_store.py")
        for table in ("cplus_we_runs", "cplus_e3_g2_moves", "cplus_e1_tasks", "cplus_m04_runs"):
            self.assertIn(table, store)

    def test_transport_gives_critical_messages_their_own_bounded_lane(self):
        runtime = self.code(JAVA / "NativeEffectRuntime.java")
        self.assertIn("new ArrayBlockingQueue<>(NativeEffectFlow.SLOTS + 8)", runtime)
        self.assertNotIn("LinkedBlockingQueue", runtime)
        flow = self.code(JAVA / "NativeEffectFlow.java")
        self.assertIn("static final int SLOTS = 16;", flow)
        self.assertIn("slotsUsed >= SLOTS", flow)
        self.assertEqual(flow.count("slotsUsed++"), 1)
        transport = text(JAVA / "cplus" / "CPlusTransport.java")
        for route in ("bind", "reserve", "start", "finalize", "cancel", "hold", "repair", "reconcile", "status", "active"):
            self.assertIn(f'"/v1/cplus/wn/{route}"', transport)

    def test_build_gains_a_gametest_run_and_no_dependency(self):
        gradle = text(ROOT / "forge-mod" / "build.gradle")
        self.assertIn("gameTestServer {", gradle)
        self.assertIn("property 'forge.enabledGameTestNamespaces', (project.findProperty('gametestNamespace') ?: 'npcortex').toString()", gradle)
        dependencies = gradle[gradle.index("dependencies {"):]
        dependencies = dependencies[:dependencies.index("}")]
        self.assertEqual(re.findall(r"^\s+(\w+)\s", dependencies, flags=re.M), ["minecraft"], "no new dependency")


if __name__ == "__main__":
    unittest.main()
