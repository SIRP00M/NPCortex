# NPCortex B3-B — HTTP Request Lifecycle Hardening

Base: B3 AI-Driven Social Runtime Full Repository ZIP (74 files). Scope: Windows 10053 stability bug, without B3 feature expansion.

- `backend/src/npcortex_backend/server.py`: bounded, chunked discard of rejected oversized POST bodies before emitting HTTP 413; same discard for unknown route 404; explicit close for invalid framing, unknown-length or excessive/slow requests; JSON API contracts unchanged.
- `backend/tests/test_http_request_lifecycle.py`: 5 focused HTTP 400/404/413 tests, 116 total including existing 111 tests.
- `scripts/test-b3-http-lifecycle.ps1`: strict Windows targeted regression runner with repeat count.
- `docs/b3-http-lifecycle-hardening.md`: audit evidence, trade-offs, acceptance criteria and Windows procedure.
- README: candidate and test entrypoints.

No SQLite migration. No changes to AI behavior, social scheduling, Ollama integration, Forge Java source or existing API routes. No GitHub operations.

**Limit:** Rejected content longer than the bounded 1 MiB drain budget, slow or malformed incoming framing may still lead to a TCP reset due to intentional connection closure. This is preferred to unrestricted request-body consumption on a localhost development HTTP server.
