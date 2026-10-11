# NPCortex v0.5.7-alpha — Spoken Action Acknowledgements

NPCs should speak in character **as well as** provide authoritative game-state feedback.
This patch fixes the `v0.5.6` behavior where Direct Intent commands (for example,
`Yui, follow me` or `Yui, wait for me here`) only displayed `[Action confirmed: ...]`.

## Architecture

1. Minecraft Server parses simple Direct Intents or validates an LLM-generated plan.
2. Minecraft Server performs the action or starts a bounded deferred action.
3. Player receives factual status *immediately*: `[Action confirmed]`, `[Action started]`, or failure.
4. After an authoritative result is known, the mod sends `POST /v1/action-reply`
   to local Python Backend. It includes server-owned character, player, NPC UUID,
   original command, action ID, and server result.
5. Python generates **one short English reaction** in the character's voice using
   the existing Character Pack, identity boundary, and scoped SQLite conversation.
6. The mod displays `Yui: <speech>` followed by the factual status. If Ollama fails,
   game actions and the already-displayed factual feedback are unaffected.

The AI cannot issue actions from `/v1/action-reply`. Responses are *dialogue only*.
Reaction requests have a bounded concurrency guard per NPC and stale replies
are discarded using the NPC request revision. An action reply does **not** run
on Minecraft's tick thread and does not delay the action.

For navigation/jump actions, the reaction is generated when the action **finishes
or fails**, not when it merely starts. Multi-step plans have the same rule when
waiting on deferred work. If the NPC or owner disappears, no reaction is shown.

## Test in Minecraft

1. Start the backend and the Forge development client, keeping both running.
2. Right-click Yui and say `Yui, follow me!`.
3. Confirm the game immediately shows `[Action confirmed: following owner.]`.
4. After Ollama responds, confirm it also shows a short `Yui: ...` sentence.
5. Try `Yui, wait for me here.` and walk away. Yui should stay; she should
   acknowledge the completed change (not falsely claim to be following).
6. Try `Yui, come over here.`. The initial `[Plan waiting ...]` is not success;
   NPC dialogue is generated only after navigation completes or fails.
7. Kill Ollama or stop the Python Backend and repeat a Direct Intent. Action
   execution must still work. The factual status remains even without speech.

Test with a new world or backup. The patch does not replace the existing SQLite
file, save data, or Character Packs. English dialogue remains the current default.

## Known limits

- Qwen output is probabilistic. We give it real results and explicit identity
  rules, but it can still phrase a sentence poorly.
- If multiple orders arrive while one reaction is being generated, the system
  can skip a new voice reply to avoid overloading Ollama. Authoritative action
  feedback still appears; replies from obsolete commands cannot overwrite newer ones.
- Full ForgeGradle compilation/in-game verification must run on a machine with
  Forge dependencies (for example the developer's Windows machine).
