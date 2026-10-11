"""Framework-level orchestration; nothing here moves a Minecraft NPC."""
from dataclasses import dataclass
from pathlib import Path
from .characters import load_character
from .session import SessionStore

@dataclass
class ChatService:
    packs_dir: Path
    model: str
    provider: object
    sessions: SessionStore

    def chat(self, character_id: str, player_id: str, message: str) -> dict[str, str]:
        character = load_character(self.packs_dir, character_id)
        messages = [
            {"role": "system", "content": character.system_prompt},
            *self.sessions.get(player_id, character_id),
            {"role": "user", "content": message},
        ]
        reply = self.provider.chat(messages)
        self.sessions.add_exchange(player_id, character_id, message, reply)
        return {
            "character_id": character.character_id,
            "character_name": character.display_name,
            "reply": reply,
            "model": self.model,
        }
