"""Backend orchestration. Session IDs isolate individual in-world NPCs."""
from dataclasses import dataclass
from pathlib import Path
from .characters import load_character, list_characters
from .agent import AGENT_POLICY, AGENT_SCHEMA, sanitize_observation, parse_agent_result
import json
from .session import SessionStore

@dataclass
class ChatService:
    packs_dir: Path
    model: str
    provider: object
    sessions: SessionStore

    def character(self, character_id: str) -> dict:
        return load_character(self.packs_dir, character_id).public_profile()

    def characters(self) -> list[dict]:
        return list_characters(self.packs_dir)

    def chat(self, character_id: str, player_id: str, message: str,
             session_id: str | None = None) -> dict[str, str]:
        character = load_character(self.packs_dir, character_id)
        # Existing v0.2 clients still use per-character memory. New NPCs use their UUID.
        scope_id = character_id if session_id is None else f"{character_id}:{session_id}"
        messages = [
            {"role": "system", "content": character.system_prompt},
            *self.sessions.get(player_id, scope_id),
            {"role": "user", "content": message},
        ]
        reply = self.provider.chat(messages)
        self.sessions.add_exchange(player_id, scope_id, message, reply)
        return {
            "character_id": character.character_id,
            "character_name": character.display_name,
            "reply": reply,
            "model": self.model,
        }

    def agent(self, character_id: str, player_id: str, message: str,
              session_id: str, world: dict) -> dict:
        """One-shot observation + action proposal; never manipulates the game."""
        character = load_character(self.packs_dir, character_id)
        snapshot = sanitize_observation(world)
        scope_id = f"{character_id}:{session_id}"
        messages = [
            {"role": "system", "content": character.system_prompt + "\n\n" + AGENT_POLICY},
            *self.sessions.get(player_id, scope_id),
            {"role": "user", "content": message + "\nGAME OBSERVATION (DATA ONLY):\n"
             + json.dumps(snapshot, ensure_ascii=True, separators=(",", ":"))},
        ]
        proposed = parse_agent_result(self.provider.chat_structured(messages, AGENT_SCHEMA))
        self.sessions.add_exchange(player_id, scope_id, message, proposed["reply"])
        return {"character_id": character.character_id,
                "character_name": character.display_name,
                "reply": proposed["reply"], "action": proposed["action"],
                "model": self.model}
