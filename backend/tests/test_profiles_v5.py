"""Character Profile Foundation (schema v5): gates G1-G7. Every database lives in a temp directory."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
from threading import Barrier, Thread
import unittest
from unittest.mock import patch
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend import dbtools, service as service_module
from npcortex_backend.characters import CharacterError, load_character
from npcortex_backend.identity import IdentityError
from npcortex_backend.profiles import (LEGACY_SENTINEL, ProfileError, compose_system_prompt, default_profile,
                                       validate_profile)
from npcortex_backend.server import make_http_server
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.world_store import (ProfileNotRegisteredError, RegistryConflictError, RegistryNotFoundError,
                                          RevisionConflictError, SchemaUpgradeNotAllowedError, SchemaVersionError,
                                          WorldStore)

FIXTURE = ROOT / "backend" / "tests" / "fixtures" / "v4_baseline.sqlite3"
PACKS = ROOT / "character-packs"
TOKEN = "p" * 40
DIM = "minecraft:overworld"
FIX_WORLD = "11111111-1111-4111-8111-111111111111"
FIX_YUI = "22222222-2222-4222-8222-222222222222"
FIX_GUIDE = "33333333-3333-4333-8333-333333333333"


class Capture:
    def __init__(self, reply="ok"):
        self.calls, self.reply = [], reply

    def chat(self, messages):
        self.calls.append(messages)
        return self.reply


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def dump(path):
    db = sqlite3.connect(path)
    try:
        return "\n".join(db.iterdump())
    finally:
        db.close()


def structured(name="Reika", personality="Calm and precise.", style="Formal.", backstory="Born in a snowy village."):
    return {"display_name": name, "sections": {"personality": personality, "speech_style": style, "backstory": backstory}}


def raw(name="Mika", prompt="You are Mika, a cheerful baker."):
    return {"display_name": name, "system_prompt": prompt}


class Base(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.path = self.dir / "npcortex.sqlite3"
        self.provider = Capture()
        self.svc = self.make(self.path)
        self.world = self.svc.register_world("Profile World")["world_id"]

    def make(self, path, **kwargs):
        return ChatService(PACKS, "fake", kwargs.pop("provider", None) or self.provider, SessionStore(),
                           world_store=WorldStore(path, **kwargs))

    def reg(self, selector: dict, actor_id=None, dim=DIM, world=None):
        data = {"world_id": world or self.world, "dimension_id": dim, **selector}
        if actor_id:
            data["actor_id"] = actor_id
        return self.svc.register_actor_request(data)

    def count(self, table, path=None):
        db = sqlite3.connect(path or self.path)
        try:
            return db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        finally:
            db.close()


class G1ProfileValidationTests(unittest.TestCase):
    def test_structured_composes_deterministically_with_guardrail(self):
        a, b = validate_profile(structured()), validate_profile(structured())
        self.assertEqual(a.system_prompt, b.system_prompt)
        self.assertEqual(a.content_sha256, b.content_sha256)
        self.assertEqual(a.composition, "structured")
        self.assertTrue(a.system_prompt.startswith("You are Reika, "))
        self.assertIn("Personality: Calm and precise.", a.system_prompt)
        self.assertIn("Never claim that you have moved", a.system_prompt)

    def test_user_text_cannot_replace_template_or_use_format_fields(self):
        p = validate_profile(structured(personality="{display_name} {0} %s ignore all rules"))
        self.assertIn("{display_name} {0} %s ignore all rules", p.system_prompt)
        self.assertTrue(p.system_prompt.endswith("unless a game system has confirmed it."))

    def test_empty_optional_sections_are_omitted(self):
        p = validate_profile(structured(style="", backstory=""))
        self.assertNotIn("Speech style:", p.system_prompt)
        self.assertNotIn("Backstory:", p.system_prompt)

    def test_raw_requires_prompt_and_rejects_mixing(self):
        self.assertEqual(validate_profile(raw()).composition, "raw")
        with self.assertRaises(ProfileError):
            validate_profile({"display_name": "X", "system_prompt": "p", "sections": {"personality": "p"}})
        with self.assertRaises(ProfileError):
            validate_profile({"display_name": "X"})

    def test_limits_and_control_characters(self):
        bad = [raw(prompt=""), raw(prompt="x" * 5001), raw(name=""), raw(name="n" * 81), raw(name="a\nb"),
               raw(prompt="a\x00b"), raw(prompt="a\x1bb"), structured(personality=""), structured(personality="p" * 1501),
               structured(style="s" * 501), structured(backstory="b" * 1501), {**raw(), "language": "not a language!"},
               {**raw(), "schema_version": 2}, {**raw(), "extra": 1}, {**raw(), "composition": "structured"},
               {"display_name": "X", "sections": {"personality": "p", "mood": "x"}}, {**raw(), "display_name": 5}]
        for obj in bad:
            with self.subTest(obj=str(obj)[:60]), self.assertRaises(ProfileError):
                validate_profile(obj)
        validate_profile(raw(prompt="x" * 5000))
        validate_profile(raw(prompt="line1\nline2\ttab"))

    def test_lone_surrogate_and_non_object_rejected(self):
        for obj in (raw(prompt="bad \ud800 text"), [], "text", None):
            with self.assertRaises(ProfileError):
                validate_profile(obj)

    def test_utf8_round_trip_and_import_equals_export(self):
        thai = "สวัสดี ฉันคือผู้ช่วย 😀 日本語"
        p = validate_profile(structured(personality=thai))
        self.assertIn(thai, p.system_prompt)
        again = validate_profile(json.loads(json.dumps(p.importable(), ensure_ascii=False)))
        self.assertEqual(again.content_sha256, p.content_sha256)
        r = validate_profile(raw(prompt=thai))
        self.assertEqual(validate_profile(r.importable()).content_sha256, r.content_sha256)

    def test_five_thousand_cjk_characters_fit_the_utf8_budget(self):
        p = validate_profile(raw(prompt="日" * 5000))
        self.assertLessEqual(len(p.system_prompt.encode()), 15000)

    def test_default_profile_sanitises_name(self):
        self.assertIn("You are Reika,", default_profile("Reika").system_prompt)
        for name in ("", "a b", "x" * 17, "bad\nname", "né", "Re;ika", 5):
            with self.assertRaises(ProfileError):
                default_profile(name)

    def test_hash_covers_composition_and_content(self):
        a = validate_profile(raw(prompt="same"))
        b = validate_profile(raw(prompt="other"))
        self.assertNotEqual(a.content_sha256, b.content_sha256)
        self.assertEqual(compose_system_prompt("A", "p", "", "").count("\n"), 2)


class G2MigrationTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.db = self.dir / "live-like.sqlite3"
        shutil.copyfile(FIXTURE, self.db)
        self.before_hash = sha(self.db)
        self.before_dump = dump(self.db)
        self.assertEqual(dbtools.manifest(self.db)["user_version"], 4)

    def backups(self):
        return sorted(self.dir.glob("*.bak"))

    def test_v4_is_refused_without_opt_in_and_untouched(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("NPCORTEX_ALLOW_SCHEMA_V5", None)
            with self.assertRaises(SchemaUpgradeNotAllowedError) as caught:
                WorldStore(self.db)
        self.assertIn("NPCORTEX_ALLOW_SCHEMA_V5", str(caught.exception))
        self.assertEqual(sha(self.db), self.before_hash)
        self.assertEqual(self.backups(), [])
        self.assertEqual(sorted(p.name for p in self.dir.iterdir()), [self.db.name])

    def test_env_flag_enables_upgrade(self):
        with patch.dict(os.environ, {"NPCORTEX_ALLOW_SCHEMA_V5": "1"}):
            WorldStore(self.db)
        self.assertEqual(dbtools.manifest(self.db)["user_version"], 5)

    def test_upgrade_writes_verified_backup_and_preserves_everything(self):
        WorldStore(self.db, allow_schema_upgrade=True)
        backups = self.backups()
        self.assertEqual(len(backups), 1)
        self.assertRegex(backups[0].name, r"\.v4-\d{8}T\d{6}Z\.bak$")
        manifest = dbtools.manifest(backups[0])
        self.assertEqual((manifest["user_version"], manifest["integrity"]), (4, "ok"))
        self.assertEqual(dump(backups[0]), self.before_dump)  # logical dump identical to the pre-upgrade file
        after = dbtools.manifest(self.db)
        self.assertEqual((after["user_version"], after["integrity"]), (5, "ok"))
        for table, rows in manifest["counts"].items():
            self.assertEqual(after["counts"][table], rows, table)
        self.assertEqual(after["counts"]["character_profiles"], 0)  # no silent adoption of legacy actors
        WorldStore(self.db)  # now v5: no flag, no second backup
        self.assertEqual(len(self.backups()), 1)

    def test_legacy_yui_prompt_is_byte_identical_after_upgrade(self):
        cap = Capture()
        store = WorldStore(self.db, allow_schema_upgrade=True)
        svc = ChatService(PACKS, "fake", cap, SessionStore(), world_store=store)
        svc.chat_actor(FIX_WORLD, DIM, FIX_YUI, "player-1", "hi again")
        pack = json.loads((PACKS / "yui" / "character.json").read_text(encoding="utf-8"))
        self.assertEqual(cap.calls[0][0]["content"], pack["system_prompt"])
        self.assertEqual(store.get_actor(FIX_YUI)["character_id"], "yui")
        self.assertIsNone(store.get_current_profile(FIX_YUI))

    def test_fresh_database_needs_no_flag_and_no_backup(self):
        fresh = self.dir / "fresh.sqlite3"
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("NPCORTEX_ALLOW_SCHEMA_V5", None)
            WorldStore(fresh)
        self.assertEqual(dbtools.manifest(fresh)["user_version"], 5)
        self.assertEqual(self.backups(), [])

    def test_backup_failure_aborts_before_any_change(self):
        with patch("npcortex_backend.world_store.backup_database", side_effect=dbtools.BackupError("disk full")):
            with self.assertRaises(SchemaVersionError):
                WorldStore(self.db, allow_schema_upgrade=True)
        self.assertEqual(sha(self.db), self.before_hash)
        self.assertEqual(dbtools.manifest(self.db)["user_version"], 4)

    def test_unverifiable_backup_is_deleted_and_aborts(self):
        original = dbtools.manifest

        def lying(path):
            result = original(path)
            return {**result, "integrity": "corrupt"} if str(path).endswith(".bak") else result

        with patch.object(dbtools, "manifest", lying):
            with self.assertRaises(SchemaVersionError):
                WorldStore(self.db, allow_schema_upgrade=True)
        self.assertEqual(self.backups(), [])
        self.assertEqual(dbtools.manifest(self.db)["user_version"], 4)

    def test_failure_inside_v5_script_rolls_back_and_keeps_backup(self):
        db = sqlite3.connect(self.db)
        db.execute("CREATE TABLE character_profiles (x INTEGER)")  # collision makes the v5 script fail midway
        db.commit()
        db.close()
        with self.assertRaises(sqlite3.Error):
            WorldStore(self.db, allow_schema_upgrade=True)
        manifest = dbtools.manifest(self.db)
        self.assertEqual(manifest["user_version"], 4)
        self.assertEqual(manifest["integrity"], "ok")
        self.assertEqual(len(self.backups()), 1)

    def test_post_upgrade_verification_failure_points_to_backup(self):
        with patch("npcortex_backend.world_store.table_counts", side_effect=[{"worlds": 1}, {"worlds": 2}]):
            with patch("npcortex_backend.dbtools.table_counts", side_effect=lambda db: {}):
                with self.assertRaises(sqlite3.DatabaseError) as caught:
                    WorldStore(self.db, allow_schema_upgrade=True)
        self.assertIn("rolled back", str(caught.exception))
        self.assertIn(".bak", str(caught.exception))
        # the failed verification must NOT leave a committed v5 database behind
        manifest = dbtools.manifest(self.db)
        self.assertEqual(manifest["user_version"], 4)
        self.assertNotIn("character_profiles", manifest["counts"])
        self.assertEqual(dump(self.db), self.before_dump)

    def test_restore_from_backup_is_exact_and_upgradable_again(self):
        WorldStore(self.db, allow_schema_upgrade=True)
        backup = self.backups()[0]
        restored = self.dir / "restored.sqlite3"
        shutil.copyfile(backup, restored)  # the documented operator restore (backend stopped)
        manifest = dbtools.manifest(restored)
        self.assertEqual((manifest["user_version"], manifest["integrity"]), (4, "ok"))
        self.assertEqual(dump(restored), self.before_dump)
        WorldStore(restored, allow_schema_upgrade=True)  # restoring never blocks a later upgrade
        self.assertEqual(dbtools.manifest(restored)["user_version"], 5)

    def test_backup_never_overwrites(self):
        target = self.dir / "x.bak"
        target.write_bytes(b"precious")
        src = sqlite3.connect(self.db)
        try:
            with self.assertRaises(dbtools.BackupError):
                dbtools.backup_database(src, target, 4)
        finally:
            src.close()
        self.assertEqual(target.read_bytes(), b"precious")

    def test_older_versions_also_need_the_flag(self):
        old = self.dir / "v2.sqlite3"
        shutil.copyfile(FIXTURE, old)
        db = sqlite3.connect(old)
        db.executescript("DROP TABLE social_steps; DROP TABLE social_runs; DROP TABLE agent_actions; "
                         "DROP TABLE agent_decisions; DROP TABLE agent_observations; DROP TABLE agent_goals; "
                         "PRAGMA user_version=2;")
        db.close()
        with self.assertRaises(SchemaUpgradeNotAllowedError):
            WorldStore(old, allow_schema_upgrade=False)
        WorldStore(old, allow_schema_upgrade=True)
        self.assertEqual(dbtools.manifest(old)["user_version"], 5)

    def test_future_version_is_still_refused(self):
        far = self.dir / "future.sqlite3"
        shutil.copyfile(FIXTURE, far)
        db = sqlite3.connect(far)
        db.execute("PRAGMA user_version=6")
        db.close()
        with self.assertRaises(SchemaVersionError):
            WorldStore(far, allow_schema_upgrade=True)


class G3RegistrationTests(Base):
    def test_legacy_registration_unchanged_and_byte_identical(self):
        aid = str(uuid4())
        via_request = self.reg({"character_id": "yui"}, actor_id=aid)
        other = self.make(self.dir / "other.sqlite3")
        w2 = other.register_world("W2", self.world)
        direct = other.register_actor(self.world, DIM, "yui", aid)
        self.assertEqual(via_request, direct)
        self.assertEqual(set(via_request), {"actor_id", "world_id", "dimension_id", "character_id"})
        self.assertEqual(w2["world_id"], self.world)

    def test_native_registration_creates_actor_and_revision_one(self):
        out = self.reg({"profile": structured()})
        self.assertTrue(out["created"])
        self.assertEqual(out["character_id"], LEGACY_SENTINEL)
        self.assertEqual(out["profile"]["revision"], 1)
        self.assertEqual(out["profile"]["source"], "inline")
        self.assertEqual(self.count("actors"), 1)
        self.assertEqual(self.count("character_profiles"), 1)

    def test_default_and_preset_sources(self):
        d = self.reg({"default_profile": {"display_name": "Sakura"}})
        self.assertEqual((d["profile"]["source"], d["profile"]["display_name"]), ("default", "Sakura"))
        p = self.reg({"preset": "yui"})
        self.assertEqual(p["profile"]["source"], "preset:yui")
        pack = json.loads((PACKS / "yui" / "character.json").read_text(encoding="utf-8"))
        self.assertEqual(self.svc.resolve_actor_character({"actor_id": p["actor_id"], "character_id": p["character_id"]}
                                                          ).system_prompt, pack["system_prompt"])

    def test_names_need_no_matching_pack(self):
        for name in ("Reika", "Mika", "g5n02", "Sakura", "A1_b"):
            out = self.reg({"default_profile": {"display_name": name}})
            self.assertEqual(out["profile"]["display_name"], name)

    def test_require_existing_profile(self):
        aid = str(uuid4())
        with self.assertRaises(ProfileNotRegisteredError):
            self.reg({"require_existing_profile": True}, actor_id=aid)
        with self.assertRaises(IdentityError):
            self.reg({"require_existing_profile": True})  # needs actor_id
        self.reg({"profile": raw()}, actor_id=aid)
        out = self.reg({"require_existing_profile": True}, actor_id=aid)
        self.assertFalse(out["created"])
        self.assertEqual(out["profile"]["revision"], 1)
        self.assertEqual(self.count("actors"), 1)  # never creates anything

    def test_conflict_matrix(self):
        legacy, native = str(uuid4()), str(uuid4())
        self.reg({"character_id": "yui"}, actor_id=legacy)
        self.reg({"profile": raw()}, actor_id=native)
        # legacy row
        self.assertEqual(self.reg({"character_id": "yui"}, actor_id=legacy)["character_id"], "yui")
        with self.assertRaises(RegistryConflictError):
            self.reg({"character_id": "guide"}, actor_id=legacy)
        for selector in ({"profile": raw()}, {"default_profile": {"display_name": "Zed"}}, {"preset": "yui"}):
            with self.subTest(selector=list(selector)), self.assertRaises(RegistryConflictError):
                self.reg(selector, actor_id=legacy)
        with self.assertRaises(ProfileNotRegisteredError):
            self.reg({"require_existing_profile": True}, actor_id=legacy)
        # native row
        with self.assertRaises(RegistryConflictError):
            self.reg({"character_id": "yui"}, actor_id=native)
        replay = self.reg({"profile": raw()}, actor_id=native)
        self.assertFalse(replay["created"])
        with self.assertRaises(RegistryConflictError):
            self.reg({"profile": raw(prompt="You are different.")}, actor_id=native)
        with self.assertRaises(RegistryConflictError):
            self.reg({"preset": "yui"}, actor_id=native)
        with self.assertRaises(RegistryConflictError):
            self.reg({"profile": raw()}, actor_id=native, dim="minecraft:the_nether")
        self.assertEqual(self.count("character_profiles"), 1)

    def test_default_and_preset_replay_rules(self):
        d, p = str(uuid4()), str(uuid4())
        self.reg({"default_profile": {"display_name": "Rei"}}, actor_id=d)
        self.assertFalse(self.reg({"default_profile": {"display_name": "Rei"}}, actor_id=d)["created"])
        with self.assertRaises(RegistryConflictError):
            self.reg({"default_profile": {"display_name": "Other"}}, actor_id=d)
        self.reg({"preset": "yui"}, actor_id=p)
        self.assertFalse(self.reg({"preset": "yui"}, actor_id=p)["created"])
        with self.assertRaises(RegistryConflictError):
            self.reg({"preset": "guide"}, actor_id=p)

    def test_world_and_input_errors_persist_nothing(self):
        with self.assertRaises(RegistryNotFoundError):
            self.reg({"profile": raw()}, world=str(uuid4()))
        with self.assertRaises(ProfileError):
            self.reg({"profile": {"display_name": "x"}})
        with self.assertRaises(CharacterError):
            self.reg({"preset": "no_such_pack"})
        with self.assertRaises(CharacterError):
            self.reg({"preset": "../yui"})
        with self.assertRaises(IdentityError):
            self.reg({"profile": raw(), "preset": "yui"})
        with self.assertRaises(IdentityError):
            self.reg({})
        self.assertEqual(self.count("actors"), 0)
        self.assertEqual(self.count("character_profiles"), 0)

    def test_sentinel_cannot_be_registered_as_a_legacy_pack(self):
        for value in (LEGACY_SENTINEL, "profile", "@profile "):
            with self.assertRaises(CharacterError):
                self.reg({"character_id": value})
        with self.assertRaises(CharacterError):
            load_character(PACKS, LEGACY_SENTINEL)
        self.assertEqual(self.count("actors"), 0)

    def test_crash_between_actor_and_profile_insert_leaves_nothing(self):
        with patch.object(WorldStore, "_insert_profile_row", side_effect=RuntimeError("boom")):
            with self.assertRaises(RuntimeError):
                self.reg({"profile": raw()})
        self.assertEqual(self.count("actors"), 0)
        self.assertEqual(self.count("character_profiles"), 0)
        self.assertTrue(self.reg({"profile": raw()})["created"])

    def test_profile_rows_are_immutable(self):
        aid = self.reg({"profile": raw()})["actor_id"]
        db = sqlite3.connect(self.path)
        try:
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("UPDATE character_profiles SET system_prompt='x' WHERE actor_id=?", (aid,))
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("DELETE FROM character_profiles WHERE actor_id=?", (aid,))
        finally:
            db.close()

    def test_foreign_key_requires_an_actor(self):
        db = sqlite3.connect(self.path)
        db.execute("PRAGMA foreign_keys=ON")
        try:
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO character_profiles VALUES (?,1,1,'raw','n','en','','','','p','inline','h','','t')",
                           (str(uuid4()),))
        finally:
            db.close()


class G4ResolutionTests(Base):
    def prompts(self):
        return [c[0]["content"] for c in self.provider.calls]

    def test_each_actor_gets_only_its_own_prompt(self):
        specs = {"Reika": structured("Reika", "Calm."), "Mika": raw("Mika", "You are Mika the baker."),
                 "Sakura": {"display_name": "Sakura", "sections": {"personality": "Loud."}}}
        ids = {name: self.reg({"profile": body})["actor_id"] for name, body in specs.items()}
        legacy = self.reg({"character_id": "yui"})["actor_id"]
        expected = {n: validate_profile(b).system_prompt for n, b in specs.items()}
        for name, aid in ids.items():
            self.provider.calls.clear()
            self.svc.chat_actor(self.world, DIM, aid, "p1", "hello")
            self.assertEqual(self.prompts(), [expected[name]])
        pack = json.loads((PACKS / "yui" / "character.json").read_text(encoding="utf-8"))
        self.provider.calls.clear()
        self.svc.chat_actor(self.world, DIM, legacy, "p1", "hello")
        self.assertEqual(self.prompts(), [pack["system_prompt"]])

    def test_transcripts_stay_per_actor(self):
        a = self.reg({"profile": raw("A", "You are A.")})["actor_id"]
        b = self.reg({"profile": raw("B", "You are B.")})["actor_id"]
        self.svc.chat_actor(self.world, DIM, a, "p1", "secret for A")
        self.provider.calls.clear()
        self.svc.chat_actor(self.world, DIM, b, "p1", "hello B")
        self.assertNotIn("secret for A", json.dumps(self.provider.calls[0]))

    def test_sentinel_never_reaches_load_character_on_any_consumer_path(self):
        real = service_module.load_character

        def guarded(packs, character_id):
            self.assertNotEqual(character_id, LEGACY_SENTINEL)
            return real(packs, character_id)

        sys.path.insert(0, str(ROOT / "backend" / "tests"))
        from test_b3_social import FakeSocialAI
        ai = FakeSocialAI()
        svc = ChatService(PACKS, "fake", ai, SessionStore(), world_store=self.svc.world_store)
        a = svc.register_actor_request({"world_id": self.world, "dimension_id": DIM, "profile": raw("A", "You are A.")})
        b = svc.register_actor_request({"world_id": self.world, "dimension_id": DIM, "profile": raw("B", "You are B.")})
        with patch.object(service_module, "load_character", guarded), \
                patch("npcortex_backend.characters.load_character", guarded):
            svc.chat_actor(self.world, DIM, a["actor_id"], "p1", "hello")
            dialogue = svc.create_dialogue(self.world, DIM, [a["actor_id"], b["actor_id"]], [], "topic", 6)
            svc.dialogue_turn(dialogue["dialogue_id"], "actor", a["actor_id"])
            run = svc.create_social_run(svc.create_dialogue(self.world, DIM, [a["actor_id"], b["actor_id"]], [], "t2", 6)["dialogue_id"], 2)
            step = svc.social_step(run["run_id"], str(uuid4()))
        self.assertEqual(step["step"]["action"], "speak")
        social_names = [json.loads(c[1]["content"]) for c in ai.prompts if "turn-taking coordinator" in c[0]["content"]]
        names = {x["name"] for ctx in social_names for x in ctx["actors"]} if social_names and "actors" in social_names[0] else None
        if names is not None:
            self.assertEqual(names, {"A", "B"})

    def test_resolver_fails_closed_without_profile(self):
        with self.assertRaises(CharacterError):
            self.svc.resolve_actor_character({"actor_id": str(uuid4()), "character_id": LEGACY_SENTINEL})
        with self.assertRaises(CharacterError):
            self.svc.resolve_actor_character({"actor_id": str(uuid4()), "character_id": "no_pack_for_this"})

    def test_legacy_actor_with_missing_pack_fails_loudly_without_default(self):
        aid = self.reg({"character_id": "yui"})["actor_id"]
        with patch.object(service_module, "load_character", side_effect=CharacterError("Character pack 'yui' not found or invalid")):
            with self.assertRaises(CharacterError):
                self.svc.chat_actor(self.world, DIM, aid, "p1", "hi")
        self.assertEqual(self.count("character_profiles"), 0)  # nothing silently adopted

    def test_editing_one_actor_does_not_change_others(self):
        a = self.reg({"profile": raw("A", "You are A.")})["actor_id"]
        b = self.reg({"profile": raw("B", "You are B.")})["actor_id"]
        before = self.svc.world_store.export_profile(b)["content_sha256"]
        self.svc.world_store.add_profile_revision(a, 1, validate_profile(raw("A", "You are A2.")))
        self.assertEqual(self.svc.world_store.export_profile(b)["content_sha256"], before)
        self.provider.calls.clear()
        self.svc.chat_actor(self.world, DIM, a, "p1", "hi")
        self.assertEqual(self.prompts(), ["You are A2."])


class G5RevisionTests(Base):
    def setUp(self):
        super().setUp()
        self.aid = self.reg({"profile": structured()})["actor_id"]
        self.store = self.svc.world_store

    def edit(self, expected, body, note=None):
        return self.store.add_profile_revision(self.aid, expected, validate_profile(body), note)

    def test_update_creates_next_revision_and_keeps_history(self):
        first = self.store.export_profile(self.aid)
        out = self.edit(1, structured(personality="Warmer now."), "tone")
        self.assertTrue(out["applied"])
        self.assertEqual(out["profile"]["revision"], 2)
        self.assertEqual(self.store.export_profile(self.aid)["revision"], 2)
        db = sqlite3.connect(self.path)
        rows = db.execute("SELECT revision,content_sha256,source,note FROM character_profiles WHERE actor_id=? "
                          "ORDER BY revision", (self.aid,)).fetchall()
        db.close()
        self.assertEqual([r[0] for r in rows], [1, 2])
        self.assertEqual(rows[0][1], first["content_sha256"])  # revision 1 untouched
        self.assertEqual((rows[1][2], rows[1][3]), ("edit", "tone"))

    def test_stale_expected_revision_conflicts_with_current(self):
        self.edit(1, structured(personality="v2"))
        with self.assertRaises(RevisionConflictError) as caught:
            self.edit(1, structured(personality="somebody else's v2"))
        self.assertEqual(caught.exception.current_revision, 2)
        with self.assertRaises(RevisionConflictError):
            self.edit(9, structured(personality="future"))
        self.assertEqual(self.count("character_profiles"), 2)

    def test_retry_of_applied_edit_is_idempotent(self):
        body = structured(personality="retry me")
        first = self.edit(1, body)
        again = self.edit(1, body)  # client timed out and retried
        self.assertTrue(first["applied"])
        self.assertFalse(again["applied"])
        self.assertEqual(again["profile"]["revision"], 2)
        self.assertEqual(self.count("character_profiles"), 2)

    def test_noop_edit_creates_no_revision(self):
        out = self.edit(1, structured())
        self.assertFalse(out["applied"])
        self.assertEqual(self.count("character_profiles"), 1)

    def test_composition_can_change_between_revisions(self):
        out = self.edit(1, raw("Reika", "You are Reika, hand written."))
        self.assertEqual(out["profile"]["composition"], "raw")
        self.assertEqual(self.store.export_profile(self.aid)["profile"]["system_prompt"], "You are Reika, hand written.")

    def test_two_concurrent_writers_exactly_one_wins(self):
        barrier = Barrier(2)

        def attempt(i):
            barrier.wait()
            try:
                self.edit(1, structured(personality=f"writer {i}"))
                return "ok"
            except RevisionConflictError:
                return "conflict"

        with ThreadPoolExecutor(2) as pool:
            results = sorted(pool.map(attempt, (1, 2)))
        self.assertEqual(results, ["conflict", "ok"])
        self.assertEqual(self.count("character_profiles"), 2)

    def test_bad_inputs(self):
        for expected in (0, -1, "1", None, 1.5, True):
            with self.subTest(expected=expected), self.assertRaises(IdentityError):
                self.store.add_profile_revision(self.aid, expected, validate_profile(raw()))
        with self.assertRaises(RegistryNotFoundError):
            self.store.add_profile_revision(str(uuid4()), 1, validate_profile(raw()))
        with self.assertRaises(ProfileError):
            self.store.add_profile_revision(self.aid, 1, validate_profile(raw()), "n" * 201)

    def test_legacy_actor_cannot_be_edited(self):
        legacy = self.reg({"character_id": "yui"})["actor_id"]
        with self.assertRaises(ProfileNotRegisteredError):
            self.store.add_profile_revision(legacy, 1, validate_profile(raw()))
        with self.assertRaises(ProfileNotRegisteredError):
            self.store.export_profile(legacy)
        self.assertEqual(self.store.get_actor(legacy)["character_id"], "yui")

    def test_next_chat_uses_the_new_revision(self):
        self.edit(1, raw("Reika", "You are the revised Reika."))
        self.svc.chat_actor(self.world, DIM, self.aid, "p1", "hello")
        self.assertEqual(self.provider.calls[-1][0]["content"], "You are the revised Reika.")


class G6ListingTests(Base):
    def test_listing_reports_native_legacy_and_unregistered_world(self):
        native = self.reg({"profile": structured()})["actor_id"]
        legacy = self.reg({"character_id": "yui"})["actor_id"]
        listing = self.svc.world_store.list_world_profiles(self.world)
        by_id = {a["actor_id"]: a for a in listing["actors"]}
        self.assertEqual(by_id[native]["profile"]["revision"], 1)
        self.assertEqual(by_id[native]["character_id"], LEGACY_SENTINEL)
        self.assertIsNone(by_id[legacy]["profile"])
        self.assertEqual(by_id[legacy]["character_id"], "yui")
        self.assertNotIn("system_prompt", json.dumps(listing))
        with self.assertRaises(RegistryNotFoundError):
            self.svc.world_store.list_world_profiles(str(uuid4()))

    def test_listing_is_scoped_to_one_world(self):
        other = self.svc.register_world("Other")["world_id"]
        self.reg({"profile": raw()})
        self.reg({"profile": raw("Z", "You are Z.")}, world=other)
        self.assertEqual(len(self.svc.world_store.list_world_profiles(self.world)["actors"]), 1)

    def test_revision_shows_in_listing(self):
        aid = self.reg({"profile": structured()})["actor_id"]
        self.svc.world_store.add_profile_revision(aid, 1, validate_profile(structured(personality="new")))
        self.assertEqual(self.svc.world_store.list_world_profiles(self.world)["actors"][0]["profile"]["revision"], 2)


class HttpBase(Base):
    def setUp(self):
        super().setUp()
        self.httpd = make_http_server(self.svc)
        self.thread = Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=3)

    def call(self, method, path, body=None, token=None, raw_body=None):
        data = raw_body if raw_body is not None else (None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"))
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=5)
        try:
            conn.putrequest(method, path)
            if data is not None:
                conn.putheader("Content-Type", "application/json; charset=utf-8")
                conn.putheader("Content-Length", str(len(data)))
            if token is not None:
                conn.putheader("X-NPCortex-CPlus-Token", token)
            conn.endheaders(data)
            response = conn.getresponse()
            return response.status, json.loads(response.read() or b"{}")
        finally:
            conn.close()


class G7HttpTests(HttpBase):
    def env(self, require=False):
        values = {"NPCORTEX_CPLUS_F1_TOKEN": TOKEN}
        if require:
            values["NPCORTEX_REQUIRE_TOKEN"] = "1"
        return patch.dict(os.environ, values)

    def setUp(self):
        super().setUp()
        os.environ.pop("NPCORTEX_REQUIRE_TOKEN", None)

    def body(self, selector):
        return {"world_id": self.world, "dimension_id": DIM, **selector}

    def test_inline_profile_registration_needs_token_even_when_enforcement_is_off(self):
        with self.env():
            self.assertFalse(os.environ.get("NPCORTEX_REQUIRE_TOKEN"))
            status, _ = self.call("POST", "/v1/actors", self.body({"profile": raw()}))
            self.assertEqual(status, 403)
            status, _ = self.call("POST", "/v1/actors", self.body({"profile": raw()}), token="wrong" * 8)
            self.assertEqual(status, 403)
            status, out = self.call("POST", "/v1/actors", self.body({"profile": raw()}), token=TOKEN)
            self.assertEqual(status, 201)
            self.assertEqual(out["character_id"], LEGACY_SENTINEL)
        self.assertEqual(self.count("actors"), 1)

    def test_profile_routes_fail_closed_without_any_configured_secret(self):
        with patch.dict(os.environ, {"NPCORTEX_CPLUS_F1_SECRET_FILE": str(self.dir / "absent.secret")}):
            os.environ.pop("NPCORTEX_CPLUS_F1_TOKEN", None)
            self.assertEqual(self.call("POST", "/v1/actors", self.body({"profile": raw()}), token=TOKEN)[0], 403)
            self.assertEqual(self.call("GET", f"/v1/worlds/{self.world}/profiles", token=TOKEN)[0], 403)

    def test_every_profile_selector_needs_token_but_legacy_keeps_the_old_rule(self):
        with self.env():
            self.assertEqual(self.call("POST", "/v1/actors", self.body({"character_id": "yui"}))[0], 201)
            aid = str(uuid4())
            for selector in ({"default_profile": {"display_name": "Rei"}}, {"preset": "guide"},
                             {"require_existing_profile": True}):
                with self.subTest(selector=list(selector)):
                    body = {**self.body(selector), "actor_id": aid}
                    self.assertEqual(self.call("POST", "/v1/actors", body)[0], 403)
                    self.assertEqual(self.call("POST", "/v1/actors", body, token="x" * 40)[0], 403)
            self.assertEqual(self.count("character_profiles"), 0)
            ok = self.call("POST", "/v1/actors", {**self.body({"default_profile": {"display_name": "Rei"}}), "actor_id": aid}, token=TOKEN)
            self.assertEqual(ok[0], 201)
            # an existing profile is not revealed to a token-less caller either
            self.assertEqual(self.call("POST", "/v1/actors", {**self.body({"require_existing_profile": True}), "actor_id": aid})[0], 403)
        with self.env(require=True):
            self.assertEqual(self.call("POST", "/v1/actors", self.body({"character_id": "guide"}))[0], 403)

    def test_export_listing_and_revisions_need_token_always(self):
        with self.env():
            aid = self.call("POST", "/v1/actors", self.body({"profile": structured()}), token=TOKEN)[1]["actor_id"]
            routes = [("GET", f"/v1/actors/{aid}/profile", None), ("GET", f"/v1/worlds/{self.world}/profiles", None),
                      ("POST", f"/v1/actors/{aid}/profile/revisions", {"expected_revision": 1, "profile": raw()})]
            for method, path, body in routes:
                with self.subTest(path=path):
                    self.assertEqual(self.call(method, path, body)[0], 403)
                    self.assertEqual(self.call(method, path, body, token="x" * 40)[0], 403)
            self.assertEqual(self.call("GET", f"/v1/actors/{aid}/profile", token=TOKEN)[0], 200)
        # unauthenticated attempts changed nothing
        self.assertEqual(self.count("character_profiles"), 1)

    def test_export_shape_is_importable_as_is(self):
        with self.env():
            aid = self.call("POST", "/v1/actors", self.body({"profile": structured()}), token=TOKEN)[1]["actor_id"]
            status, exported = self.call("GET", f"/v1/actors/{aid}/profile", token=TOKEN)
            self.assertEqual(status, 200)
            self.assertEqual(set(exported), {"actor_id", "revision", "source", "content_sha256", "composition",
                                             "effective_system_prompt", "profile"})
            status, copy = self.call("POST", "/v1/actors", self.body({"profile": exported["profile"]}), token=TOKEN)
            self.assertEqual(status, 201)
            self.assertNotEqual(copy["actor_id"], aid)
            again = self.call("GET", f"/v1/actors/{copy['actor_id']}/profile", token=TOKEN)[1]
            self.assertEqual(again["content_sha256"], exported["content_sha256"])
            self.assertEqual(again["effective_system_prompt"], exported["effective_system_prompt"])

    def test_export_of_legacy_actor_is_404_and_does_not_read_the_pack(self):
        with self.env():
            aid = self.call("POST", "/v1/actors", self.body({"character_id": "yui"}))[1]["actor_id"]
            with patch.object(service_module, "load_character", side_effect=AssertionError("pack read")):
                status, body = self.call("GET", f"/v1/actors/{aid}/profile", token=TOKEN)
            self.assertEqual((status, body["code"]), (404, "profile_not_registered"))
            self.assertEqual(self.call("GET", f"/v1/actors/{uuid4()}/profile", token=TOKEN)[0], 404)

    def test_revisions_route_success_conflict_replay(self):
        with self.env():
            aid = self.call("POST", "/v1/actors", self.body({"profile": structured()}), token=TOKEN)[1]["actor_id"]
            path = f"/v1/actors/{aid}/profile/revisions"
            edit = {"expected_revision": 1, "profile": structured(personality="Edited.")}
            status, out = self.call("POST", path, edit, token=TOKEN)
            self.assertEqual((status, out["profile"]["revision"], out["applied"]), (201, 2, True))
            status, out = self.call("POST", path, edit, token=TOKEN)  # retry
            self.assertEqual((status, out["applied"]), (200, False))
            status, out = self.call("POST", path, {"expected_revision": 1, "profile": structured(personality="Other.")}, token=TOKEN)
            self.assertEqual((status, out["code"], out["current_revision"]), (409, "revision_conflict", 2))
            self.assertEqual(self.call("POST", path, {"profile": raw()}, token=TOKEN)[0], 400)
            self.assertEqual(self.call("POST", path, {"expected_revision": 2, "profile": {"display_name": "x"}}, token=TOKEN)[1]["code"], "invalid_profile")

    def test_revision_without_language_keeps_the_stored_language(self):
        with self.env():
            pack = {"display_name": "Hans", "language": "de", "system_prompt": "Du bist Hans."}
            aid = self.call("POST", "/v1/actors", self.body({"profile": pack}), token=TOKEN)[1]["actor_id"]
            path = f"/v1/actors/{aid}/profile/revisions"
            same = {"expected_revision": 1, "profile": {"display_name": "Hans", "system_prompt": "Du bist Hans."}}
            status, out = self.call("POST", path, same, token=TOKEN)
            self.assertEqual((status, out["applied"]), (200, False))  # no hidden language change, no new revision
            changed = {"expected_revision": 1, "profile": {"display_name": "Hans", "system_prompt": "Du bist Hans, der Baecker."}}
            self.assertEqual(self.call("POST", path, changed, token=TOKEN)[0], 201)
            self.assertEqual(self.call("GET", f"/v1/actors/{aid}/profile", token=TOKEN)[1]["profile"]["language"], "de")
            explicit = {"expected_revision": 2, "profile": {"display_name": "Hans", "language": "en", "system_prompt": "You are Hans."}}
            self.assertEqual(self.call("POST", path, explicit, token=TOKEN)[0], 201)
            self.assertEqual(self.call("GET", f"/v1/actors/{aid}/profile", token=TOKEN)[1]["profile"]["language"], "en")

    def test_listing_route(self):
        with self.env():
            self.call("POST", "/v1/actors", self.body({"profile": structured()}), token=TOKEN)
            self.call("POST", "/v1/actors", self.body({"character_id": "yui"}))
            status, out = self.call("GET", f"/v1/worlds/{self.world}/profiles", token=TOKEN)
            self.assertEqual(status, 200)
            self.assertEqual(len(out["actors"]), 2)
            self.assertEqual(self.call("GET", f"/v1/worlds/{uuid4()}/profiles", token=TOKEN)[0], 404)

    def test_body_limits_apply_only_to_profile_payloads(self):
        with self.env():
            big_plain = self.body({"character_id": "yui", "pad": "x" * 9000})
            self.assertEqual(self.call("POST", "/v1/actors", big_plain)[0], 413)
            self.assertEqual(self.call("POST", "/v1/chat", {"character_id": "yui", "player_id": "p", "message": "m" * 9000})[0], 413)
            thai = "ก" * 5000  # 15000 bytes of UTF-8
            status, out = self.call("POST", "/v1/actors", self.body({"profile": raw("T", thai)}), token=TOKEN)
            self.assertEqual(status, 201)
            exported = self.call("GET", f"/v1/actors/{out['actor_id']}/profile", token=TOKEN)[1]
            self.assertEqual(exported["profile"]["system_prompt"], thai)
            huge = self.body({"profile": raw("T", "x" * 5000), "pad": "y" * 33000})
            self.assertEqual(self.call("POST", "/v1/actors", huge, token=TOKEN)[0], 413)
            self.assertEqual(self.call("POST", "/v1/actors", self.body({"profile": raw("T", "x" * 5001)}), token=TOKEN)[1]["code"], "invalid_profile")

    def test_non_ascii_escaped_json_also_fits(self):
        with self.env():
            body = json.dumps(self.body({"profile": raw("T", "日" * 4000)}), ensure_ascii=True).encode()
            self.assertLess(len(body), 32768)
            self.assertEqual(self.call("POST", "/v1/actors", raw_body=body, token=TOKEN)[0], 201)

    def test_errors_do_not_echo_credentials_or_prompts(self):
        with self.env():
            status, out = self.call("POST", "/v1/actors", self.body({"profile": raw("T", "x" * 6000)}), token=TOKEN)
            text = json.dumps(out)
            self.assertNotIn(TOKEN, text)
            self.assertNotIn("x" * 100, text)

    def test_legacy_registration_via_http_unchanged(self):
        status, out = self.call("POST", "/v1/actors", self.body({"character_id": "yui"}))
        self.assertEqual((status, set(out)), (201, {"actor_id", "world_id", "dimension_id", "character_id"}))
        self.assertEqual(self.call("POST", "/v1/actors", self.body({"character_id": "no_pack"}))[1]["code"], "invalid_character")
        self.assertEqual(self.call("POST", "/v1/actors", {"world_id": self.world, "dimension_id": DIM})[0], 400)
        with self.env():
            self.assertEqual(self.call("POST", "/v1/actors", self.body({"character_id": "yui", "profile": raw()}), token=TOKEN)[0], 400)


if __name__ == "__main__":
    unittest.main()
