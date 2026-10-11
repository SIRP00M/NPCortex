"""Strict, data-driven character packs. No arbitrary path access from API input."""
from dataclasses import dataclass
import json
from pathlib import Path
import re

CHARACTER_ID = re.compile(r"^[a-z][a-z0-9_-]{0,39}$")

class CharacterError(ValueError):
    pass

@dataclass(frozen=True)
class Character:
    character_id: str
    display_name: str
    language: str
    system_prompt: str


def load_character(packs_dir: Path, character_id: str) -> Character:
    if not CHARACTER_ID.fullmatch(character_id):
        raise CharacterError("Invalid character_id: use lowercase letters, digits, _ or -")
    path = packs_dir / character_id / "character.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CharacterError(f"Character pack '{character_id}' not found or invalid") from exc
    if not isinstance(data, dict) or data.get("id") != character_id:
        raise CharacterError("Character pack ID does not match folder")
    name, language, prompt = data.get("name"), data.get("language"), data.get("system_prompt")
    if not isinstance(name, str) or not 1 <= len(name) <= 80:
        raise CharacterError("Invalid character name")
    if not isinstance(language, str) or not 1 <= len(language) <= 16:
        raise CharacterError("Invalid character language")
    if not isinstance(prompt, str) or not 1 <= len(prompt) <= 5000:
        raise CharacterError("Missing or invalid system_prompt")
    return Character(character_id=character_id, display_name=name, language=language, system_prompt=prompt)
