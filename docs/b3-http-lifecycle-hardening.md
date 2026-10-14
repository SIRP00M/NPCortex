# B3-B — HTTP Request Lifecycle Hardening (candidate)

## Origin and audit

On Windows Python 3.13, `test_stability.HttpTest.test_malformed_and_large_json` intermittently failed with `ConnectionAbortedError: [WinError 10053]`. The failing subtest's label `raw=b'xxxxxxxxxx'` was just a **10-byte preview** of its actual `b'x' * 8193` body. The previous HTTP implementation rejected `Content-Length > 8192` immediately without consuming pending TCP request bytes. The earlier B2-C fix handled small unknown routes (`/nope`) but did not cover HTTP 413.

This is a strong, evidence-based hypothesis for the intermittent reset; confirm the fix on the actual Windows environment before marking the Quality Gate complete.

## What changed

- Reject oversized POST bodies as before with **HTTP 413** and JSON `{ "error": ..., "code": "request_too_large" }`.
- Before replying, drain the rejected body **in 8 KiB chunks**, with a **1 MiB maximum total drain** and **2-second wall-clock deadline**. Nothing rejected is parsed or persisted, and the maximum *accepted* JSON body remains **8192 bytes**.
- Apply the same bounded rejection drain to unknown POST routes, preserving their HTTP 404 response.
- For unsupported transfer coding / malformed framing / oversized bodies exceeding the drain cap, explicitly close the connection instead of attempting unbounded or ambiguous reads. For huge/slow/incomplete requests, the client **may still see a connection reset**; these protective cases are intentionally not guaranteed to deliver JSON. This is a local development server, not an internet-facing service.
- HTTP successful response formats and B1/B2/B3 API endpoints remain unchanged. B3 Social Runtime, Qwen, SQLite schema v3 and Forge Java source are untouched.

## Acceptance Tests

Stop/backup the SQLite DB before replacing a running backend; no schema migration occurs in this fix.

From the repository root (PowerShell, Python 3.13):

```powershell
.\scripts\test-b3-http-lifecycle.ps1 -Repeat 3
.\scripts\test-b3-regression.ps1 -Repeat 3
```

Targeted HTTP suite contains 5 tests that exercise:

- 413 for repeated POST bodies of 8193, 16384, 65536 and 1048576 bytes; never `WinError 10053` in those bounded cases.
- 404 for unknown POST endpoints with bodies, including oversized ones.
- 400 for malformed JSON, unsupported Transfer-Encoding header and a POST without Content-Length.
- Correct 200 JSON from `/v1/chat` after errors, including the original A2 payload fields.

Expected: 5/5 targeted, then 116/116 full regression, in **each of 3 runs**. The injected `SQLite operation failed` and `Ollama request failed` tracebacks in a couple of tests are expected when those tests end in `ok`. The final lines must show `OK` and `PASS`.

Then (in separate PowerShell windows):

```powershell
.\scripts\dev-backend.ps1
# In another terminal:
.\scripts\test-b3-social.ps1
```

For restart/resume, follow `docs/b3-windows-verification.md`. Forge regression remains via `./scripts/phase3-check.ps1` and `./scripts/dev-forge.ps1 -Task build` using **JDK 17**.

**Quality Gate:** This package is a candidate until Windows HTTP/full regressions and the real-Qwen B3 integration are accepted by the user. No GitHub operations were performed.
