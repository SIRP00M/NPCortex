# Command-origin investigation (G2): can the server tell a human from a data pack?

**Verdict: UNKNOWN for authorization.** A design that probably works exists and is supported by bytecode and by offline tests on
the real `CommandSourceStack`, but it has never been exercised against a live server, so nothing may grant anything on its basis.
No mutating command entry point was added.

## 1. Evidence (Forge 1.20.1-47.4.10, mapped official; `javap -p -c` on the dev jar)

| Path | What the bytecode shows |
|---|---|
| Player chat command | `ServerGamePacketListenerImpl.performChatCommand` builds the stack with `CommandSigningContext$SignedArguments` and calls `Commands.performCommand`, which posts Forge `CommandEvent`. |
| `/function` | `FunctionCommand` runs `source.withSuppressedOutput().withMaximumPermission(2)` then `ServerFunctionManager.execute`. The invoker's `source` and signing context are kept. |
| Tick, load, `schedule` functions | `ServerFunctionManager.getGameLoopSender()` = `MinecraftServer.createCommandSourceStack().withPermission(2).withSuppressedOutput()`; source is the server. |
| Commands inside a function | `CommandFunction$CommandEntry` calls `CommandDispatcher.execute(ParseResults)` directly: `CommandEvent` does NOT fire. |
| `/execute as/at` | `ExecuteCommand` uses `withEntity`; `source`, signing context and suppression are unchanged. |
| Command block | `BaseCommandBlock.createCommandSourceStack()` then `Commands.performPrefixedCommand`; the `source` is the command block. |

Public API of the stack: `public final CommandSource source`, `getEntity()`, `getPlayer()`, `isPlayer()`, `getSigningContext()`,
`hasPermission(int)`. `silent` and the permission level are private and have no accessor. `getPlayer()` is entity based, so
`execute as <other player>` changes it; the requester must be taken from `source`, not from `getPlayer()`.

Offline experiments (`CommandOriginSelfTest`, 26 checks, real `CommandSourceStack`/Brigadier, FIXTURE sources):
- `withSuppressedOutput`, `withMaximumPermission`, `withPermission`, `withEntity` return derived stacks that keep `source` and the
  signing context. `withEntity` to the same entity returns the same instance.
- Therefore "signed" alone cannot separate a typed command from a function the player started; **silence can**, and it is
  observable through public API: a copy of the stack with a probe `CommandSource` receives `sendSuccess` only when output is not
  suppressed (`CommandOrigin.isSilent`, control cases included; the real source is never contacted).
- A plain handler receives the very stack instance it was dispatched with; an execute-style redirect hands over a derived stack
  with the same `source`.

## 2. The diagnostic classifier (`CommandOrigin`, reporting only)

`DIRECT_PLAYER_CHAT` = source is a player AND not silent AND signing context is `SignedArguments`. `PLAYER_SUPPRESSED_CHAIN`
(a function the player started), `PLAYER_NOT_FROM_CHAT_PACKET` (code called the dispatcher with a player stack), `SERVER`
(console, tick/load/scheduled functions), `OTHER` (command block, RCON, entity). `/npcortex m04 observe` logs the kind; no code
path allows or denies on it.

## 3. What remains UNKNOWN (needs the Owner's gameplay experiment)

1. That the player really is the `CommandSource` of its own chat commands, in single player and on a dedicated server.
2. That silence is true for every data-pack route (function from chat, `execute ... run function`, `schedule`, tick/load tags)
   and false for typed chat.
3. Command block, RCON and console classification; commands injected by other mods or plugins; client mods that send chat
   commands automatically (a human cannot be distinguished from a macro by any server-side signal).
4. Behaviour across versions (the signal set is Forge 1.20.1 specific).

Experiment protocol (read-only, one session): in a world with one NPC run `/npcortex m04 observe <npc> 2 minecraft:dirt` (a)
typed in chat, (b) typed as `/execute as @s run npcortex m04 observe ...`, (c) from a data-pack function started by `/function`,
(d) from a tick/`schedule` function, (e) from a command block, (f) from the dedicated-server console / RCON. Collect the
`NPCortex M04 observe origin=...` log lines and compare with section 2. Chat output is expected to be absent for (c)/(d).

## 4. Recommended fail-closed design for mutation (not implemented)

1. Mutation is never reachable from `hasPermission(2)` alone. A mutation request must carry an attested origin that is verified,
   not reported.
2. Preferred origin: the custom network packet path already used by the Manager GUI. The sender is `NetworkEvent.Context.getSender()`
   (a real `ServerPlayer`, not forgeable from a data pack), checked against the operator/allow-list on the server. A command is only
   a convenience front end.
3. If a command front end is kept: require `DIRECT_PLAYER_CHAT`, take the requester from `source` (not the entity), refuse
   `SERVER`, `OTHER`, `PLAYER_SUPPRESSED_CHAIN` and `PLAYER_NOT_FROM_CHAT_PACKET`, and still demand a second typed confirmation
   bound to a one-time nonce shown to the requester.
4. Unknown or ambiguous origin means DENY. Every decision is written to the world-effect ledger with the attested origin kind.
