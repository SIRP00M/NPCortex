# Phase 3 — Forge 1.20.1 in-game conversation bridge

This phase adds a **developer command bridge**, not a spawnable NPC or GUI. The command runs on the Minecraft logical server, makes asynchronous HTTP requests to the local Python backend, and schedules the resulting chat message back on the Minecraft server thread.

## Tested / untested

- [x] Python backend -> Ollama (reported as tested by project owner in the original baseline; **not re-verified against real Ollama in A2**).
- [x] `/health` returns HTTP 200 (backend process is alive; model may still be offline).
- [x] Forge command and Gradle project code authored; scripts prepared.
- [ ] Gradle compilation verified on a PC with JDK 17 and internet access.
- [ ] Commands verified in a running Forge 1.20.1 client.

## Windows setup

Open **PowerShell** in the `NPCortex/` repository root. Execution policy can be configured once per Windows account:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

If an individual file downloaded from the internet is blocked, inspect it before running and then unblock it:

```powershell
Get-Content .\scripts\dev-forge.ps1
Unblock-File .\scripts\dev-forge.ps1
```

Install the 64-bit **JDK 17**, not merely a Java runtime. The Minecraft Forge 1.20.1 documentation specifies JDK 17. If `java -version` returns Java 21 or another release, configure the current shell for the correct JDK before continuing. Example (replace the path with the actual JDK 17 directory):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
```

Avoid copying the placeholder `jdk-17.0.x-hotspot` verbatim; look up the installed directory first:

```powershell
Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory
```

### Terminal 1 — AI backend

```powershell
cd F:\AI\NPCortex
.\scripts\dev-backend.ps1
```

### Terminal 2 — preflight + game

```powershell
cd F:\AI\NPCortex
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

The ZIP already contains the Gradle wrapper. If missing, `dev-forge.ps1` downloads a verified wrapper from the official Forge 47.4.10 MDK. Gradle still needs an internet connection for Forge/Minecraft dependencies.

Do not attempt this phase in your full CurseForge modpack first. `runClient` creates a dedicated Forge development instance.

## In-game tests

Create a **single-player test world with Allow Cheats: ON**, because the commands are restricted to permission level 2 for safety.

1. Type `/npcortex status` — expected: `Backend online | model: qwen3:8b`. **This does not confirm that Ollama can generate text.**
2. Type `/npcortex ask yui Hey Yui, want to explore?` — expected: `NPCortex: Thinking...`, then a generated English reply. This command **does not create or move an NPC**.
3. Type `/npcortex ask unknown Hello` — expected: a readable `Backend returned HTTP 400` error mentioning the unknown character.
4. Stop the Python backend and type `/npcortex status` — expected: a readable connection error (the game should remain responsive).
5. Restart the backend, stop Ollama, then use `/npcortex ask yui Hello` — expected: HTTP 502 with a provider-unavailable message (the game should remain responsive).
6. Send two chat requests quickly to the same character. Confirm no crash, that completed turns are ordered, and that a busy error may appear if the wait threshold is exceeded.

If commands do not appear, check that the Forge game is loading `NPCortex` under the Mods menu and that cheats are enabled in the test world.

If Forge startup fails, paste the **first error section** from PowerShell (not only the last line), the result of `java -version`, and `forge-mod/run/logs/latest.log` when available.

### Server vs client

With a dedicated Minecraft server, the backend URL refers to the **Minecraft server machine**, not the remote player's PC. The Python service is currently unauthenticated and bound to loopback only; do not forward port 8765 to the internet. In-game operator permission is required for commands.

## Expected scope after successful testing

Phase 3 is complete when `runClient` opens, the `/npcortex status` command reports the local backend process, and `/npcortex ask yui ...` displays Qwen's reply in game. Then Phase 4 may introduce an NPC entity and dialog screen.
