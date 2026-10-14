from .config import Settings
from .ollama import OllamaProvider
from .service import ChatService
from .session import SessionStore
from .server import serve
from .world_store import WorldStore


def main():
    settings = Settings.from_environment()
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
        world_store=WorldStore(settings.db_path, settings.max_transcript_messages),
    )
    serve(service, settings.host, settings.port)


if __name__ == "__main__":
    main()
