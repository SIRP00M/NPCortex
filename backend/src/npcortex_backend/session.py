"""Bounded, expiring, in-memory chat context with per-session turn ordering.

The store is intentionally ephemeral. A conversation lease protects a session
from eviction while its active/queued request completes and serializes its turns.
"""
from contextlib import contextmanager
from dataclasses import dataclass, field
from threading import Lock
from time import monotonic
from typing import Callable, Iterator


class SessionBusyError(RuntimeError):
    """A conversation has too many pending requests or a turn timed out."""


class SessionCapacityError(RuntimeError):
    """All session slots are occupied by active or queued conversations."""


@dataclass
class _Session:
    last_access: float
    history: list[dict[str, str]] = field(default_factory=list)
    turn_lock: Lock = field(default_factory=Lock)
    requests: int = 0  # Includes requests waiting to acquire turn_lock.


class _Conversation:
    """Operations available only while the session's turn lock is held."""

    def __init__(self, session: _Session, max_messages: int):
        self._session = session
        self._max_messages = max_messages

    def history(self) -> list[dict[str, str]]:
        return [message.copy() for message in self._session.history]

    def add_exchange(self, user_text: str, assistant_text: str) -> None:
        self._session.history.extend((
            {"role": "user", "content": user_text},
            {"role": "assistant", "content": assistant_text},
        ))
        # Keep whole exchanges to avoid orphan user/assistant entries.
        self._session.history = self._session.history[-self._max_messages:]


class SessionStore:
    def __init__(
        self,
        max_messages: int = 12,
        max_sessions: int = 128,
        idle_ttl_seconds: float = 1800,
        max_requests_per_session: int = 2,
        wait_timeout_seconds: float = 20,
        clock: Callable[[], float] = monotonic,
    ):
        if not isinstance(max_messages, int) or isinstance(max_messages, bool) or max_messages < 2 or max_messages % 2:
            raise ValueError("max_messages must be a positive even integer (at least 2)")
        if not isinstance(max_sessions, int) or isinstance(max_sessions, bool) or max_sessions < 1:
            raise ValueError("max_sessions must be a positive integer")
        if idle_ttl_seconds <= 0 or max_requests_per_session < 1 or wait_timeout_seconds < 0:
            raise ValueError("Session TTL and request limits must be positive; wait timeout may be zero")
        self.max_messages = max_messages
        self.max_sessions = max_sessions
        self.idle_ttl_seconds = idle_ttl_seconds
        self.max_requests_per_session = max_requests_per_session
        self.wait_timeout_seconds = wait_timeout_seconds
        self._clock = clock
        self._sessions: dict[tuple[str, str], _Session] = {}
        self._guard = Lock()

    def _expire_locked(self, now: float) -> None:
        for key, session in list(self._sessions.items()):
            if session.requests == 0 and now - session.last_access >= self.idle_ttl_seconds:
                del self._sessions[key]

    @contextmanager
    def conversation(self, player_id: str, character_id: str) -> Iterator[_Conversation]:
        key = (player_id, character_id)
        with self._guard:
            now = self._clock()
            self._expire_locked(now)
            session = self._sessions.get(key)
            if session is None:
                if len(self._sessions) >= self.max_sessions:
                    # LRU eviction only for idle sessions, never an active/queued turn.
                    candidates = [(s.last_access, k) for k, s in self._sessions.items() if s.requests == 0]
                    if not candidates:
                        raise SessionCapacityError("All conversation slots are currently busy")
                    _, oldest_key = min(candidates)
                    del self._sessions[oldest_key]
                session = _Session(last_access=now)
                self._sessions[key] = session
            if session.requests >= self.max_requests_per_session:
                raise SessionBusyError("Conversation already has the maximum pending requests")
            session.requests += 1
            session.last_access = now

        acquired = False
        try:
            acquired = session.turn_lock.acquire(timeout=self.wait_timeout_seconds)
            if not acquired:
                raise SessionBusyError("Conversation is busy; please retry after the previous reply")
            yield _Conversation(session, self.max_messages)
        finally:
            if acquired:
                session.turn_lock.release()
            with self._guard:
                session.requests -= 1
                session.last_access = self._clock()

    def get(self, player_id: str, character_id: str) -> list[dict[str, str]]:
        """Backward-compatible snapshot method for diagnostics and tests."""
        with self._guard:
            self._expire_locked(self._clock())
            if (player_id, character_id) not in self._sessions:
                return []
        with self.conversation(player_id, character_id) as turn:
            return turn.history()

    def add_exchange(self, player_id: str, character_id: str, user_text: str, assistant_text: str) -> None:
        """Backward-compatible append method; normal requests use a single lease."""
        with self.conversation(player_id, character_id) as turn:
            turn.add_exchange(user_text, assistant_text)
