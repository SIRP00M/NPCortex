"""Character Profile v1: per-actor, SQLite-owned personality records.

A Character Profile is NOT a Character Pack (a folder of mutable JSON) and NOT the
Minecraft GameProfile used by C1. It is validated here once, stored as an immutable
revision, and sent to the model as the system prompt.
"""
from dataclasses import dataclass
import hashlib
import json
import re

PROFILE_SCHEMA_VERSION = 1
COMPOSER_VERSION = 1
LEGACY_SENTINEL = "@profile"  # actors.character_id value of profile-bound actors; never a valid pack ID

MAX_DISPLAY_NAME = 80
MAX_LANGUAGE = 16
MAX_PERSONALITY = 1500
MAX_SPEECH_STYLE = 500
MAX_BACKSTORY = 1500
MAX_SYSTEM_PROMPT = 5000
MAX_NOTE = 200
MAX_TOTAL_UTF8_BYTES = 24000

NPC_NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")  # same rule as C1IdentityData.validName
LANGUAGE = re.compile(r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f-\x9f]")


class ProfileError(ValueError):
    """The submitted Character Profile is invalid (HTTP 400 invalid_profile)."""


@dataclass(frozen=True)
class Profile:
    composition: str  # "raw" | "structured"
    display_name: str
    language: str
    system_prompt: str  # effective prompt sent to the model
    personality: str = ""
    speech_style: str = ""
    backstory: str = ""

    @property
    def content_sha256(self) -> str:
        sections = None
        if self.composition == "structured":
            sections = {"personality": self.personality, "speech_style": self.speech_style,
                        "backstory": self.backstory}
        canonical = {"schema_version": PROFILE_SCHEMA_VERSION, "composition": self.composition,
                     "display_name": self.display_name, "language": self.language,
                     "system_prompt": self.system_prompt, "sections": sections}
        raw = json.dumps(canonical, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def importable(self) -> dict:
        """The exact object /v1/actors and the revisions route accept (export == import format)."""
        base = {"schema_version": PROFILE_SCHEMA_VERSION, "composition": self.composition,
                "display_name": self.display_name, "language": self.language}
        if self.composition == "structured":
            base["sections"] = {"personality": self.personality, "speech_style": self.speech_style,
                                "backstory": self.backstory}
        else:
            base["system_prompt"] = self.system_prompt
        return base


def _text(value, field: str, minimum: int, maximum: int, multiline: bool) -> str:
    if not isinstance(value, str):
        raise ProfileError(f"{field} must be text")
    text = value.replace("\r\n", "\n").replace("\r", "\n").strip()
    try:
        text.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise ProfileError(f"{field} contains invalid Unicode") from exc
    if _CONTROL.search(text) or (not multiline and ("\n" in text or "\t" in text)):
        raise ProfileError(f"{field} contains control characters")
    if not minimum <= len(text) <= maximum:
        raise ProfileError(f"{field} must contain {minimum}-{maximum} characters")
    return text


def compose_system_prompt(display_name: str, personality: str, speech_style: str, backstory: str) -> str:
    """Deterministic English template (composer v1). User text is appended as data, never formatted."""
    lines = [f"You are {display_name}, an NPC companion living in a Minecraft world.",
             "Personality: " + personality]
    if speech_style:
        lines.append("Speech style: " + speech_style)
    if backstory:
        lines.append("Backstory: " + backstory)
    lines.append("Always reply in natural English and stay in character. Never claim that you have moved, "
                 "mined, crafted, picked up an item, fought an enemy or changed the game world unless a "
                 "game system has confirmed it.")
    return "\n".join(lines)


def validate_profile(obj) -> Profile:
    if not isinstance(obj, dict):
        raise ProfileError("profile must be a JSON object")
    version = obj.get("schema_version", PROFILE_SCHEMA_VERSION)
    if isinstance(version, bool) or version != PROFILE_SCHEMA_VERSION:
        raise ProfileError("Unsupported profile schema_version")
    has_sections, has_prompt = "sections" in obj, "system_prompt" in obj
    if has_sections == has_prompt:
        raise ProfileError("Provide exactly one of 'sections' (structured) or 'system_prompt' (raw)")
    composition = "structured" if has_sections else "raw"
    if "composition" in obj and obj["composition"] != composition:
        raise ProfileError("composition does not match the supplied fields")
    allowed = {"schema_version", "composition", "display_name", "language",
               "sections" if has_sections else "system_prompt"}
    extra = set(obj) - allowed
    if extra:
        raise ProfileError("Unknown profile field(s): " + ", ".join(sorted(map(str, extra))[:5]))
    if "display_name" not in obj:
        raise ProfileError("display_name is required")
    name = _text(obj["display_name"], "display_name", 1, MAX_DISPLAY_NAME, False)
    language = _text(obj.get("language", "en"), "language", 1, MAX_LANGUAGE, False)
    if not LANGUAGE.fullmatch(language):
        raise ProfileError("language must look like 'en' or 'en-US'")
    if has_sections:
        sections = obj["sections"]
        if not isinstance(sections, dict) or set(sections) - {"personality", "speech_style", "backstory"}:
            raise ProfileError("sections may contain only personality, speech_style and backstory")
        personality = _text(sections.get("personality"), "personality", 1, MAX_PERSONALITY, True)
        style = _text(sections.get("speech_style", ""), "speech_style", 0, MAX_SPEECH_STYLE, True)
        backstory = _text(sections.get("backstory", ""), "backstory", 0, MAX_BACKSTORY, True)
        prompt = compose_system_prompt(name, personality, style, backstory)
        profile = Profile("structured", name, language, prompt, personality, style, backstory)
    else:
        prompt = _text(obj["system_prompt"], "system_prompt", 1, MAX_SYSTEM_PROMPT, True)
        profile = Profile("raw", name, language, prompt)
    total = sum(len(s.encode("utf-8")) for s in (profile.display_name, profile.language, profile.system_prompt,
                                                 profile.personality, profile.speech_style, profile.backstory))
    if total > MAX_TOTAL_UTF8_BYTES or len(profile.system_prompt) > MAX_SYSTEM_PROMPT:
        raise ProfileError("profile is too large")
    return profile


def note_text(value) -> str:
    if value is None:
        return ""
    return _text(value, "note", 0, MAX_NOTE, False)


def default_profile(display_name) -> Profile:
    """Built-in default (source "default"). The name is the C1 NPC name, so it is strictly sanitised."""
    if not isinstance(display_name, str) or not NPC_NAME.fullmatch(display_name):
        raise ProfileError("display_name must be 1-16 ASCII letters, digits or underscores")
    return validate_profile({
        "display_name": display_name,
        "sections": {"personality": "A friendly, curious and helpful companion who enjoys exploring and building.",
                     "speech_style": "Short, warm and natural sentences.",
                     "backstory": ""}})


def profile_from_pack(character) -> Profile:
    """Copy a Character Pack once (source "preset:<id>"). The pack is not referenced afterwards."""
    return validate_profile({"display_name": character.display_name, "language": character.language,
                             "system_prompt": character.system_prompt})
