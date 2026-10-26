package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** P1-C Fix #1: fail-closed, bounded, async Forge-to-C0 cancellation.
 * Only C0 terminal acknowledgement may clear CANCEL_PENDING. On Save/Quit +
 * rejoin the journal is re-scanned, with no old movement re-executed.
 * Explicit stop of C1 movement is a prerequisite to sending cancellation.
 */
final class C2CancellationRecovery {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<UUID> IN_FLIGHT = new HashSet<>();
    private static final Map<UUID, Long> RETRY_AT = new HashMap<>();
    private static long ticks;
    private static final int RETRY_TICKS = 100;

    private C2CancellationRecovery() {}

    static void begin(C2AgentRuntime.Run run, String reason) {
        // The caller released the exclusive active run; any C1 motion stop is the caller's, and conditional.
        C2ActionJournal.get(run.server).record(run.actor,run.goal,run.action,
                "CANCEL_PENDING",reason.substring(0,Math.min(reason.length(),180)));
        RETRY_AT.remove(run.actor);
        // A server-tick retry is required even if the backend is offline.
    }

    static void onServerTick(MinecraftServer server) {
        ServerThread.require(server, "C2 cancellation reconcile");
        ticks++;
        C2ActionJournal journal=C2ActionJournal.get(server);
        for (C2ActionJournal.Row row : journal.entries()) {
            // One-time compatibility for old P1-C operator cancellation rows.
            // Never auto-reconcile arbitrary INTERRUPTED states (timeout, crash,
            // lifecycle change): only the unambiguous old cancellation marker.
            if ("INTERRUPTED".equals(row.state()) && row.goal()!=null
                    && row.message().startsWith("Operator cancelled; C0 goal may have a pending action")) {
                journal.record(row.actor(),row.goal(),row.action(),"CANCEL_PENDING",
                        "Legacy operator cancel; awaiting backend reconciliation");
                continue;
            }
            // Fix #1: repair ONLY the known old-S4 replanning-interruption
            // signature. Unknown INTERRUPTED rows remain fail-closed for audit.
            if (C2S4RecoveryPolicy.legacyReplanInterrupted(row.state(),row.message(),
                    row.goal()!=null,row.action()!=null) && !C2AgentRuntime.hasActiveActor(row.actor())) {
                journal.record(row.actor(),row.goal(),row.action(),"CANCEL_PENDING",
                        "S4 known legacy replan interruption; C0 terminal reconciliation pending");
                continue;
            }
            // S4: on a *new* server process a stale, action-owning journal is
            // eligible for backend reconciliation, never for movement replay.
            // In this process ACTIVE protects legitimately MOVING actors.
            if (C2S4RecoveryPolicy.restartCandidate(row.state(),row.goal()!=null,row.action()!=null)
                    && !C2AgentRuntime.hasActiveActor(row.actor())) {
                journal.record(row.actor(),row.goal(),row.action(),"CANCEL_PENDING",
                        "S4 abandoned action on reload; backend terminal reconciliation pending");
                continue;
            }
            if (!"CANCEL_PENDING".equals(row.state()) || row.goal()==null) continue;
            if (IN_FLIGHT.contains(row.actor()) || RETRY_AT.getOrDefault(row.actor(),0L)>ticks) continue;
            C1IdentityData.Entry identity=null;
            for (C1IdentityData.Entry candidate : C1IdentityData.get(server).all()) {
                if (candidate.actorId().equals(row.actor())) { identity=candidate; break; }
            }
            if (identity==null) continue;
            // Never reconcile while a local C1 movement remains active. The
            // cancelled motion may need additional ticks to settle under gravity.
            if (C1P1MotionRuntime.c2Receipt(identity.profileId()).active()) continue;
            C1PlayerAvatar live=C1P0Runtime.activeAvatar(server,identity.character());
            if (live!=null && !live.isRemoved() && !live.onGround()) continue;
            RETRY_AT.put(row.actor(),ticks+RETRY_TICKS);
            IN_FLIGHT.add(row.actor());
            JsonObject payload=new JsonObject();
            payload.addProperty("world_id",C1IdentityData.get(server).worldId().toString());
            payload.addProperty("actor_id",row.actor().toString());
            if (row.action()!=null) payload.addProperty("action_id",row.action().toString());
            ChatBridge.c2Post("/v1/agent/goals/"+row.goal()+"/cancel",payload,10)
                .whenComplete((response,error)->server.execute(()->{
                    IN_FLIGHT.remove(row.actor());
                    C2ActionJournal.Row current=C2ActionJournal.get(server).find(row.actor());
                    if (current==null || !"CANCEL_PENDING".equals(current.state())
                            || !row.goal().equals(current.goal()) || !java.util.Objects.equals(row.action(),current.action())) return;
                    if (error!=null) {
                        LOG.warn("NPCortex cancellation reconcile deferred goal={} reason={}",row.goal(),ChatBridge.describeFailure(error));
                        return;
                    }
                    try {
                        boolean matching=response.has("goal_id") && response.get("goal_id").getAsString().equals(row.goal().toString())
                                && response.has("actor_id") && response.get("actor_id").getAsString().equals(row.actor().toString());
                        boolean actionMatches=row.action()==null || (response.has("action_id")
                                && !response.get("action_id").isJsonNull()
                                && row.action().toString().equals(response.get("action_id").getAsString()));
                        boolean terminal=response.has("terminal") && response.get("terminal").getAsBoolean();
                        boolean noPending=response.has("pending_action_id") && response.get("pending_action_id").isJsonNull();
                        String state=response.get("status").getAsString();
                        boolean cancelled=response.get("cancelled").getAsBoolean();
                        if (!matching || !actionMatches || !C2CancellationProtocol.terminal(terminal,noPending,state)) {
                            LOG.warn("NPCortex cancellation reply was not terminal/matching goal={}",row.goal());
                            return;
                        }
                        // Fresh local motion check just before release of the gate.
                        if (C1P1MotionRuntime.c2Receipt(identityFor(server,row.actor())).active()) return;
                        C1IdentityData.Entry currentIdentity=null;
                        for (C1IdentityData.Entry entry : C1IdentityData.get(server).all())
                            if (entry.actorId().equals(row.actor())) { currentIdentity=entry; break; }
                        if (currentIdentity==null) return;
                        C1PlayerAvatar liveNow=C1P0Runtime.activeAvatar(server,currentIdentity.character());
                        if (liveNow!=null && !liveNow.isRemoved() && !liveNow.onGround()) return;
                        String resolved=C2CancellationProtocol.journalState(cancelled,state);
                        C2ActionJournal.get(server).record(row.actor(),row.goal(),row.action(),resolved,
                                "C0 terminal verified; no pending backend action");
                        RETRY_AT.remove(row.actor());
                        LOG.info("NPCortex cancellation reconciled actor={} goal={} state={}",row.actor(),row.goal(),resolved);
                    } catch (RuntimeException ex) {
                        LOG.warn("NPCortex invalid cancellation reply goal={}",row.goal(),ex);
                    }
                }));
        }
    }

    private static UUID identityFor(MinecraftServer server,UUID actor) {
        for (C1IdentityData.Entry id : C1IdentityData.get(server).all())
            if (id.actorId().equals(actor)) return id.profileId();
        throw new IllegalStateException("Cancelled actor identity is missing");
    }

    static void beforeStop() { IN_FLIGHT.clear(); RETRY_AT.clear(); ticks=0; }
}
