"""G-script: scripts/register-npc-profile.ps1 against a real backend on a free port with a temp database."""
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
from threading import Thread
import unittest
from unittest.mock import Mock, patch
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.server import make_http_server
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.world_store import WorldStore

SCRIPT = ROOT / "scripts" / "register-npc-profile.ps1"
POWERSHELL = shutil.which("powershell")
TOKEN = "s" * 40
THAI = "สวัสดี ฉันคือเรกะ 😀 日本語"


@unittest.skipUnless(POWERSHELL, "Windows PowerShell is not available")
class RegisterScriptTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.tmp.name)
        cls.db = cls.dir / "script-test.sqlite3"
        provider = Mock()
        provider.chat.return_value = "ok"
        service = ChatService(ROOT / "character-packs", "fake", provider, SessionStore(),
                              world_store=WorldStore(cls.db))
        cls.env_patch = patch.dict(os.environ, {"NPCORTEX_CPLUS_F1_TOKEN": TOKEN})
        cls.env_patch.start()
        cls.httpd = make_http_server(service)
        cls.thread = Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.httpd.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=3)
        cls.env_patch.stop()
        cls.tmp.cleanup()

    def run_script(self, *args, token=TOKEN, extra_env=None, base=None):
        env = {k: v for k, v in os.environ.items() if not k.startswith("NPCORTEX_CPLUS_F1")}
        if token is not None:
            env["NPCORTEX_CPLUS_F1_TOKEN"] = token
        env.update(extra_env or {})
        command = [POWERSHELL, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(SCRIPT),
                   "-BaseUrl", base or self.url, *args]
        done = subprocess.run(command, capture_output=True, env=env, timeout=120)
        out = done.stdout.decode("utf-8", "replace")
        err = done.stderr.decode("utf-8", "replace")
        for text in (out, err):
            self.assertNotIn(TOKEN, text, "the credential must never be printed")
        return done.returncode, out, err

    def profile_file(self, body, name="profile.json"):
        path = self.dir / f"{uuid4()}-{name}"
        path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        return str(path)

    def rows(self, sql, *params):
        db = sqlite3.connect(self.db)
        try:
            return db.execute(sql, params).fetchall()
        finally:
            db.close()

    def register(self, body, actor=None, world=None, apply=True, dimension="minecraft:overworld"):
        actor = actor or str(uuid4())
        world = world or str(uuid4())
        args = ["-Action", "Register", "-ActorId", actor, "-WorldId", world, "-Dimension", dimension,
                "-ProfileFile", self.profile_file(body)]
        if apply:
            args.append("-Apply")
        return actor, world, self.run_script(*args)

    STRUCTURED = {"display_name": "Reika", "sections": {"personality": THAI, "speech_style": "Formal.", "backstory": "Snow."}}

    def test_dry_run_sends_nothing(self):
        before = self.rows("SELECT COUNT(*) FROM actors")[0][0]
        actor, world, (code, out, err) = self.register(self.STRUCTURED, apply=False)
        self.assertEqual(code, 0, err)
        self.assertIn("DRY RUN", out)
        self.assertIn(actor, out)
        self.assertEqual(self.rows("SELECT COUNT(*) FROM actors")[0][0], before)
        self.assertEqual(self.rows("SELECT COUNT(*) FROM worlds WHERE world_id=?", world)[0][0], 0)

    def test_apply_registers_world_actor_and_profile_with_unicode(self):
        actor, world, (code, out, err) = self.register(self.STRUCTURED)
        self.assertEqual(code, 0, out + err)
        self.assertIn("REGISTERED revision 1", out)
        self.assertEqual(self.rows("SELECT name FROM worlds WHERE world_id=?", world)[0][0], f"NPCortex-C1-{world}")
        row = self.rows("SELECT revision,personality,source,character_id FROM character_profiles p JOIN actors a USING(actor_id) "
                        "WHERE actor_id=?", actor)[0]
        self.assertEqual((row[0], row[1], row[2], row[3]), (1, THAI, "inline", "@profile"))

    def test_apply_is_idempotent_and_conflicting_replay_is_reported(self):
        actor, world, (code, out, err) = self.register(self.STRUCTURED)
        self.assertEqual(code, 0, err)
        again = self.run_script("-Action", "Register", "-ActorId", actor, "-WorldId", world, "-ProfileFile",
                                self.profile_file(self.STRUCTURED), "-Apply")
        self.assertEqual(again[0], 0, again[1] + again[2])
        other = dict(self.STRUCTURED, display_name="Different")
        clash = self.run_script("-Action", "Register", "-ActorId", actor, "-WorldId", world, "-ProfileFile",
                                self.profile_file(other), "-Apply")
        self.assertEqual(clash[0], 1)
        self.assertIn("HTTP 409", clash[2])
        self.assertEqual(self.rows("SELECT COUNT(*) FROM character_profiles WHERE actor_id=?", actor)[0][0], 1)

    def test_check_registered_and_not_registered(self):
        actor, world, (code, out, err) = self.register(self.STRUCTURED)
        self.assertEqual(code, 0, err)
        code, out, err = self.run_script("-Action", "Check", "-ActorId", actor)
        self.assertEqual(code, 0, err)
        self.assertIn("REGISTERED revision 1", out)
        code, out, err = self.run_script("-Action", "Check", "-ActorId", str(uuid4()))
        self.assertEqual(code, 2)
        self.assertIn("NOT REGISTERED", out)

    def test_export_reimports_to_the_same_content(self):
        actor, world, (code, out, err) = self.register(self.STRUCTURED)
        self.assertEqual(code, 0, err)
        exported = self.dir / f"{uuid4()}-export.json"
        code, out, err = self.run_script("-Action", "Export", "-ActorId", actor, "-OutFile", str(exported))
        self.assertEqual(code, 0, err)
        data = json.loads(exported.read_text(encoding="utf-8"))
        self.assertEqual(data["sections"]["personality"], THAI)
        self.assertNotIn("system_prompt", data)
        clone = str(uuid4())
        code, out, err = self.run_script("-Action", "Register", "-ActorId", clone, "-WorldId", world,
                                         "-ProfileFile", str(exported), "-Apply")
        self.assertEqual(code, 0, out + err)
        hashes = [self.rows("SELECT content_sha256 FROM character_profiles WHERE actor_id=?", a)[0][0] for a in (actor, clone)]
        self.assertEqual(hashes[0], hashes[1])
        code, out, err = self.run_script("-Action", "Export", "-ActorId", actor, "-OutFile", str(exported))
        self.assertEqual(code, 1)
        self.assertIn("already exists", err)
        code, out, err = self.run_script("-Action", "Export", "-ActorId", actor, "-OutFile", str(exported), "-Force")
        self.assertEqual(code, 0, err)

    def test_raw_profile_with_thai_prompt_round_trips(self):
        raw = {"display_name": "Mika", "system_prompt": THAI * 20}
        actor, world, (code, out, err) = self.register(raw)
        self.assertEqual(code, 0, out + err)
        self.assertEqual(self.rows("SELECT system_prompt FROM character_profiles WHERE actor_id=?", actor)[0][0], THAI * 20)

    def test_refuses_non_loopback_before_reading_any_credential(self):
        code, out, err = self.run_script("-Action", "Check", "-ActorId", str(uuid4()), base="http://192.0.2.7:8765",
                                         token=None, extra_env={"NPCORTEX_CPLUS_F1_SECRET_FILE": str(self.dir / "absent")})
        self.assertEqual(code, 1)
        self.assertIn("non-loopback", err)
        for bad in ("http://user:pw@127.0.0.1:1", "http://127.0.0.1:1/path", "ftp://127.0.0.1"):
            code, out, err = self.run_script("-Action", "Check", "-ActorId", str(uuid4()), base=bad)
            self.assertEqual(code, 1, bad)

    def test_oversize_request_refused_locally(self):
        before = self.rows("SELECT COUNT(*) FROM actors")[0][0]
        big = {"display_name": "Big", "system_prompt": "x" * 40000}
        actor, world, (code, out, err) = self.register(big)
        self.assertEqual(code, 1)
        self.assertIn("32768", err)
        self.assertEqual(self.rows("SELECT COUNT(*) FROM actors")[0][0], before)

    def test_backend_validation_error_is_shown_and_nothing_is_stored(self):
        before = self.rows("SELECT COUNT(*) FROM character_profiles")[0][0]
        actor, world, (code, out, err) = self.register({"display_name": "Bad", "system_prompt": ""})
        self.assertEqual(code, 1)
        self.assertIn("HTTP 400", err)
        self.assertEqual(self.rows("SELECT COUNT(*) FROM character_profiles")[0][0], before)
        self.assertEqual(self.rows("SELECT COUNT(*) FROM actors WHERE actor_id=?", actor)[0][0], 0)

    def test_pack_reference_files_are_refused(self):
        actor, world, (code, out, err) = self.register({"character_id": "yui", "display_name": "x"})
        self.assertEqual(code, 1)
        self.assertIn("not a pack reference", err)

    def test_wrong_credential_is_a_clear_403_without_leaking_either_secret(self):
        wrong = "w" * 40
        actor, world = str(uuid4()), str(uuid4())
        code, out, err = self.run_script("-Action", "Register", "-ActorId", actor, "-WorldId", world,
                                         "-ProfileFile", self.profile_file(self.STRUCTURED), "-Apply", token=wrong)
        self.assertEqual(code, 1)
        self.assertIn("403", err)
        self.assertNotIn(wrong, out + err)
        self.assertEqual(self.rows("SELECT COUNT(*) FROM actors WHERE actor_id=?", actor)[0][0], 0)

    def test_missing_secret_and_unreachable_backend(self):
        code, out, err = self.run_script("-Action", "Check", "-ActorId", str(uuid4()), token=None,
                                         extra_env={"NPCORTEX_CPLUS_F1_SECRET_FILE": str(self.dir / "absent.secret")})
        self.assertEqual(code, 1)
        self.assertIn("secret", err.lower())
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        code, out, err = self.run_script("-Action", "Check", "-ActorId", str(uuid4()), base=f"http://127.0.0.1:{port}")
        self.assertEqual(code, 1)
        self.assertIn("Cannot reach the backend", err)

    def test_secret_file_is_validated_and_used(self):
        good = self.dir / "good.secret"
        good.write_text(TOKEN.ljust(64, "a")[:64] if False else "a" * 64 + "\n", encoding="ascii")
        code, out, err = self.run_script("-Action", "Check", "-ActorId", str(uuid4()), token=None,
                                         extra_env={"NPCORTEX_CPLUS_F1_SECRET_FILE": str(good)})
        self.assertEqual(code, 1)  # a well-formed but different secret: the backend answers 403, not a local error
        self.assertIn("403", err)
        bad = self.dir / "bad.secret"
        bad.write_text("not-a-secret", encoding="ascii")
        code, out, err = self.run_script("-Action", "Check", "-ActorId", str(uuid4()), token=None,
                                         extra_env={"NPCORTEX_CPLUS_F1_SECRET_FILE": str(bad)})
        self.assertEqual(code, 1)
        self.assertIn("valid secret", err)

    def test_script_world_name_matches_the_mod_contract(self):
        script = SCRIPT.read_text(encoding="utf-8")
        java = (ROOT / "forge-mod" / "src" / "main" / "java" / "io" / "github" / "sirp00m" / "npcortex" / "C1IdentityData.java").read_text(encoding="utf-8")
        mod_prefix = re.search(r'backendWorldName\(UUID worldId\) \{\s*return "([^"]+)" \+ worldId', java).group(1)
        self.assertIn(f'"name":"{mod_prefix}\' + $WorldId', script.replace("NPCortex-C1-", "NPCortex-C1-"))
        c2 = (ROOT / "forge-mod" / "src" / "main" / "java" / "io" / "github" / "sirp00m" / "npcortex" / "C2AgentRuntime.java").read_text(encoding="utf-8")
        self.assertIn("C1IdentityData.backendWorldName(run.world)", c2)

    def test_script_never_writes_or_echoes_the_secret(self):
        text = SCRIPT.read_text(encoding="utf-8")
        for forbidden in ("Write-Host $script:Secret", "Write-Output $script:Secret", "Set-Content", "Out-File", "-Verbose"):
            self.assertNotIn(forbidden, text)
        self.assertIn("TryAddWithoutValidation('X-NPCortex-CPlus-Token', $script:Secret)", text)


if __name__ == "__main__":
    unittest.main()
