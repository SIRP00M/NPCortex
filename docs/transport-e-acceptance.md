> M03-FINAL fresh audit update: this document is historical. Current source, integrated commands and acceptance status are in [m03-final/README.md](m03-final/README.md). The legacy Test6 source and hooks were absent at audit; use `npcortex_m03:test6a`, not the old Test6 functions. Historical PASS does not certify this candidate.

# Transport E integrated candidate — 2026-10-25

Baseline: G3 BigSprint Fix2 EmptyStatus full repository supplied by user.
Scope: C+ shared transport + durable metadata recovery. No GitHub operations,
world mutation, Qwen auto-dispatch, E3-E DAG or E4/E5 implementation.

## Changes
- Serialize all lazy backend bridge initialization under an RLock. Constructors
  that fence old work can no longer race each other on first HTTP requests.
- CPlusTransport is used by F1 delivery, E preview/coordinator, G2 and G3.
  Legacy ChatBridge/C2 APIs retain their existing contracts. Transport is shared;
  stage-specific Minecraft workers and identity/ACK validators remain separate.
- Bound received bytes before allocating a complete response; shared URL/auth,
  timeout, HTTP status/error and retry handling. Only explicitly idempotent E3
  metadata routes receive at most one extra attempt after IO failure; 4xx are
  not retried. Replaying HTTP metadata never invokes navigation by itself.
- E3 requests persist without secrets under world/data/npcortex/transport.
  Reserve handoff marker is forced to disk BEFORE returning the ACK to the game
  thread. Missing/corrupt records and orphan temp files HOLD. Report and terminal
  messages preserve original payloads for explicit recovery. Records retained
  as evidence; no automatic cleanup/compaction in this candidate.
- New operator command: /npcortex debug cplus tasktransportrecover <name>.
  It inspects backend status and persisted provenance, checks requester/session,
  then re-sends only original report/finalize/cancel-ack metadata. It cannot start
  movement. A proven never-handed-off reserve can be abandoned through an
  authenticated, identity-checked endpoint; accepted goals cannot be abandoned.
- Retry taskmovecancel for CANCEL_PENDING after lost intent ACK. Then use
  taskmovereconcile once C2/C0 cancellation is terminal. If the goal completed
  before cancellation, taskmovefinalize accepts full live completion proof in
  CANCEL_PENDING; cancellation never overrides an existing terminal record.
- G2/G3/recovery actor guards prevent recovery from abandoning an in-flight
  reserve. A rejected G2 report queue releases the local pending guard and HOLDs.
- Fixed IPv6 loopback literal check; removed obsolete 'G3 pending' message.

## Validation performed here
- Full backend unittest discovery: 396 tests, PASS (61.028 seconds).
- Added real HTTP concurrency regression with 10 concurrent first requests:
  one store construction, all responses RESERVED. PASS.
- HTTP metadata tests: abandon before handoff, reject admitted-goal abandon,
  bad auth, duplicate report/cancel ACK, and completion-vs-cancel. PASS.
- Java 17 actual compilation and execution of pure C+ package and tests:
  shared transport PASS (lost response exact-payload retry, no retry on 409,
  bounded response and IPv6 validation).
- Existing Java regressions PASS: F1 recovery 36 checks, transport 12,
  async diagnostics 16, D+ ledger 35, F0 contracts 71.
- Parsed all 174 Java sources: syntax PASS. This is NOT Forge type checking.
- Forge compile/build attempted: BLOCKED before compilation because Gradle 8.8
  distribution is absent and network access to services.gradle.org is unavailable.
- verifyTransportOutbox, G2/G3 Gson contract tests and actual game NOT run here.

## Windows automated gate (from extracted repository root)

    .\scripts\test-transport-e.ps1

This runs full backend tests then Gradle build + verifyTransportE +
verifyTransportOutbox + verifyG2Identity + verifyG3TerminalContract. If this gate
fails, preserve logs and do not treat the candidate as accepted.

## Windows gameplay gate
Use a COPY of an existing world and keep its data/npcortex records.
1. Start backend and Forge as usual. Confirm ordinary chat, C1 and C2/follow.
2. taskmove one nearby loaded target; taskmovestatus; after arrival run
   taskmovefinalize. Expect backend COMPLETED and a new goal admitted.
3. During a sufficiently long move: taskmovecancel; after C2 cancellation ACK,
   taskmovereconcile. Expect CANCELLED; no other actor/Follow stopped.
4. Lose the reserve response BEFORE any handoff (fault proxy/test harness needed):
   tasktransportrecover must cancel only the unstarted reservation. New movement
   must require a NEW explicit taskmove command.
5. Lose report response: tasktransportrecover must never navigate again;
   taskmovestatus must show original goal ID. If report already committed, status
   may already be C2_ACCEPTED and no report replay is required.
6. Lose cancel-begin response: retry taskmovecancel with the SAME actor; then
   taskmovereconcile. If goal already completed, use taskmovefinalize instead.
7. Lose finalize/cancel-ack response: inspect/recover; already committed terminal
   state remains terminal. No duplicate effects or actor fence release guesses.
8. Concurrent actors 1/3/5/10; server exit/rejoin; backend offline/restart;
   orphan .tmp and disk write failures must HOLD without replay.

## Deliberate fail-closed boundaries / residual risks
- This is NOT automatic restart/crash recovery. A different session or backend
  restart HOLD cannot be finalized from old outbox data. Unknown handoff remains
  fenced; no speculative reset, rollback, or world action.
- Crash after durable handoff marker but before durable report is an ambiguous
  window: HOLD, not retry navigation. Existing C2 SavedData is not an fsync world
  transaction. This cannot be resolved solely from HTTP acknowledgements.
- A rejected report worker queue may leave no report record. HOLD for inspection.
- Outbox uses file force + atomic rename; directory fsync and actual Windows
  power-loss guarantees have not been proven. No automatic orphan-file deletion.
- Disk records are retained and can grow. Archive only after verified terminal
  acceptance; do not delete evidence to clear a stuck actor.
- F1 retains its original cursor/confirmation policy; generic E3 retry does not
  override it. Dedicated-server/multi-user credentials and live MSPT unverified.
- Candidate quality status: backend/pure Java gates PASS; Forge and Windows
  gameplay gates PENDING. Do not start the next roadmap milestone yet.

## 2026-10-25 handoff diagnostics addendum (same Transport E scope)

The subsequent handoff candidate adds only privacy-safe failure codes to G2/G3
operator messages and tests; the original transport state machine, safety fences
and persistence policy are unchanged. An `HTTP_409` vs `CONNECT_FAILED` now
helps explain a reserve HOLD, without assuming offline or pathfinding failure.
The current operator protocol, expected states and evidence collection are in
`docs/transport-e-handoff-windows.md`. A new local run verified 396 backend tests
and seven standalone pure Java test mains; **Forge and Windows gameplay are
still PENDING for this updated source**, regardless of historical results above.
