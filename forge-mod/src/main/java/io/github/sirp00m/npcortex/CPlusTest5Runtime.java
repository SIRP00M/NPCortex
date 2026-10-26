package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.sirp00m.npcortex.cplus.CPlusF1Delivery;
import io.github.sirp00m.npcortex.cplus.CPlusLocalAuth;
import io.github.sirp00m.npcortex.cplus.CPlusTransport;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * TEMPORARY, operator-initiated Test-5 bridge; remove after Transport E acceptance.
 * Does not implement movement, cancel, recovery or world mutation: calls the
 * existing G2/G3 operator paths with the original online requester identity.
 * Backend status is polled READ-ONLY. Any uncertainty => HOLD; no move replay.
 * In-memory only: no test session is resumed after restart.
 */
final class CPlusTest5Runtime {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<MinecraftServer,Run> RUNS = new IdentityHashMap<>();
    private static final Map<MinecraftServer,ExecutorService> WORKERS = new IdentityHashMap<>();
    private static final int POLL_TICKS = 10;
    private static final int LIMIT_TICKS = 1800;
    private static final double NEAR_RADIUS = 2.5;

    private enum Phase { PREFLIGHT, ADMITTING, RACING, RESOLVING, PASS, HOLD, INCONCLUSIVE, ABORTED }
    private static final class Run {
        final MinecraftServer server;
        final UUID requester, actor, world;
        final String name, dimension, session;
        final int x,y,z;
        final URI base;
        final String token;
        Phase phase=Phase.PREFLIGHT;
        String baselineTask="", task="", goal="", ackState="", latestBackend="NONE", note="";
        int ticks, nextPoll=0, terminalWait=0;
        boolean queryPending, cancelIssued, terminalSubmitted;
        Run(MinecraftServer server,UUID requester,UUID actor,UUID world,String name,String dimension,
            String session,int x,int y,int z,URI base,String token) {
            this.server=server;this.requester=requester;this.actor=actor;this.world=world;
            this.name=name;this.dimension=dimension;this.session=session;
            this.x=x;this.y=y;this.z=z;this.base=base;this.token=token;
        }
    }
    private CPlusTest5Runtime() {}

    private static ExecutorService worker(MinecraftServer server) {
        return WORKERS.computeIfAbsent(server, ignored -> new ThreadPoolExecutor(
                1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),
                job->{Thread thread=new Thread(job,"npcortex-temp-test5");thread.setDaemon(true);return thread;},
                new ThreadPoolExecutor.AbortPolicy()));
    }
    private static void say(CommandSourceStack src,String message) {
        src.sendSuccess(()->Component.literal("NPCortex Test5: "+message),false);
    }
    private static void notify(Run run,String message) {
        ServerPlayer requester=run.server.getPlayerList().getPlayer(run.requester);
        if(requester!=null)requester.sendSystemMessage(Component.literal("NPCortex Test5: "+message));
        LOG.info("NPCortex TEMP Test5 npc={} phase={} {}",run.name,run.phase,message);
    }

    private static boolean terminal(Run run) {
        return run.phase==Phase.PASS || run.phase==Phase.HOLD || run.phase==Phase.INCONCLUSIVE || run.phase==Phase.ABORTED;
    }
    private static void stop(Run run,Phase status,String reason) {
        if(terminal(run))return;
        run.phase=status;run.note=reason;
        notify(run,status+" | "+reason+" | task="+run.task+" goal="+run.goal+
                "; existing C2/G3 attempt preserved, no movement replay");
    }

    static int start(CommandSourceStack src,String name) {
        if(src.getPlayer()==null || !src.hasPermission(2)) {
            say(src,"HOLD: run /function from an ONLINE OP player, not console/server tick");return 0;
        }
        MinecraftServer server=src.getServer();
        Run old=RUNS.get(server);
        if(old!=null && !terminal(old)) {say(src,"HOLD: prior test in progress; use autotest5 status");return 0;}
        // Do not replace an active same-actor or unfinished task by rerunning /function.
        C1IdentityData.Entry id=C1IdentityData.get(server).find(name);
        C1PlayerAvatar avatar=C1P0Runtime.activeAvatar(server,name);
        if(id==null || avatar==null || avatar.isRemoved() || !id.active() ||
                !avatar.getUUID().equals(id.profileId()) || !avatar.onGround() ||
                !avatar.serverLevel().dimension().location().toString().equals(id.dimension()) ||
                C2AgentRuntime.hasActiveActor(id.actorId()) || C2FollowRuntime.hasActiveActor(id.actorId()) ||
                C1P1MotionRuntime.c2Receipt(id.profileId()).active() ||
                CPlusE3G2Runtime.pending(server,id.actorId()) ||
                CPlusE3G3Runtime.pending(server,id.actorId()) ||
                CPlusTransportRecovery.busy(server,id.actorId())) {
            say(src,"HOLD: NPC unavailable, not grounded, or movement/Follow/transport busy");return 0;
        }
        C2ActionJournal.Row journal=C2ActionJournal.get(server).find(id.actorId());
        if(journal!=null && journal.uncertain()) {say(src,"HOLD: unresolved C2 journal; DO NOT reset");return 0;}
        BlockPos target=chooseFlatTarget(avatar);
        if(target==null) {say(src,"HOLD: no flat, loaded, unobstructed 5-block corridor around Yui");return 0;}
        final URI base;
        final String token;
        final JsonObject snap;
        try {
            base=CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get());
            token=CPlusLocalAuth.readToken();
            snap=CPlusF2WorldSampler.capture(server,name);
        } catch(Exception ex) {say(src,"HOLD: backend auth or world snapshot unavailable");return 0;}
        Run run=new Run(server,src.getPlayer().getUUID(),id.actorId(),
                C1IdentityData.get(server).worldId(),name,
                snap.get("dimension_id").getAsString(),snap.get("session_id").getAsString(),
                target.getX(),target.getY(),target.getZ(),base,token);
        RUNS.put(server,run);
        say(src,"PREFLIGHT using NPC position, target=("+run.x+","+run.y+","+run.z+")"
                +"; checking durable backend fence before ONE move");
        poll(run);
        return 1;
    }

    /** Exact avatar position, never the command executor's location. Conservative
     * surface-air/ground/collision tests across every block of a five-block line.
     * This is NOT path planning or proof of reachability; C2 remains authoritative.
     */
    static BlockPos chooseFlatTarget(C1PlayerAvatar avatar) {
        ServerLevel level=avatar.serverLevel();
        BlockPos from=avatar.blockPosition();
        int[][] directions={{1,0},{0,1},{-1,0},{0,-1}};
        for(int[] delta:directions) {
            boolean safe=true;
            for(int i=1;i<=5;i++) {
                BlockPos p=from.offset(delta[0]*i,0,delta[1]*i);
                if(!level.hasChunkAt(p) || !level.hasChunkAt(p.below()) ||
                        !level.getWorldBorder().isWithinBounds(p) ||
                        !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir() ||
                        !level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP) ||
                        level.getBlockState(p.below()).is(Blocks.MAGMA_BLOCK) ||
                        level.getBlockState(p.below()).is(Blocks.CAMPFIRE) ||
                        level.getBlockState(p.below()).is(Blocks.SOUL_CAMPFIRE) ||
                        !level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty()) {
                    safe=false;break;
                }
                double dx=p.getX()+0.5-avatar.getX(), dy=p.getY()-avatar.getY(),dz=p.getZ()+0.5-avatar.getZ();
                AABB candidate=avatar.getBoundingBox().move(dx,dy,dz);
                if(!level.noCollision(avatar,candidate)) {safe=false;break;}
            }
            if(safe)return from.offset(delta[0]*5,0,delta[1]*5);
        }
        return null;
    }

    static int status(CommandSourceStack src) {
        Run run=RUNS.get(src.getServer());
        if(run==null) {say(src,"NONE (temporary harness inactive)");return 1;}
        say(src,"phase="+run.phase+" npc="+run.name+" target="+run.x+","+run.y+","+run.z+
                " backend="+run.latestBackend+" task="+run.task+" goal="+run.goal+
                " cancelIssued="+run.cancelIssued+" ACK="+run.ackState+" note="+run.note);
        return 1;
    }
    static int abort(CommandSourceStack src) {
        Run run=RUNS.get(src.getServer());
        if(run==null || terminal(run)) {say(src,"No active test to abort");return 1;}
        if(!run.requester.equals(src.getPlayer()==null?null:src.getPlayer().getUUID())) {
            say(src,"HOLD: only original operator can abort this test");return 0;
        }
        stop(run,Phase.ABORTED,"local harness stopped ONLY; existing C2/G3 work may still need manual reconciliation");
        return 1;
    }

    private static void poll(Run run) {
        if(terminal(run) || run.queryPending)return;
        run.queryPending=true;
        JsonObject query=new JsonObject();
        query.addProperty("world_id",run.world.toString());query.addProperty("actor_id",run.actor.toString());
        try {
            CompletableFuture.supplyAsync(()->CPlusTransport.exchange(
                    run.base.resolve("/v1/cplus/e3/status"),run.token,query.toString(),5,24000),worker(run.server))
                    .whenComplete((reply,error)->run.server.execute(()->{
                        if(RUNS.get(run.server)!=run || terminal(run))return;
                        run.queryPending=false;
                        if(error!=null) {
                            stop(run,Phase.HOLD,"backend read-only status ["+CPlusTransport.diagnosticCode(error)+"]");return;
                        }
                        try {onStatus(run,JsonParser.parseString(reply).getAsJsonObject());}
                        catch(RuntimeException ex) {stop(run,Phase.HOLD,"invalid or mismatched backend status; inspect backend log");}
                    }));
        } catch(RejectedExecutionException ex) {
            run.queryPending=false;stop(run,Phase.HOLD,"test status worker busy");
        }
    }

    private static String get(JsonObject o,String key) {
        if(!o.has(key) || o.get(key).isJsonNull())throw new IllegalArgumentException("missing "+key);
        return o.get(key).getAsString();
    }
    private static void onStatus(Run run,JsonObject row) {
        if(!run.world.toString().equals(get(row,"world_id")) ||
                !run.actor.toString().equals(get(row,"actor_id")) ||
                row.get("world_effects").getAsBoolean())
            throw new IllegalArgumentException("backend identity or effect mismatch");
        String state=get(row,"state");
        if(!java.util.Set.of("NONE","RESERVED","C2_ACCEPTED","CANCEL_PENDING","HOLD","COMPLETED","CANCELLED").contains(state))
            throw new IllegalArgumentException("invalid state");
        run.latestBackend=state;
        if(run.phase==Phase.PREFLIGHT) {
            if(!java.util.Set.of("NONE","COMPLETED","CANCELLED").contains(state)) {
                stop(run,Phase.HOLD,"existing unresolved backend attempt "+state+"; not overwritten");return;
            }
            run.baselineTask=row.has("task_id") ? get(row,"task_id") : "";
            ServerPlayer player=online(run);
            if(player==null || !identityStillLive(run)) {
                stop(run,Phase.HOLD,"operator/actor/session changed before move");return;
            }
            run.phase=Phase.ADMITTING;
            int result=CPlusE3G2Runtime.move(player.createCommandSourceStack(),run.name,run.x,run.y,run.z);
            if(result!=1) {stop(run,Phase.HOLD,"G2 refused move synchronously; inspect game output");return;}
            notify(run,"ONE taskmove submitted; waiting for unique backend task/goal ID");
            return;
        }
        if(run.phase==Phase.ADMITTING) {
            if("NONE".equals(state) || (row.has("task_id") && run.baselineTask.equals(get(row,"task_id"))))return;
            if(!validAttempt(run,row))throw new IllegalArgumentException("unexpected G2 attempt");
            if(run.task.isEmpty())run.task=get(row,"task_id");
            if(!run.task.equals(get(row,"task_id")))throw new IllegalArgumentException("attempt switched");
            if("HOLD".equals(state)) {stop(run,Phase.HOLD,"new G2 attempt is HOLD");return;}
            if("C2_ACCEPTED".equals(state)) {
                run.goal=get(row,"goal_id");run.phase=Phase.RACING;
                notify(run,"G2 C2_ACCEPTED goal="+run.goal+"; approaching target to trigger cancel");
            } else if("RESERVED".equals(state))return;
            else throw new IllegalArgumentException("unexpected admission state "+state);
        } else if(run.phase==Phase.RACING || run.phase==Phase.RESOLVING) {
            if(!validAttempt(run,row) || !run.task.equals(get(row,"task_id")) ||
                    !run.goal.equals(get(row,"goal_id")))
                throw new IllegalArgumentException("attempt/goal changed");
            if("HOLD".equals(state))stop(run,Phase.HOLD,"backend marked attempt HOLD");
            // All decisions/terminal checks are on Minecraft's server tick.
        }
    }
    private static boolean validAttempt(Run run,JsonObject row) {
        return run.requester.toString().equals(get(row,"requester_id")) &&
                run.session.equals(get(row,"session_id")) &&
                run.dimension.equals(get(row,"dimension_id")) &&
                get(row,"task_id").length()>0 &&
                row.getAsJsonObject("target").get("x").getAsInt()==run.x &&
                row.getAsJsonObject("target").get("y").getAsInt()==run.y &&
                row.getAsJsonObject("target").get("z").getAsInt()==run.z;
    }
    private static ServerPlayer online(Run run) {
        ServerPlayer player=run.server.getPlayerList().getPlayer(run.requester);
        return player!=null && player.hasPermissions(2) ? player : null;
    }
    private static boolean identityStillLive(Run run) {
        C1IdentityData.Entry id=C1IdentityData.get(run.server).find(run.name);
        C1PlayerAvatar avatar=C1P0Runtime.activeAvatar(run.server,run.name);
        if(id==null || !id.actorId().equals(run.actor) || avatar==null || avatar.isRemoved() ||
                !avatar.serverLevel().dimension().location().toString().equals(id.dimension()))return false;
        try {
            JsonObject snapshot=CPlusF2WorldSampler.capture(run.server,run.name);
            return run.session.equals(get(snapshot,"session_id")) &&
                    run.dimension.equals(get(snapshot,"dimension_id"));
        } catch(RuntimeException ex) {return false;}
    }
    private static boolean exactJournal(C2ActionJournal.Row journal,Run run,String state) {
        return journal!=null && state.equals(journal.state()) &&
                run.goal.equals(journal.goal()==null?"":journal.goal().toString());
    }
    private static void advance(Run run) {
        if(run.goal.isEmpty() || run.task.isEmpty())return;
        C1PlayerAvatar avatar=C1P0Runtime.activeAvatar(run.server,run.name);
        C2ActionJournal.Row journal=C2ActionJournal.get(run.server).find(run.actor);
        boolean activeGoal=C2AgentRuntime.hasActiveGoal(run.actor,UUID.fromString(run.goal));
        boolean completed=exactJournal(journal,run,"COMPLETED");
        boolean cancelled=exactJournal(journal,run,"CANCELLED");
        String c2=completed?"COMPLETED":cancelled?"CANCELLED":activeGoal?"ACTIVE":"UNKNOWN";
        boolean witness=completed && C2AgentRuntime.terminalWitness(run.actor,UUID.fromString(run.goal))!=null;
        boolean near=avatar!=null && Math.hypot(avatar.getX()-(run.x+0.5),avatar.getZ()-(run.z+0.5))<=NEAR_RADIUS;
        var next=CPlusTest5Decision.choose(run.latestBackend,c2,activeGoal,witness,near,run.cancelIssued);
        if(next==CPlusTest5Decision.Next.HOLD) {
            stop(run,Phase.HOLD,"C2/backend goal/state lacks safe correlation; C2="+c2+" G2="+run.latestBackend);return;
        }
        if(next==CPlusTest5Decision.Next.TERMINAL) {
            if(!run.ackState.equals(run.latestBackend)) {
                if(++run.terminalWait>100)stop(run,Phase.HOLD,"durable terminal seen but client terminal ACK not confirmed");
                return;
            }
            if(("COMPLETED".equals(run.latestBackend) && !completed) ||
                    ("CANCELLED".equals(run.latestBackend) && !cancelled)) {
                stop(run,Phase.HOLD,"durable outcome mismatches exact C2 journal");return;
            }
            stop(run,run.cancelIssued?Phase.PASS:Phase.INCONCLUSIVE,
                    "G3 durable "+run.latestBackend+" + exact C2 goal + validated client ACK"+
                            (run.cancelIssued?"; cancel was actually issued":"; cancel was never issued"));
            return;
        }
        if(run.terminalSubmitted || CPlusE3G3Runtime.pending(run.server,run.actor))return;
        ServerPlayer operator=online(run);
        if(operator==null) {stop(run,Phase.HOLD,"requester offline or permission revoked");return;}
        int submitted=1;
        if(next==CPlusTest5Decision.Next.CANCEL) {
            // Mark before async submission so the test never spams or retries cancel.
            run.cancelIssued=true;run.phase=Phase.RESOLVING;
            submitted=CPlusE3G3Runtime.cancelMove(operator.createCommandSourceStack(),run.name);
            notify(run,"near goal (<=2.5 blocks); exact-goal taskmovecancel requested ONCE");
        } else if(next==CPlusTest5Decision.Next.FINALIZE) {
            run.terminalSubmitted=true;run.phase=Phase.RESOLVING;
            submitted=CPlusE3G3Runtime.finalizeMove(operator.createCommandSourceStack(),run.name);
            notify(run,"verified C2 completion; taskmovefinalize requested ONCE");
        } else if(next==CPlusTest5Decision.Next.RECONCILE) {
            run.terminalSubmitted=true;run.phase=Phase.RESOLVING;
            submitted=CPlusE3G3Runtime.reconcileCancel(operator.createCommandSourceStack(),run.name);
            notify(run,"verified exact-goal C2 cancellation; taskmovereconcile requested ONCE");
        }
        if(submitted==0)stop(run,Phase.HOLD,"G3 operation refused; inspect C2/G3 (no retry)");
    }

    static void onTerminalAck(MinecraftServer server,UUID actor,String task,String terminalState) {
        Run run=RUNS.get(server);
        if(run!=null && !terminal(run) && run.actor.equals(actor) && run.task.equals(task) &&
                java.util.Set.of("COMPLETED","CANCELLED").contains(terminalState)) {
            run.ackState=terminalState;
            notify(run,"validated G3 "+terminalState+" client-side durable ACK for task="+task);
        }
    }
    static void onServerTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        Run run=RUNS.get(event.getServer());
        if(run==null || terminal(run))return;
        if(++run.ticks>LIMIT_TICKS) {
            stop(run,Phase.HOLD,"test watchdog timeout; no automatic replay/retry");return;
        }
        if(online(run)==null || (run.ticks % 10==0 && !identityStillLive(run))) {
            stop(run,Phase.HOLD,"operator or NPC identity/session changed; no automatic retry");return;
        }
        if(run.phase==Phase.RACING || run.phase==Phase.RESOLVING)advance(run);
        if(!terminal(run) && ++run.nextPoll>=POLL_TICKS) {
            run.nextPoll=0;poll(run);
        }
    }
    static void onServerStopping(ServerStoppingEvent event) {
        Run run=RUNS.remove(event.getServer());
        if(run!=null && !terminal(run))notify(run,"stopped on server shutdown; old backend attempt preserved");
        ExecutorService executor=WORKERS.remove(event.getServer());
        if(executor!=null)executor.shutdownNow();
    }
}
