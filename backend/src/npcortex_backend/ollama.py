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

    def chat(self, messages: list[dict[str, str]]) -> str:
        return self._chat(messages, json_format=False)

    def chat_json(self, messages: list[dict[str, str]]) -> str:
        """Optional structured-output capability used only by the B3 coordinator."""
        return self._chat(messages, json_format=True)

    def _chat(self, messages: list[dict[str, str]], *, json_format: bool) -> str:
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            "think": False,
            "options": {"num_ctx": 4096, "num_predict": 160},
        }
        if json_format:
            payload["format"] = "json"
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
