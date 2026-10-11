"""In-memory bounded conversation history; deliberately not permanent memory."""
from threading import Lock

class SessionStore:
    def __init__(self, max_messages: int = 12):
        self._histories: dict[tuple[str, str], list[dict[str, str]]] = {}
        self._guard = Lock()
        self.max_messages = max_messages

    def get(self, player_id: str, character_id: str) -> list[dict[str, str]]:
        with self._guard:
            return [entry.copy() for entry in self._histories.get((player_id, character_id), [])]

    def add_exchange(self, player_id: str, character_id: str, user_text: str, assistant_text: str) -> None:
        with self._guard:
            key = (player_id, character_id)
            history = self._histories.setdefault(key, [])
            history.extend((
                {"role": "user", "content": user_text},
                {"role": "assistant", "content": assistant_text},
            ))
            self._histories[key] = history[-self.max_messages:]
