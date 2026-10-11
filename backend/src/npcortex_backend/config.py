"""Environment-based config; secrets and user-specific paths are not committed."""
from dataclasses import dataclass
import os
from pathlib import Path

DEFAULT_PACKS_DIR = Path(__file__).resolve().parents[3] / "character-packs"

@dataclass(frozen=True)
class Settings:
    host: str = "127.0.0.1"
    port: int = 8765
    ollama_url: str = "http://127.0.0.1:11434"
    model: str = "qwen3:8b"
    packs_dir: Path = DEFAULT_PACKS_DIR
    data_dir: Path = Path(__file__).resolve().parents[3] / "backend" / "data"

    @classmethod
    def from_environment(cls):
        return cls(
            host=os.getenv("NPCORTEX_HOST", "127.0.0.1"),
            port=int(os.getenv("NPCORTEX_PORT", "8765")),
            ollama_url=os.getenv("NPCORTEX_OLLAMA_URL", "http://127.0.0.1:11434"),
            model=os.getenv("NPCORTEX_MODEL", "qwen3:8b"),
            packs_dir=Path(os.getenv("NPCORTEX_PACKS_DIR", str(DEFAULT_PACKS_DIR))).resolve(),
            data_dir=Path(os.getenv("NPCORTEX_DATA_DIR", str(cls.data_dir))).resolve(),
        )
