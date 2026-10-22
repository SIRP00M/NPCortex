package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** C2-F1 operator-only, bounded local follow. No C0 action is created or
 * claimed. C2 move_to owns C0 as before; the two owner types are EXCLUSIVE.
 * Runs exclusively on the Forge server thread, after C1 physics ticks.
 */
final class C2FollowRuntime {
    private static final Logger LOG=LogUtils.getLogger();
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    private static long ticks;
    private static long lastPlanTick=-1;
    private C2FollowRuntime() { }

    private static final class Session {
        final MinecraftServer server;
        final UUID actor,profile,session=UUID.randomUUID(),player;
        final String npc,dimension;
        final C2FollowPolicy policy=new C2FollowPolicy();
        final C2FollowAlternatives alternatives=new C2FollowAlternatives();
        C2WaypointExecution route;
        String phase="STARTED";
        String reason="Follow session claimed";
        long routeStarted;
        int segmentTicks;
        int settlingTicks;
        UUID lastExtensionToken;
        int lastExtensionIndex=-1;
        long lastRollingProbe=-1000;
        int persistentAppends;
        int rollingMisses;
        int mixed3DStarts;
        int terrainSelections;
        String lastTerrainDecision="NONE";
        String lastFailure="NONE";
        int detourSide;
        int detourHops;
        final C2FollowSmoothMetrics smoothMetrics=new C2FollowSmoothMetrics();
        final C2FollowPreparedContinuation prepared=new C2FollowPreparedContinuation();
        final C2FollowDetourStability detourStability=new C2FollowDetourStability();
        final C2FollowTargetProjection targetProjection=new C2FollowTargetProjection();
        UUID lastPreplanToken;
        int lastPreplanEndpoint=-1;
        long lastPreplanTick=-1000;
        Session(MinecraftServer server,C1IdentityData.Entry id,ServerPlayer target) {
            this.server=server;actor=id.actorId();profile=id.profileId();
            npc=id.character();dimension=id.dimension();player=target.getUUID();
        }
        C1PlayerAvatar avatar() {
            C1PlayerAvatar a=C1P0Runtime.activeAvatar(server,npc);
            return a!=null && !a.isRemoved() && a.getUUID().equals(profile)
                    && a.serverLevel().dimension().location().toString().equals(dimension)
                    && C1P1CLifecycleRuntime.mayRestore(server,profile) ? a : null;
        }
        ServerPlayer target() { return server.getPlayerList().getPlayer(player); }
    }

    static boolean hasActiveActor(UUID actor) { return SESSIONS.containsKey(actor); }

    static String follow(MinecraftServer server,String npcName,String playerName) {
        if(server==null || !server.isSameThread()) return "C2-F1 requires server thread";
        C1IdentityData.Entry id=C1IdentityData.get(server).find(npcName);
        if(id==null) return "Unknown NPC: "+npcName;
        if(SESSIONS.containsKey(id.actorId())) return "C2-F1 already following; use c2 unfollow first";
        if(SESSIONS.size()>=C2FollowPolicy.MAX_ACTORS) return "C2-F1 maximum 10 following NPCs";
        if(C2AgentRuntime.hasActiveActor(id.actorId())) return "C2-F1 rejected: Navigate owns this NPC";
        C2ActionJournal.Row prior=C2ActionJournal.get(server).find(id.actorId());
        if(prior!=null && prior.uncertain())
            return "C2-F1 refused: unresolved C0 action journal "+prior.state()+"; reconcile first";
        ServerPlayer player=server.getPlayerList().getPlayerByName(playerName);
        if(player==null || player.isRemoved() || !player.isAlive() || player.isSpectator())
            return "C2-F1 target player must be online, alive and non-spectator";
        C1PlayerAvatar npc=C1P0Runtime.activeAvatar(server,npcName);
        if(npc==null || npc.isRemoved() || !npc.getUUID().equals(id.profileId())
                || !npc.serverLevel().dimension().location().toString().equals(id.dimension())
                || !C1P1CLifecycleRuntime.mayRestore(server,id.profileId()))
            return "C2-F1 NPC not live/alive in registered dimension";
        if(!npc.serverLevel().dimension().equals(player.serverLevel().dimension()))
            return "C2-F1 target player must be in same dimension";
        var motion=C1P1MotionRuntime.c2Ticket(id.profileId());
        if(motion.active() || motion.combatActive() || !npc.onGround())
            return "C2-F1 C1 movement busy or NPC not grounded";
        Session session=new Session(server,id,player);
        SESSIONS.put(session.actor,session);
        C2FollowJournal.get(server).record(session.actor,session.session,session.player,"STARTED",
                "C2-F1 bounded local follow; no C0 action");
        LOG.info("NPCortex C2-F1 started actor={} session={} target={}",session.actor,session.session,session.player);
        return "C2-F1 follow started npc="+npcName+" player="+player.getGameProfile().getName()
                +" session="+session.session+" stopDistance="+C2FollowPolicy.STOP_DISTANCE
                +" resumeDistance="+C2FollowPolicy.RESUME_DISTANCE+" (local 3D only)";
    }

    static String unfollow(MinecraftServer server,String npcName) {
        C1IdentityData.Entry id=C1IdentityData.get(server).find(npcName);
        if(id==null) return "Unknown NPC: "+npcName;
        Session session=SESSIONS.get(id.actorId());
        if(session==null) return "C2-F1 no active follow; "+status(server,npcName);
        close(session,"STOPPED","Operator unfollow");
        return "C2-F1 stopped "+npcName+" session="+session.session+" (no C0 action to cancel)";
    }

    static String status(MinecraftServer server,String npcName) {
        C1IdentityData.Entry id=C1IdentityData.get(server).find(npcName);
        if(id==null) return "Unknown NPC: "+npcName;
        Session session=SESSIONS.get(id.actorId());
        if(session!=null)
            return npcName+" F1="+session.phase+" session="+session.session
                    +" player="+session.player+" generation="+session.policy.generation()
                    +" failures="+session.policy.failures()
                    +" avoided="+session.alternatives.avoidedCount()
                    +" persistentAppends="+session.persistentAppends
                    +" rollingMisses="+session.rollingMisses
                    +" mixed3DStarts="+session.mixed3DStarts
                    +" terrainSelections="+session.terrainSelections
                    +" terrainReprobes="+session.policy.terrainReprobes()
                    +" detourHops="+session.detourHops
                    +" prepared="+session.prepared.decision()
                    +session.smoothMetrics.summary()
                    +" targetMode="+session.targetProjection.mode()
                    +" targetProjection="+session.targetProjection.source()
                    +" projectedY="+session.targetProjection.lastY()
                    +" terrainDecision="+session.lastTerrainDecision
                    +" lastFailure="+session.lastFailure
                    +" waypoint="+(session.route==null?"none":session.route.currentIndex()+"/"+session.route.totalSegments())
                    +" motion="+(session.route==null?"none":String.valueOf(
                            C1P1MotionRuntime.c2WalkProgress(session.profile,session.route.expectedTicket())))
                    +" reason="+session.reason;
        C2FollowJournal.Row old=C2FollowJournal.get(server).find(id.actorId());
        return old==null ? npcName+" F1=NONE" : npcName+" F1="
                +(old.state().equals("STOPPED")||old.state().equals("FAILED") ? old.state() : "STOPPED_AFTER_RESTART")
                +" session="+old.session()+" reason="+old.reason();
    }

    /** Claim check: stopping never cancels a C1 motion from another owner. */
    private static void stopOwned(Session session) {
        if(session.route==null || !session.route.waiting()) return;
        var motion=C1P1MotionRuntime.c2Ticket(session.profile);
        if(C2FollowPolicy.ownedTicket(session.route.expectedTicket(),motion.token(),motion.active(),motion.origin())
                && session.avatar()!=null) C1P1MotionRuntime.stop(session.server,session.npc);
    }
    private static void transition(Session session,String state,String why) {
        if(state.equals(session.phase) && why.equals(session.reason)) return;
        session.phase=state;session.reason=why;
        // The durable journal records lifecycle transitions, not 20-TPS positions.
        if(state.startsWith("PAUSED") || state.equals("HOLD") || state.equals("MOVING")) return;
        C2FollowJournal.get(session.server).record(session.actor,session.session,session.player,state,why);
    }
    private static void close(Session session,String status,String why) {
        if(SESSIONS.get(session.actor)!=session) return;
        stopOwned(session);
        session.route=null;
        session.prepared.clear();
        SESSIONS.remove(session.actor,session);
        C2FollowJournal.get(session.server).record(session.actor,session.session,session.player,status,why);
        LOG.info("NPCortex C2-F1 session={} actor={} terminal={} reason={}",session.session,session.actor,status,why);
    }
    private static void pause(Session s,String why) {
        stopOwned(s);
        s.route=null;
        s.prepared.clear();
        s.segmentTicks=0;
        transition(s,"PAUSED",why);
    }

    /** Called after C1 physics and C2 Agent Runtime, on Forge server END tick. */
    static void onServerTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END) return;
        ticks++;
        for(Session session:List.copyOf(SESSIONS.values())) {
            if(SESSIONS.get(session.actor)!=session || session.server!=event.getServer()) continue;
            try { tick(session); }
            catch(RuntimeException error) {
                LOG.warn("NPCortex C2-F1 guarded session failure actor={} session={}",session.actor,session.session,error);
                close(session,"FAILED","C2-F1 runtime error: "+error.getClass().getSimpleName());
            } finally {
                if(SESSIONS.get(session.actor)==session) session.smoothMetrics.sample(session.phase);
            }
        }
    }

    private static void tick(Session s) {
        if(C2AgentRuntime.hasActiveActor(s.actor)) { close(s,"FAILED","C0 action took ownership");return; }
        C2ActionJournal.Row prior=C2ActionJournal.get(s.server).find(s.actor);
        if(prior!=null && prior.uncertain()) { close(s,"FAILED","Unresolved C0 journal; follow fenced");return; }
        C1PlayerAvatar avatar=s.avatar();
        if(avatar==null) { close(s,"FAILED","NPC lifecycle/dimension unavailable");return; }
        ServerPlayer player=s.target();
        if(player==null || player.isRemoved() || !player.isAlive() || player.isSpectator()
                || !player.serverLevel().dimension().equals(avatar.serverLevel().dimension())) {
            pause(s,"Target offline/unavailable or changed dimension");return;
        }
        // Flight is a target *presentation/mode* signal, not a C1 move goal.
        // On a mode transition, invalidate speculative hints (which may have
        // been computed against a different target height) without hijacking
        // or cancelling an already active owned C1 motion.
        boolean flightModeChanged=s.targetProjection.observe(
                player.getAbilities().flying || player.isFallFlying(),player.onGround());
        if(flightModeChanged) {
            s.prepared.clear();
            s.policy.targetRelocated();
            if(s.route!=null && !s.route.waiting()) s.route=null;
        }
        double horizontalDistance=Math.hypot(player.getX()-avatar.getX(),player.getZ()-avatar.getZ());
        double distance=s.targetProjection.followingDistance(horizontalDistance,
                player.getY(),avatar.getY());
        var desired=s.policy.distance(distance);
        if(desired==C2FollowPolicy.DistanceGate.HOLD || !s.policy.following()) {
            pause(s,"Within follow stop distance");return;
        }
        // The sensor is local (radius 6), not multi-chunk and never force-loads.
        // Rolling local goals replace the former >8-block hard stop. Every
        // planning hop stays inside a fresh radius-6 snapshot and requires
        // loaded terrain. No global map, chunk force-load or teleport.
        if(s.targetProjection.outsideVerticalReach(player.getY(),avatar.getY(),
                C2NavGrid.VERTICAL_RADIUS+1.0)) {
            pause(s,"Target outside local vertical reach; no teleport or chunk loading");return;
        }
        if(s.route!=null && s.route.waiting()) {
            s.segmentTicks++;
            if(ticks-s.routeStarted>C2FollowPolicy.MAX_ROUTE_TICKS) {
                retry(s,"Follow route watchdog expired");return;
            }
            var ticket=C1P1MotionRuntime.c2Ticket(s.profile);
            if(!s.route.acceptsTicket(ticket.token()) ||
                    C2FollowPolicy.foreignActive(s.route.expectedTicket(),ticket.token(),ticket.active(),ticket.origin())) {
                close(s,"FAILED","Stale/foreign C1 movement ticket; manual inspection required");return;
            }
            if(s.segmentTicks>C2FollowPolicy.MAX_SEGMENT_TICKS) {
                retry(s,"C1 segment watchdog expired");return;
            }
            if(ticket.combatActive()) { transition(s,"PAUSED_COMBAT","Waiting for C1 combat owner");return; }
            // C-S2: do not wait for the current WALK ticket to finish. When
            // its certified endpoint approaches, seek a fresh, flat route
            // from THAT endpoint and append to the SAME token. A changed
            // player position is expected in Follow, not a reason to veto.
            // 1 global planner batch/tick and per-session probe cooldown.
            if(ticket.active() && !ticket.combatActive()) {
                var progress=C1P1MotionRuntime.c2WalkProgress(s.profile,ticket.token());
                int endpoint=s.route.lastCommittedIndex();
                boolean extended=false;
                // One *expensive* world/graph extension probe per committed
                // endpoint, rather than repeating it at 20 TPS on a miss.
                if(progress!=null && progress.remaining()>=1 && progress.remaining()<=2
                        && (!ticket.token().equals(s.lastExtensionToken)
                            || endpoint!=s.lastExtensionIndex)) {
                    s.lastExtensionToken=ticket.token();
                    s.lastExtensionIndex=endpoint;
                    extended=C2WaypointBridge.extendFollowWalkChain(s.route,avatar,s.profile);
                    if(extended) {s.segmentTicks=0;s.routeStarted=ticks;s.persistentAppends++;}
                }
                // New route generation from the current VERIFIED endpoint is
                // attempted only if the original route has no more committed
                // nodes and a reasonable distance remains to the player.
                if(!extended && progress!=null && progress.remaining()>=1 && progress.remaining()<=2
                        && s.route.lastCommittedIndex()==s.route.path().size()-1
                        && ticks-s.lastRollingProbe>=C2FollowPolicy.PERSISTENT_PROBE_TICKS
                        && lastPlanTick!=ticks
                        && Math.hypot(player.getX()-(s.route.receiptTarget().x()+.5),
                                player.getZ()-(s.route.receiptTarget().z()+.5))
                             > C2FollowPolicy.RESUME_DISTANCE) {
                    s.lastRollingProbe=ticks;
                    lastPlanTick=ticks;
                    if(extendLiveFollow(s,avatar,player)) {
                        s.persistentAppends++;
                        s.segmentTicks=0;
                        s.routeStarted=ticks;
                        s.policy.rollingExtended(ticks,player.getX(),player.getZ());
                    } else s.rollingMisses++;
                }
                // S2: while C1 owns the current movement, prepare only an
                // immutable, NON-AUTHORITATIVE next corridor from its approved
                // endpoint. The hint is NOT executable and can be dropped.
                // One global terrain-planning batch per tick remains enforced.
                if(!extended && lastPlanTick!=ticks && eligibleForPreplan(s,avatar,player,progress)) {
                    lastPlanTick=ticks;
                    prepareContinuation(s,avatar,player,ticket.token());
                }
            }
            var node=s.route.receiptTarget();
            boolean reached=C2MoveGeometry.near(avatar.getX(),avatar.getY(),avatar.getZ(),
                    node.x()+0.5,node.footY(),node.z()+0.5,C2WaypointExecution.WAYPOINT_TOLERANCE);
            var next=s.route.observe(ticket.token(),ticket.active(),ticket.phase(),reached);
            if(next==C2WaypointExecution.Update.ACTIVE) return;
            if("CANCELLED".equals(ticket.phase())) {
                close(s,"STOPPED","C1 motion was explicitly stopped; no automatic resume");return;
            }
            s.segmentTicks=0;
            if(next==C2WaypointExecution.Update.FAILED) { retry(s,"C1 terminal "+ticket.phase());return; }
            if(next==C2WaypointExecution.Update.COMPLETE) {
                s.policy.resetFailureWindow(); // verified physical progress, not merely planned
                s.alternatives.verifiedProgress();
                var terminalToken=ticket.token();
                var physicalEndpoint=s.route.receiptTarget();
                s.route=null;
                s.smoothMetrics.terminalHandoff();
                if(distance<C2FollowPolicy.RESUME_DISTANCE) {
                    s.prepared.clear();
                    s.policy.holdUntilResumeDistance();
                    transition(s,"HOLD","Verified safe follow ring; wait for player to leave");
                } else {
                    // Consume ONLY after the original C1 physical receipt was
                    // VERIFIED by route.observe(). Re-check all world safety
                    // and the exclusive C1 token on the NEXT server tick.
                    var preview=s.prepared.consume(terminalToken,physicalEndpoint,
                            ticks,player.getX(),player.getZ());
                    if(preview!=null && Mth.floor(avatar.getX())==physicalEndpoint.x()
                            && Mth.floor(avatar.getZ())==physicalEndpoint.z()
                            && Math.abs(avatar.getY()-physicalEndpoint.footY())<=.16) {
                        s.route=C2WaypointExecution.followSmoothed(preview);
                        s.routeStarted=ticks;
                        s.segmentTicks=0;
                        s.policy.planned(ticks,player.getX(),player.getZ());
                        s.smoothMetrics.preparedHit();
                        transition(s,"TURNING","Prepared corridor awaiting fresh C1 safety validation");
                    } else {
                        s.smoothMetrics.preparedMiss();
                        s.policy.afterVerifiedRoute(ticks);
                        transition(s,"FOLLOWING","Player moved past resume distance; tracking");
                    }
                }
                return;
            }
            // NEXT: the target moved far from planned endpoint; do not blindly
            // continue an obsolete route. Never interrupt a live C1 ticket.
            if(s.policy.targetMoved(player.getX(),player.getZ())) {
                s.route=null;
                s.prepared.clear();
                transition(s,"REPLANNING","Player moved; route invalidated at terminal receipt");
                s.policy.afterVerifiedRoute(ticks);
                return;
            }
            // Never issue the next ticket during the same tick as a terminal
            // receipt. F2 rotates gradually BEFORE the next immutable C1 plan.
            transition(s,"TURNING","Preparing next verified waypoint");
            return;
        }
        var idleMotion=C1P1MotionRuntime.c2Ticket(s.profile);
        if(idleMotion.active() && idleMotion.origin()!=C2S4RecoveryPolicy.MotionOrigin.AUTO_SETTLING
                && idleMotion.origin()!=C2S4RecoveryPolicy.MotionOrigin.AUTO_COMBAT) {
            close(s,"FAILED","Foreign command owns C1 while Follow is idle");return;
        }
        if(idleMotion.active() || !avatar.onGround()) {
            s.settlingTicks++;
            if(s.settlingTicks>C2FollowPolicy.SETTLE_TIMEOUT_TICKS) {
                close(s,"FAILED","C1 not settled/grounded before deadline");
            } else transition(s,"WAIT_GROUND","Waiting for Vanilla physics to settle");
            return;
        }
        s.settlingTicks=0;
        if(s.route!=null) {
            if(s.policy.targetMoved(player.getX(),player.getZ())) {
                s.route=null;
                s.prepared.clear();
                s.policy.defer(ticks);
                transition(s,"REPLANNING","Moving target invalidated unstarted segment");
                return;
            }
            if(ticks-s.routeStarted>C2FollowPolicy.MAX_ROUTE_TICKS) {
                retry(s,"Follow route/turn watchdog expired");return;
            }
            String error=beginSmoothSegment(s,avatar);
            if(error!=null) retry(s,error);
            return;
        }
        if(!s.policy.ready(ticks)) { transition(s,"COOLDOWN","Bounded follow planning cooldown");return; }
        if(lastPlanTick==ticks) return; // global max one local follow planning batch/tick
        lastPlanTick=ticks;
        plan(s,avatar,player);
    }

    /** S2 work is speculative and read-only: no C1 ticket operations here. */
    private static boolean eligibleForPreplan(Session s,C1PlayerAvatar npc,
            ServerPlayer player,C1P1MotionRuntime.C1F2WalkProgress progress) {
        if(s.route==null || !s.route.waiting() || s.prepared.pending()
                || !npc.onGround() || ticks-s.lastPreplanTick<10
                || !npc.serverLevel().hasChunkAt(npc.blockPosition())
                || !player.serverLevel().hasChunkAt(player.blockPosition())) return false;
        int endpoint=s.route.lastCommittedIndex();
        if(endpoint!=s.route.path().size()-1) return false;
        var node=s.route.path().get(endpoint);
        if(Math.hypot(player.getX()-node.x()-.5,player.getZ()-node.z()-.5)
                <=C2FollowPolicy.RESUME_DISTANCE) return false;
        // For WALK: approach last 1..2 committed waypoints. For mixed 3D:
        // only probe after execution is underway and endpoint is in local range.
        if(progress!=null && (progress.remaining()<1 || progress.remaining()>2)) return false;
        if(progress==null && (s.segmentTicks<8 ||
                Math.hypot(npc.getX()-node.x()-.5,npc.getZ()-node.z()-.5)>4.5)) return false;
        var token=s.route.expectedTicket();
        return token!=null && (!token.equals(s.lastPreplanToken)
                || endpoint!=s.lastPreplanEndpoint);
    }

    private static void prepareContinuation(Session s,C1PlayerAvatar npc,
            ServerPlayer player,UUID token) {
        int at=s.route.lastCommittedIndex();
        var endpoint=s.route.path().get(at);
        s.lastPreplanToken=token;s.lastPreplanEndpoint=at;
        s.lastPreplanTick=ticks;
        try {
            var graph=C2GraphWorldProbe.captureWithSnapshot(npc,C2NavGrid.MAX_RADIUS).graph();
            if(!graph.contains(endpoint)) {s.smoothMetrics.preplanAttempt(false);return;}
            double targetY=s.targetProjection.effectiveY(graph,endpoint,
                    player.getX(),player.getY(),player.getZ());
            var chosen=C2TerrainNativeFollowPlanner.select(graph,
                    new C2AStarPlanner.Target(endpoint.x(),endpoint.supportY(),endpoint.z()),
                    endpoint.x()+.5,endpoint.footY(),endpoint.z()+.5,
                    player.getX(),targetY,player.getZ(),
                    s.alternatives.avoided(),s.alternatives.preferred(),
                    s.detourSide,s.detourHops<C2TerrainNativeFollowPlanner.MAX_LATERAL_DETOURS);
            if(!chosen.found()) {s.smoothMetrics.preplanAttempt(false);return;}
            var nodes=C2F2Locomotion.smooth(graph,chosen.plan().path());
            boolean prepared=s.prepared.offer(token,endpoint,nodes,ticks,
                    player.getX(),player.getZ(),chosen.reason());
            s.smoothMetrics.preplanAttempt(prepared);
        } catch(IllegalArgumentException invalidHint) {
            // Speculation is never allowed to terminate an otherwise healthy
            // C1-owned movement. The regular fresh plan remains the fallback.
            s.prepared.clear();
            s.smoothMetrics.preplanAttempt(false);
            LOG.debug("NPCortex speculative follow hint rejected actor={}",s.actor,invalidHint);
        }
    }

    /** C-S2: one bounded local plan from the *committed endpoint*, never
     * from a predicted/unverified player position. The first node of the
     * proposed path must equal the old receipt endpoint, so no jump, splice,
     * teleport or unchecked connecting chord can enter a live C1 ticket.
     * For horizontal WALK only; all vertical primitives retain old receipts. */
    private static boolean extendLiveFollow(Session s,C1PlayerAvatar npc,ServerPlayer player) {
        if(s.route==null || !s.route.waiting() || !npc.onGround()
                || !npc.serverLevel().hasChunkAt(npc.blockPosition())
                || !player.serverLevel().hasChunkAt(player.blockPosition())) return false;
        var endpoint=s.route.path().get(s.route.lastCommittedIndex());
        if(s.route.path().size()>=C2WaypointExecution.MAX_WAYPOINTS-1
                || Math.abs(endpoint.footY()-npc.getY())>.02) return false;
        var capture=C2GraphWorldProbe.captureWithSnapshot(npc,C2NavGrid.MAX_RADIUS);
        var graph=capture.graph();
        if(!graph.contains(endpoint)) return false;
        var from=new C2AStarPlanner.Target(endpoint.x(),endpoint.supportY(),endpoint.z());
        double x=endpoint.x()+.5,z=endpoint.z()+.5;
        double targetY=s.targetProjection.effectiveY(graph,endpoint,
                player.getX(),player.getY(),player.getZ());
        var selected=C2TerrainNativeFollowPlanner.select(graph,from,x,endpoint.footY(),z,
                player.getX(),targetY,player.getZ(),s.alternatives.avoided(),
                s.alternatives.preferred(),s.detourSide,
                s.detourHops<C2TerrainNativeFollowPlanner.MAX_LATERAL_DETOURS);
        s.lastTerrainDecision="LIVE_"+selected.reason();
        if(!selected.found()) return false;
        var nodes=C2F2Locomotion.smooth(graph,selected.plan().path());
        if(nodes.size()<2 || !nodes.get(0).equals(endpoint)) return false;
        var extra=new java.util.ArrayList<C2TraversabilityGraph.Node>();
        double length=0;
        for(int i=1;i<nodes.size() && extra.size()<C2WaypointExecution.FOLLOW_CHAIN_LIMIT;i++) {
            var n=nodes.get(i);
            var prev=nodes.get(i-1);
            if(Math.abs(n.footY()-endpoint.footY())>.0001 ||
                    Math.abs(prev.footY()-endpoint.footY())>.0001) break;
            double step=Math.hypot((double)(n.x()-prev.x()),(double)(n.z()-prev.z()));
            if(step<.03 || step>4.5 || length+step>C1F2ContinuousWalk.MAX_TOTAL_LENGTH) break;
            extra.add(n);length+=step;
        }
        if(extra.isEmpty()) return false;
        boolean appended=C2WaypointBridge.appendPersistentFollowWalk(s.route,npc,s.profile,graph,extra);
        if(appended) trackCorridor(s,selected);
        return appended;
    }

    private static void retry(Session s,String reason) {
        s.lastFailure=reason;
        // A failed *owned* physical segment must not be selected again as the
        // first choice on the very next fresh-graph replan.
        s.alternatives.failed(s.route);
        stopOwned(s);
        s.route=null;
        s.prepared.clear();
        if(s.policy.failed(ticks)) transition(s,"REPLANNING",reason);
        else transition(s,"PAUSED_UNREACHABLE","Replan budget exhausted: "+reason);
    }

    private static void plan(Session s,C1PlayerAvatar npc,ServerPlayer player) {
        if(!npc.serverLevel().hasChunkAt(npc.blockPosition()) || !player.serverLevel().hasChunkAt(player.blockPosition())) {
            pause(s,"Unloaded player/NPC chunk; no force-load");return;
        }
        // Target movement allows a fresh selection. For stationary players,
        // repeated failures use rate-limited terrain re-probes (2/4/6 s):
        // no stale path is replayed and no automatic travel is authorized.
        if(s.policy.targetMoved(player.getX(),player.getZ())) {
            s.alternatives.targetRelocated(npc.getX(),npc.getZ(),player.getX(),player.getZ());
            s.policy.targetRelocated();
            // Preserve the chosen detour side across small player movements.
            // Clearing it on EVERY 1.5-block retarget made uneven terrain
            // choose alternating left/right corridors (visible zigzag).
            if(s.detourStability.shouldReset(player.getX(),player.getZ())) {
                s.detourHops=0;s.detourSide=0;
                s.detourStability.clear();
            }
        } else if(s.policy.failures()>=C2FollowPolicy.MAX_FAILURES) {
            if(!s.policy.terrainReprobe(ticks)) {
                transition(s,"PAUSED_UNREACHABLE","Waiting for bounded terrain re-probe or target movement");return;
            }
            // Failed nodes remain a COST preference rather than permanent
            // exclusions. A fresh graph still validates any chosen corridor.
        }
        C2GraphWorldProbe.Capture capture=C2GraphWorldProbe.captureWithSnapshot(npc,C2NavGrid.MAX_RADIUS);
        var graph=capture.graph();
        var from=new C2AStarPlanner.Target(Mth.floor(npc.getX()),C2S3SupportY.at(npc.getY()),Mth.floor(npc.getZ()));
        var origin=graph.nodes().stream().filter(node -> node.x()==from.x()
                && node.supportY()==from.supportY() && node.z()==from.z()).findFirst().orElse(null);
        if(origin==null) {retry(s,"NO_VERIFIED_ACTOR_SUPPORT_NODE");return;}
        double targetY=s.targetProjection.effectiveY(graph,origin,
                player.getX(),player.getY(),player.getZ());
        var selection=C2TerrainNativeFollowPlanner.select(graph,from,
                npc.getX(),npc.getY(),npc.getZ(),player.getX(),targetY,player.getZ(),
                s.alternatives.avoided(),s.alternatives.preferred(),s.detourSide,
                s.detourHops<C2TerrainNativeFollowPlanner.MAX_LATERAL_DETOURS);
        s.lastTerrainDecision=selection.reason();
        s.terrainSelections++;
        C2HierarchicalPlanner.Result best=selection.plan();
        s.policy.markTarget(player.getX(),player.getZ());
        if(best==null) { retry(s,selection.reason());return; }
        C2WaypointExecution route=C2WaypointExecution.followSmoothed(C2F2Locomotion.smooth(graph,best.path()));
        if(route.path().size()==1) {
            s.policy.planned(ticks,player.getX(),player.getZ());
            s.policy.holdUntilResumeDistance();
            transition(s,"HOLD","Already in a verified follow-ring node");return;
        }
        s.route=route;
        trackCorridor(s,selection);
        s.alternatives.planned(route);
        s.routeStarted=ticks;
        s.segmentTicks=0;
        s.policy.planned(ticks,player.getX(),player.getZ());
        String error=beginSmoothSegment(s,npc);
        if(error!=null) retry(s,error);
    }

    /** A lateral escape may move away from the player temporarily, but at
     * most six bounded corridors can be planned without real progress or
     * player relocation. Never count planning as a completed physical move. */
    private static void trackCorridor(Session s,C2TerrainNativeFollowPlanner.Selection selected) {
        if(selected.detourSide()!=0) {
            s.detourSide=selected.detourSide();
            var target=s.target();
            if(target!=null) s.detourStability.chosen(target.getX(),target.getZ());
            s.detourHops=Math.min(C2TerrainNativeFollowPlanner.MAX_LATERAL_DETOURS,s.detourHops+1);
        } else {
            s.detourSide=0;s.detourHops=0;
            s.detourStability.clear();
        }
    }

    /** Follow-only steering: rotate at most 18 degrees per tick while idle.
     * Once aligned, the ORIGINAL C1 movement owner executes a freshly
     * validated segment. Never rotate while a ticket or combat is active. */
    private static String beginSmoothSegment(Session s,C1PlayerAvatar avatar) {
        if(s.route==null || s.route.waiting() || s.route.done())
            return "F2 invalid smooth route phase";
        var to=s.route.currentTarget();
        C2MoveGeometry.Plan intent;
        try { intent=C2MoveGeometry.plan(avatar.getX(),avatar.getY(),avatar.getZ(),
                to.x()+.5,avatar.getY(),to.z()+.5); }
        catch(IllegalArgumentException error) {return "F2 invalid heading: "+error.getMessage();}
        float current=avatar.getYRot();
        // C-3D: when an upcoming verified Follow route includes a typed
        // elevation transition, claim a bounded mixed lease under ONE C1
        // token instead of tearing down WALK at each 3D waypoint. Revert to
        // the proven standalone C1 primitives if a mixed lease is refused.
        if(C2WaypointBridge.beginFollowMixed3DChain(s.route,avatar,s.server,s.npc,s.profile)) {
            s.mixed3DStarts++;
            s.segmentTicks=0;
            transition(s,"MOVING","C-3D integrated WALK/STEP/JUMP/DESCEND chain generation="
                    +s.policy.generation());
            return null;
        }
        // Flat WALK uses one C1-owned continuous steering session across a
        // bounded chain; yaw changes WHILE moving, no stop-and-pre-turn.
        if(s.route.currentKind()==C2TraversabilityGraph.MoveKind.WALK) {
            String error=C2WaypointBridge.beginFollowWalkChain(s.route,avatar,s.server,s.npc,s.profile);
            if(error==null) {
                s.segmentTicks=0;
                transition(s,"MOVING","F2 continuous verified WALK chain generation="+s.policy.generation());
            }
            return error;
        }
        // Vertical primitives intentionally retain their standalone C1 token
        // and safe pre-turn/landing verification.
        if(!C2F2Locomotion.aligned(current,intent.yaw())) {
            float next=C2F2Locomotion.approach(current,intent.yaw());
            String rotated=C1P1MotionRuntime.turn(s.server,s.npc,next);
            if(!rotated.startsWith("Turned ")) return "F2 rotate refused: "+rotated;
            transition(s,"TURNING","Smooth yaw alignment before C1 ticket");
            return null;
        }
        String error=C2WaypointBridge.beginSegment(s.route,avatar,true,s.server,s.npc,s.profile);
        if(error==null) {
            s.segmentTicks=0;
            transition(s,"MOVING","F2 verified smooth waypoint generation="+s.policy.generation());
        }
        return error;
    }

    static void beforeServerStop(MinecraftServer server) {
        for(Session s:List.copyOf(SESSIONS.values())) if(s.server==server) close(s,"STOPPED","Server stopping; no follow replay");
        ticks=0;lastPlanTick=-1;
    }
}
