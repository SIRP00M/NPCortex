"""Validated environment-based configuration; no secrets are committed."""
from dataclasses import dataclass
import os
from pathlib import Path
from urllib.parse import urlsplit

DEFAULT_PACKS_DIR = Path(__file__).resolve().parents[3] / "character-packs"
DEFAULT_DB_PATH = Path(__file__).resolve().parents[2] / "data" / "npcortex.sqlite3"


def _integer_env(key: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.getenv(key, str(default))
    try:
        result = int(raw)
    except ValueError as exc:
        raise ValueError(f"{key} must be an integer") from exc
    if not minimum <= result <= maximum:
        raise ValueError(f"{key} must be between {minimum} and {maximum}")
    return result


def _url_env(key: str, default: str) -> str:
    raw = os.getenv(key, default).strip()
    try:
        parsed = urlsplit(raw)
        port = parsed.port
    except ValueError as exc:
        raise ValueError(f"{key} must be a valid HTTP(S) URL") from exc
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or
            parsed.username or parsed.password or parsed.path not in ("", "/") or
            parsed.query or parsed.fragment or port == 0):
        raise ValueError(f"{key} must be an HTTP(S) origin without credentials, path or query")
    return raw.rstrip("/")


@dataclass(frozen=True)
class Settings:
    host: str = "127.0.0.1"
    port: int = 8765
    ollama_url: str = "http://127.0.0.1:11434"
    model: str = "qwen3:8b"
    packs_dir: Path = DEFAULT_PACKS_DIR
    db_path: Path = DEFAULT_DB_PATH
    max_transcript_messages: int = 200
    max_sessions: int = 128
    session_ttl_seconds: int = 1800
    max_requests_per_session: int = 2
    session_wait_seconds: int = 20
    max_model_requests: int = 4
    model_wait_seconds: int = 15

    @classmethod
    def from_environment(cls):
        host = os.getenv("NPCORTEX_HOST", "127.0.0.1").strip()
        model = os.getenv("NPCORTEX_MODEL", "qwen3:8b").strip()
        packs_dir = Path(os.getenv("NPCORTEX_PACKS_DIR", str(DEFAULT_PACKS_DIR))).expanduser().resolve()
        if not host or any(char.isspace() for char in host):
            raise ValueError("NPCORTEX_HOST must be a hostname or IP address without whitespace")
        if not 1 <= len(model) <= 128 or any(char.isspace() for char in model):
            raise ValueError("NPCORTEX_MODEL must be 1–128 non-whitespace characters")
        if not packs_dir.is_dir():
            raise ValueError(f"NPCORTEX_PACKS_DIR is not an existing directory: {packs_dir}")
        transcript_raw = _integer_env("NPCORTEX_MAX_TRANSCRIPT_MESSAGES", 200, 2, 2000)
        if transcript_raw % 2:
            raise ValueError("NPCORTEX_MAX_TRANSCRIPT_MESSAGES must be even")
        return cls(
            host=host,
            port=_integer_env("NPCORTEX_PORT", 8765, 1, 65535),
            ollama_url=_url_env("NPCORTEX_OLLAMA_URL", "http://127.0.0.1:11434"),
            model=model,
            packs_dir=packs_dir,
            db_path=Path(os.getenv("NPCORTEX_DB_PATH", str(DEFAULT_DB_PATH))).expanduser().resolve(),
            max_transcript_messages=transcript_raw,
            max_sessions=_integer_env("NPCORTEX_MAX_SESSIONS", 128, 1, 4096),
            session_ttl_seconds=_integer_env("NPCORTEX_SESSION_TTL_SECONDS", 1800, 1, 86400),
            max_requests_per_session=_integer_env("NPCORTEX_MAX_SESSION_REQUESTS", 2, 1, 8),
            session_wait_seconds=_integer_env("NPCORTEX_SESSION_WAIT_SECONDS", 20, 0, 90),
            max_model_requests=_integer_env("NPCORTEX_MAX_MODEL_REQUESTS", 4, 1, 32),
            model_wait_seconds=_integer_env("NPCORTEX_MODEL_WAIT_SECONDS", 15, 0, 90),
        )
