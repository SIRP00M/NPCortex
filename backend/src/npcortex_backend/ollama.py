"""Ollama adapter; add future LLM provider adapters next to this file."""
import json
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

class ModelError(RuntimeError):
    pass

class OllamaProvider:
    def __init__(self, base_url: str, model: str):
        self.base_url = base_url.rstrip("/")
        self.model = model

    def chat_structured(self, messages: list[dict[str, str]], schema: dict) -> str:
        """Ask Ollama for a validated JSON result; strict parsing lives in agent.py."""
        return self._request(messages, schema)

    def chat(self, messages: list[dict[str, str]]) -> str:
        return self._request(messages)

    def _request(self, messages: list[dict[str, str]], schema: dict | None = None) -> str:
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            "think": False,
            "options": {"num_ctx": 4096, "num_predict": 160},
        }
        if schema is not None:
            payload["format"] = schema
            payload["options"]["num_predict"] = 240
        req = Request(
            self.base_url + "/api/chat",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urlopen(req, timeout=90) as response:
                data = json.load(response)
            reply = data["message"]["content"]
            if not isinstance(reply, str) or not reply.strip():
                raise ModelError("Ollama returned an empty response")
            return reply.strip()
        except (HTTPError, URLError, TimeoutError, OSError, ValueError, KeyError, TypeError) as exc:
            raise ModelError(f"Ollama request failed: {exc}") from exc
