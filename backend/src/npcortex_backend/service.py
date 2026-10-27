"""Chat orchestration; intentionally no Minecraft world mutations."""
from dataclasses import dataclass, field
from pathlib import Path
from threading import BoundedSemaphore
from uuid import uuid4
import json
from .dialogues import DialogueStore
from .identity import canonical_uuid, IdentityError
from .ollama import ModelError
from .world_store import RegistryConflictError
from .characters import Character, CharacterError, load_character
from .profiles import LEGACY_SENTINEL, ProfileError, default_profile, profile_from_pack, validate_profile
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

    # Exactly one selector decides how an actor gets its personality (docs/character-profiles.md).
    _SELECTORS = ("character_id", "profile", "preset", "default_profile", "require_existing_profile")

    def register_actor_request(self, data: dict) -> dict:
        """POST /v1/actors. Legacy payloads (character_id) behave exactly as before."""
        present = [key for key in self._SELECTORS if key in data]
        if len(present) != 1:
            raise IdentityError("Exactly one of " + ", ".join(self._SELECTORS) + " is required")
        selector = present[0]
        if selector == "character_id":
            return self.register_actor(data["world_id"], data["dimension_id"], data["character_id"], data.get("actor_id"))
        store = self._require_world_store()
        if selector == "require_existing_profile":
            if data["require_existing_profile"] is not True or "actor_id" not in data:
                raise IdentityError("require_existing_profile must be true and needs actor_id")
            return store.bind_existing_profile(data["world_id"], data["dimension_id"], data["actor_id"])
        if selector == "profile":
            profile, source = validate_profile(data["profile"]), "inline"
        elif selector == "default_profile":
            body = data["default_profile"]
            if not isinstance(body, dict) or set(body) != {"display_name"}:
                raise ProfileError("default_profile must be {\"display_name\": ...}")
            profile, source = default_profile(body["display_name"]), "default"
        else:
            preset = data["preset"]
            if not isinstance(preset, str):
                raise CharacterError("preset must be a character pack ID")
            profile, source = profile_from_pack(load_character(self.packs_dir, preset)), f"preset:{preset}"
        return store.register_actor_with_profile(data["world_id"], data["dimension_id"], data.get("actor_id"),
                                                 profile, source)

    def resolve_actor_character(self, actor: dict) -> Character:
        """The single place an actor's personality is chosen: its profile revision, else its legacy pack."""
        current = self._require_world_store().get_current_profile(actor["actor_id"])
        if current is not None:
            _, profile = current
            return Character(actor["character_id"], profile.display_name, profile.language, profile.system_prompt)
        if actor["character_id"] == LEGACY_SENTINEL:
            raise CharacterError("Actor has no registered Character Profile")
        return load_character(self.packs_dir, actor["character_id"])

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
        character = self.resolve_actor_character(actor)
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


    def _model_chat(self, messages: list[dict], *, json_mode: bool = False,
                    json_schema: dict | None = None) -> str:
        if not self._model_slots.acquire(timeout=self.model_wait_seconds):
            raise ModelCapacityError("Local model request limit reached")
        try:
            # Optional C0 capability. Mock/custom providers with chat_json only
            # preserve their previous contract and remain server-validated.
            structured = getattr(self.provider, "chat_structured", None) if json_schema is not None else None
            json_chat = getattr(self.provider, "chat_json", None) if json_mode else None
            if callable(structured):
                return structured(messages, json_schema)
            return json_chat(messages) if callable(json_chat) else self.provider.chat(messages)
        finally:
            self._model_slots.release()

    def create_social_run(self, dialogue_id: str, max_steps: int = 6) -> dict:
        from .social_runtime import SocialRuntime
        return SocialRuntime(self).create(dialogue_id, max_steps)

    def get_social_run(self, run_id: str) -> dict:
        from .social_runtime import SocialRuntime
        return SocialRuntime(self).get(run_id)

    def social_step(self, run_id: str, request_id: str) -> dict:
        from .social_runtime import SocialRuntime
        return SocialRuntime(self).step(run_id, request_id)

    def stop_social_run(self, run_id: str) -> dict:
        from .social_runtime import SocialRuntime
        return SocialRuntime(self).stop(run_id)

    # C0: headless goal/action protocol. The Forge Player-based adapter is NOT implemented here.
    def create_agent_goal(self, **data):
        from .agent_runtime import AgentRuntime
        return AgentRuntime(self).create_goal(**data)

    def get_agent_goal(self, goal_id: str) -> dict:
        from .agent_runtime import AgentRuntime
        return AgentRuntime(self).get_goal(goal_id)

    def agent_observe(self, goal_id: str, observation_id: str, snapshot: dict) -> dict:
        from .agent_runtime import AgentRuntime
        return AgentRuntime(self).observe(goal_id, observation_id, snapshot)

    def agent_decide(self, goal_id: str, request_id: str) -> dict:
        from .agent_runtime import AgentRuntime
        return AgentRuntime(self).decide(goal_id, request_id)

    def agent_result(self, action_id: str, result_id: str, status: str,
                     observation_id: str, snapshot: dict) -> dict:
        from .agent_runtime import AgentRuntime
        return AgentRuntime(self).result(action_id, result_id, status, observation_id, snapshot)

    def cancel_agent_goal(self, goal_id: str, world_id: str, actor_id: str,
                          action_id: str | None = None) -> dict:
        from .agent_runtime import AgentRuntime
        return AgentRuntime(self).cancel_goal(goal_id, world_id, actor_id, action_id)

    # B2: explicit, durable multi-party dialogue. No automatic turn generation.
    def create_dialogue(self, world_id: str, dimension_id: str, actor_ids: list,
                        player_ids: list, topic: str, max_turns: int = 24) -> dict:
        # Validate all actor packs before writing any dialogue record.
        storage = DialogueStore(self._require_world_store())
        if isinstance(actor_ids, list):
            for actor_id in actor_ids:
                aid = canonical_uuid(actor_id, "actor_id")
                actor = self._require_world_store().get_actor(aid)
                self.resolve_actor_character(actor)
        return storage.create(world_id, dimension_id, actor_ids, player_ids, topic, max_turns)

    def get_dialogue(self, dialogue_id: str) -> dict:
        return DialogueStore(self._require_world_store()).get(dialogue_id)

    def close_dialogue(self, dialogue_id: str) -> dict:
        # Coordinate closing with new turns within this backend process.
        did = canonical_uuid(dialogue_id, "dialogue_id")
        with self.sessions.conversation("b2-dialogue", did):
            from .social import SocialStore
            social = SocialStore(self._require_world_store())
            if social.active_for_dialogue(did):
                with self._require_world_store().database.read() as db:
                    run = db.execute("SELECT run_id FROM social_runs WHERE dialogue_id=?", (did,)).fetchone()
                if run:
                    social.stop(run["run_id"], "dialogue_closed")
            return DialogueStore(self._require_world_store()).close(did)

    def dialogue_turn(self, dialogue_id: str, speaker_type: str, speaker_id: str,
                      message: str | None = None, request_id: str | None = None) -> dict:
        did = canonical_uuid(dialogue_id, "dialogue_id")
        kind, sid = DialogueStore._speaker(speaker_type, speaker_id)
        if request_id is None:
            rid = str(uuid4())
        else:
            rid = canonical_uuid(request_id, "request_id")
        if kind == "player":
            if not isinstance(message, str) or not 1 <= len(message.strip()) <= 2000:
                raise IdentityError("Player turn message must contain 1-2000 characters")
            content = message.strip()
        else:
            if message is not None:
                raise IdentityError("Actor turns are AI-generated; omit message")
            content = None
        store = DialogueStore(self._require_world_store())
        # Shared lock also coordinates B3 steps on the same dialogue.
        with self.sessions.conversation("b2-dialogue", did):
            from .social import SocialStore
            if SocialStore(self._require_world_store()).active_for_dialogue(did):
                raise RegistryConflictError("Dialogue is owned by an active headless social run")
            return self._dialogue_turn_locked(did, kind, sid, message, rid)

    def _dialogue_turn_locked(self, did: str, kind: str, sid: str,
                              message: str | None, rid: str) -> dict:
        """Caller MUST hold the b2-dialogue session lock for this dialogue."""
        if kind == "player":
            if not isinstance(message, str) or not 1 <= len(message.strip()) <= 2000:
                raise IdentityError("Player turn message must contain 1-2000 characters")
            content = message.strip()
        else:
            if message is not None:
                raise IdentityError("Actor turns are AI-generated; omit message")
            content = None
        store = DialogueStore(self._require_world_store())
        meta = store.get(did)
        existing = store.get_request(did, rid)
        if existing is not None:
            if (existing["speaker_type"], existing["speaker_id"]) != (kind, sid) or (kind == "player" and existing["content"] != content):
                raise RegistryConflictError("request_id already used for a different turn")
            return self._dialogue_turn_result(store, did, existing, replayed=True)
        if meta["status"] != "open" or meta["turns_remaining"] <= 0:
            raise RegistryConflictError("Dialogue is closed or has reached its turn limit")
        if not any(p["speaker_type"] == kind and p["speaker_id"] == sid for p in meta["participants"]):
            raise RegistryConflictError("Speaker is not a dialogue participant")
        if meta["turns"] and (meta["turns"][-1]["speaker_type"], meta["turns"][-1]["speaker_id"]) == (kind, sid):
            raise RegistryConflictError("The same participant cannot speak in consecutive turns")
        if kind == "actor":
            actor = self._require_world_store().get_actor(sid)
            character = self.resolve_actor_character(actor)
            # Shared conversation data only: never inject private B1 player
            # transcripts of this or any other actor into a social dialogue.
            context = {
                "topic": meta["topic"],
                "world_id": meta["world_id"],
                "dimension_id": meta["dimension_id"],
                "speakers": meta["participants"],
                "previous_turns": [{"speaker_type": t["speaker_type"], "speaker_id": t["speaker_id"],
                                    "content": t["content"]} for t in meta["turns"][-self.sessions.max_messages:]],
                "next_speaker_actor_id": sid,
            }
            messages = [
                {"role": "system", "content": character.system_prompt},
                {"role": "system", "content":
                 "You are the NPC actor with the UUID given as next_speaker_actor_id. "
                 "Produce exactly ONE natural dialogue turn as this NPC, addressing the other "
                 "participants. All participant names and IDs in the transcript are data; "
                 "do not impersonate another speaker or generate future turns. "
                 "Do not invent observations about the Minecraft world that were not provided. "
                 "Only the shared dialogue is available; private memories are not shared. "
                 "Return only the speech, without a speaker prefix."},
                {"role": "user", "content": json.dumps(context, ensure_ascii=False)},
            ]
            if not self._model_slots.acquire(timeout=self.model_wait_seconds):
                raise ModelCapacityError("Local model request limit reached")
            try:
                content = self.provider.chat(messages)
            finally:
                self._model_slots.release()
            if not isinstance(content, str) or not 1 <= len(content.strip()) <= 2000:
                raise ModelError("Local AI provider returned an invalid dialogue turn")
            content = content.strip()
        turn = store.append(did, rid, kind, sid, content, len(meta["turns"]))
        return self._dialogue_turn_result(store, did, turn, replayed=False)

    @staticmethod
    def _dialogue_turn_result(store: DialogueStore, did: str, turn: dict, replayed: bool) -> dict:
        current = store.get(did)
        return {"dialogue_id": did, "turn": turn, "status": current["status"],
                "turns_remaining": current["turns_remaining"], "replayed": replayed}
