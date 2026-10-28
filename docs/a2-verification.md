# NPCortex A2 — Test report and Windows acceptance checklist

## Environment and verification boundary

This release is a full repository based on the supplied `NPCortex-Development.zip`. Source edits stay within A2 baseline stability work; no NPC entity, new agent runtime or other feature work is included. Original ZIP extraction integrity was checked before edits.

The local tool runtime uses **Python 3.13.5 and JDK 21**. It does not contain the JDK 17 toolchain and cannot reach `services.gradle.org` to download Gradle 8.8. The `./gradlew --offline build --no-daemon` attempt fails at the initial Gradle distribution download with `java.net.UnknownHostException: services.gradle.org`; no Forge Java compilation was completed. Consequently **Forge Build: NOT VERIFIED** and **Minecraft Runtime: NOT VERIFIED**; this is not a completed gameplay Quality Gate. Backend tests use mock providers only; real Ollama and Qwen generation remain **NOT VERIFIED** here.

## Automated tests

**Result in this environment: 22 / 22 Python tests PASS** (original five plus 17 A2 regressions). A separate Java 17 source-level smoke compilation of `ChatBridge.java` succeeded using minimal stub dependencies with `javac --release 17`; this is **not** a genuine Forge or Gson dependency build.

From repository root:

```powershell
python -m unittest discover -s backend/tests -v
```

Coverage includes the original character validation, session isolation/history, and ChatService tests, plus new tests for serialized concurrent turns, waiter timeout, model concurrency, rollback on provider failure, TTL and LRU behavior, active-session protection, config error handling, live-localhost HTTP status/success/error validation using a mock provider, plus live-localhost mock Ollama transport/payload and malformed-response tests.

## Windows setup (PowerShell)

**1. Install/check prerequisites:** 64-bit JDK 17, Python 3.10+, Ollama, model `qwen3:8b`; internet for first Gradle dependency resolution. Set your current shell to the correct JDK 17 if needed:

```powershell
Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.x.y-hotspot' # replace with your actual directory
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
ollama pull qwen3:8b
```

**2. Backend window (repo root):**

```powershell
cd C:\path\to\NPCortex                  # adapt to the unzipped repository location
python -m unittest discover -s backend/tests -v
.\scripts\dev-backend.ps1
```

Optional in a separate terminal: `Invoke-RestMethod http://127.0.0.1:8765/health`. This checks **only** the Python process, not Ollama readiness.

**3. Forge window (repo root):**

```powershell
cd C:\path\to\NPCortex                  # adapt to the unzipped repository location
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

The ZIP already contains `forge-mod/gradlew.bat` and the wrapper JAR/properties. `dev-forge.ps1` requires `java` and `javac` from JDK 17 and fails on other versions by design.

## In-game acceptance checklist (owner-run)

Create a **clean Minecraft 1.20.1 Forge 47.4.10 single-player test world with cheats enabled**, separate from the main modpack. Keep the Python backend and Ollama running.

- [ ] `runClient` launches without Forge errors; Mods screen shows NPCortex.
- [ ] `/npcortex status` reports online and the configured model; no game freeze.
- [ ] `/npcortex ask yui Hello Yui!` returns a real Qwen reply; backend receives the request.
- [ ] `/npcortex ask yui Do you remember what I said?` receives conversation context.
- [ ] `/npcortex ask guide Hello` works independently from Yui history.
- [ ] `/npcortex ask unknown Hello` shows a human-readable HTTP 400 message (no game crash).
- [ ] Stop Ollama, then send a valid chat request. Verify HTTP 502 and a readable error; restart Ollama afterward.
- [ ] Stop the Python backend, then `/npcortex status`. Verify a connection error and responsive client.
- [ ] Send two chats quickly to the same character; observe ordered context or a bounded HTTP 429 busy response (rather than mixed conversation, freeze or stuck request).
- [ ] Repeat chat/status commands and review `forge-mod/run/logs/latest.log` and the backend terminal for unexpected errors.

If a failure occurs, collect the exact command, in-game result, `latest.log`, Python console output and `java -version`. Return to Audit → alternatives → new approval before out-of-scope changes.

## Quality Gate status

| Gate | Status |
|---|---|
| ZIP/source audit | Completed |
| Python tests + localhost HTTP mock regression | **PASS: 22/22** |
| Actual Ollama/Qwen generation | Not verified here; owner check required |
| Java 17 source syntax smoke, stub dependencies | **PASS**; not a Forge build |
| Java / ForgeGradle build using real JDK 17 and dependencies | **Blocked here**: no Gradle distribution download / no JDK 17 |
| Single-player Minecraft gameplay | **Pending owner test** |
| GitHub commit/push | **Not performed**; owner only, after all gates |

**A2 is an implementation candidate until its complete owner-run Quality Gate passes.**
