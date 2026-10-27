from .config import Settings
from .cplus_local_auth import initialize_secret
from .ollama import OllamaProvider
from .service import ChatService
from .session import SessionStore
from .server import serve
from .world_store import SchemaVersionError, WorldStore


def main():
    settings = Settings.from_environment()
    # Bootstrap before serving HTTP. This only enables local C-F1 diagnostics.
    # A missing/invalid secret must never disable unrelated chat or Qwen APIs.
    try:
        source = initialize_secret()
        print(f"C-F1 local diagnostic credential: {source} (secret never printed)", flush=True)
    except (OSError, ValueError) as exc:
        print(f"C-F1 local diagnostic authentication unavailable: {exc}", flush=True)
    try:
        world_store = WorldStore(settings.db_path, settings.max_transcript_messages)
    except SchemaVersionError as exc:
        # Includes the explicit v5 opt-in refusal; the database file has not been touched.
        raise SystemExit(f"NPCortex backend not started: {exc}")
    service = ChatService(
        packs_dir=settings.packs_dir,
        model=settings.model,
        provider=OllamaProvider(settings.ollama_url, settings.model),
        sessions=SessionStore(
            max_sessions=settings.max_sessions,
            idle_ttl_seconds=settings.session_ttl_seconds,
            max_requests_per_session=settings.max_requests_per_session,
            wait_timeout_seconds=settings.session_wait_seconds,
        ),
        max_model_requests=settings.max_model_requests,
        model_wait_seconds=settings.model_wait_seconds,
        world_store=world_store,
    )
    serve(service, settings.host, settings.port)


if __name__ == "__main__":
    main()
