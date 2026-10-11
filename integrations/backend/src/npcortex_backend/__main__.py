from .config import Settings
from .ollama import OllamaProvider
from .service import ChatService
from .session import SessionStore
from .server import serve


def main():
    settings = Settings.from_environment()
    service = ChatService(
        packs_dir=settings.packs_dir,
        model=settings.model,
        provider=OllamaProvider(settings.ollama_url, settings.model),
        sessions=SessionStore(),
    )
    serve(service, settings.host, settings.port)


if __name__ == "__main__":
    main()
