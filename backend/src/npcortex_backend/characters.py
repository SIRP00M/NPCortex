"""Validatable character packs; all API-supplied IDs are constrained to a directory name."""
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
    skin_id: str = ""
    skin_model: str = "wide"

    def public_profile(self) -> dict:
        """Only send display data to Minecraft, not the system prompt."""
        return {
            "id": self.character_id,
            "name": self.display_name,
            "language": self.language,
            "appearance": {"skin": self.skin_id, "model": self.skin_model},
        }


def load_character(packs_dir: Path, character_id: str) -> Character:
    if not isinstance(character_id, str) or not CHARACTER_ID.fullmatch(character_id):
        raise CharacterError("Invalid character_id: use lowercase letters, digits, _ or -")
    root = packs_dir.resolve()
    path = (root / character_id / "character.json").resolve()
    if not path.is_relative_to(root):
        raise CharacterError("Character pack cannot escape the packs directory")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CharacterError(f"Character pack '{character_id}' not found or invalid") from exc
    if not isinstance(data, dict) or data.get("id") != character_id:
        raise CharacterError("Character pack ID does not match folder")
    name, language, prompt = data.get("name"), data.get("language"), data.get("system_prompt")
    if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80:
        raise CharacterError("Invalid character name")
    if not isinstance(language, str) or not 1 <= len(language) <= 16:
        raise CharacterError("Invalid character language")
    if not isinstance(prompt, str) or not 1 <= len(prompt) <= 5000:
        raise CharacterError("Missing or invalid system_prompt")
    appearance = data.get("appearance", {})
    if not isinstance(appearance, dict):
        raise CharacterError("appearance must be an object")
    skin = appearance.get("skin", character_id)
    model = appearance.get("model", "wide")
    if not isinstance(skin, str) or not CHARACTER_ID.fullmatch(skin):
        raise CharacterError("appearance.skin must be a valid lowercase skin ID")
    if model not in ("wide", "slim"):
        raise CharacterError("appearance.model must be 'wide' or 'slim'")
    return Character(character_id, name.strip(), language, prompt, skin, model)


def list_characters(packs_dir: Path) -> list[dict]:
    """Return only complete packs and ignore unrelated folders/files."""
    if not packs_dir.is_dir():
        return []
    profiles = []
    for candidate in sorted(packs_dir.iterdir(), key=lambda p: p.name):
        if candidate.is_dir() and CHARACTER_ID.fullmatch(candidate.name):
            try:
                profiles.append(load_character(packs_dir, candidate.name).public_profile())
            except CharacterError:
                continue
    return profiles
