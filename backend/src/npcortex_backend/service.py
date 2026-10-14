"""Chat orchestration; intentionally no Minecraft world mutations."""
from dataclasses import dataclass, field
from pathlib import Path
from threading import BoundedSemaphore
from .characters import load_character
from .session import SessionStore
from .world_store import WorldStore


class ModelCapacityError(RuntimeError):
    """The local model is at its configured concurrent-request limit."""


@dataclass
class ChatService:
    packs_dir: Path
    model: str
    provider: object
    sessions: SessionStore
    world_store: WorldStore | None = None
    max_model_requests: int = 4
    model_wait_seconds: float = 15
    _model_slots: BoundedSemaphore = field(init=False, repr=False)

    def __post_init__(self):
        if self.max_model_requests < 1 or self.model_wait_seconds < 0:
            raise ValueError("Invalid model concurrency limits")
        self._model_slots = BoundedSemaphore(self.max_model_requests)

    def chat(self, character_id: str, player_id: str, message: str) -> dict[str, str]:
        # Invalid characters fail before occupying session or model resources.
        character = load_character(self.packs_dir, character_id)
        with self.sessions.conversation(player_id, character_id) as turn:
            messages = [
                {"role": "system", "content": character.system_prompt},
                *turn.history(),
                {"role": "user", "content": message},
            ]
            if not self._model_slots.acquire(timeout=self.model_wait_seconds):
                raise ModelCapacityError("Local model request limit reached")
            try:
                reply = self.provider.chat(messages)
            finally:
                self._model_slots.release()
            # Never commit a failed model call to conversation history.
            turn.add_exchange(message, reply)
        return {
            "character_id": character.character_id,
            "character_name": character.display_name,
            "reply": reply,
            "model": self.model,
        }

    def register_world(self, name: str, world_id: str | None = None) -> dict:
        return self._require_world_store().register_world(name, world_id)

    def register_actor(self, world_id: str, dimension_id: str, character_id: str,
                       actor_id: str | None = None) -> dict:
        # Reject invalid/missing packs before persisting actor identities.
        if not isinstance(character_id, str):
            from .identity import IdentityError
            raise IdentityError("character_id must be a character pack ID")
        load_character(self.packs_dir, character_id)
        return self._require_world_store().register_actor(world_id, dimension_id, character_id, actor_id)

    def _require_world_store(self) -> WorldStore:
        if self.world_store is None:
            raise RuntimeError("B1 WorldStore has not been configured")
        return self.world_store

    def chat_actor(self, world_id: str, dimension_id: str, actor_id: str,
                   player_id: str, message: str) -> dict:
        """Durable player-to-actor dialogue, *not* NPC-to-NPC orchestration."""
        from .identity import canonical_uuid, dimension_id as validate_dimension, player_id as validate_player
        wid = canonical_uuid(world_id, "world_id")
        aid = canonical_uuid(actor_id, "actor_id")
        validate_dimension(dimension_id)
        validate_player(player_id)
        storage = self._require_world_store()
        actor = storage.get_actor(aid)
        if actor["world_id"] != wid or actor["dimension_id"] != dimension_id:
            from .world_store import RegistryConflictError
            raise RegistryConflictError("Actor does not belong to the requested world/dimension")
        character = load_character(self.packs_dir, actor["character_id"])
        # The A2 session lease is only a lock/queue; transcript source of truth is SQLite.
        # Lock scope is per world + actor + player, not character template.
        with self.sessions.conversation(f"{wid}/{player_id}", f"actor:{aid}"):
            cid = storage.conversation_for(wid, aid, player_id, dimension_id)
            messages = [
                {"role": "system", "content": character.system_prompt},
                *storage.history(cid, self.sessions.max_messages),
                {"role": "user", "content": message},
            ]
            if not self._model_slots.acquire(timeout=self.model_wait_seconds):
                raise ModelCapacityError("Local model request limit reached")
            try:
                reply = self.provider.chat(messages)
            finally:
                self._model_slots.release()
            storage.append_exchange(cid, message, reply)
        return {"actor_id": aid, "world_id": wid, "dimension_id": dimension_id,
                "conversation_id": cid, "character_id": character.character_id,
                "character_name": character.display_name, "reply": reply, "model": self.model}
