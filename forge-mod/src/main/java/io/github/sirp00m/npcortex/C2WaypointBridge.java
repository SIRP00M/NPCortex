package io.github.sirp00m.npcortex;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/** Server-thread-only bridge from an immutable, approved B3 route into existing C1 travel. */
final class C2WaypointBridge {
    private C2WaypointBridge() { }

    /** Fresh edge probe; P1-D may replan a rejected edge, never bypass it. */
    /** Only a proven unsafe edge or a grounded position deviation can trigger P1-D. */
    static boolean recoverableFailure(String detail) {
        return detail!=null && (detail.startsWith("P1-C path edge became unsafe")
                || detail.startsWith("P1-C NPC deviated from waypoint origin")
                || C2S4RecoveryPolicy.recoverableStartRefusal(detail));
    }

    static String beginSegment(C2AgentRuntime.Run run) {
        String error=beginSegment(run.route, run.avatar(), run.isAlive(), run.server, run.name, run.profile);
        if (error==null) run.ticks=0;
        return error;
    }

    /** Shared C1 execution bridge for static-goal and ephemeral moving-target owners.
     * Both callers have independently claimed exclusive actor ownership BEFORE this
     * method, and both must validate each returned C1 ticket on every tick.
     */
    static String beginSegment(C2WaypointExecution route,C1PlayerAvatar avatar, boolean alive,
                               net.minecraft.server.MinecraftServer server,String name,java.util.UUID profile) {
        if (route==null || route.done() || route.waiting()) return "Invalid P1-C route state";
        if (avatar==null || !alive || !avatar.onGround()) return "P1-C actor unavailable/not grounded";
        if (C1P1MotionRuntime.c2Receipt(profile).active()) return "Another C1 movement owns the avatar";
        var to=route.currentTarget();
        if (!avatar.serverLevel().hasChunkAt(new BlockPos(to.x(),to.supportY(),to.z())))
            return "P1-C next waypoint chunk is not loaded";
        // B1 collision + support checks are recomputed on server thread before EVERY segment.
        C2GraphWorldProbe.Capture captured=C2GraphWorldProbe.captureWithSnapshot(avatar,C2NavGrid.MAX_RADIUS);
        if (!route.edgeAllowed(captured.graph())) return "P1-C path edge became unsafe; replanning NOT enabled";
        // Any combat displacement that took us off the previous node must not be
        // masked by a stale edge from an otherwise correctly scanned graph.
        var from=route.previousNode();
        if (Mth.floor(avatar.getX())!=from.x() || Mth.floor(avatar.getZ())!=from.z()
                || Math.abs(avatar.getY()-from.footY())>0.35)
            return "P1-C NPC deviated from waypoint origin";
        double tx=to.x()+0.5, ty=to.footY(), tz=to.z()+0.5;
        // F2: center-to-center graph clearance alone is insufficient when an
        // avatar starts off-center. Verify the entire REAL player-sized
        // corridor for diagonals and coalesced flat movement; fail closed.
        if(route.currentKind()==C2TraversabilityGraph.MoveKind.WALK
                && (Math.abs(from.x()-to.x())>1 || Math.abs(from.z()-to.z())>1
                    || (from.x()!=to.x() && from.z()!=to.z()))
                && !sweptWalkClear(avatar,tx,ty,tz))
            return "F2 direct walking corridor blocked or unloaded";
        C2MoveGeometry.Plan plan;
        // Orientation/distance are horizontal, while typed 3D edge owns the
        // independent height target; never bypass geometry constraints by
        // claiming a vertical move is an ordinary C2 flat WALK.
        try { plan=C2MoveGeometry.plan(avatar.getX(),avatar.getY(),avatar.getZ(),tx,avatar.getY(),tz); }
        catch (IllegalArgumentException ex) { return "P1-C segment geometry rejected: "+ex.getMessage(); }
        String turned=C1P1MotionRuntime.turn(server,name,plan.yaw());
        if (!turned.startsWith("Turned ")) return "P1-C C1 rotation refused: "+turned;
        var kind=route.currentKind();
        // C1 S2 bounds a traversal to 0.65..1.45 blocks. A newly spawned
        // avatar can stand at the near/far edge of its starting block, making
        // the adjacent *center* 0.5 or 1.5 blocks away. Center first with a
        // separate verified WALK ticket; never stretch the physical primitive.
        if (kind!=C2TraversabilityGraph.MoveKind.WALK
                && (plan.distance()<C1S2TraversalPlan.MIN_DISTANCE
                || plan.distance()>C1S2TraversalPlan.MAX_DISTANCE)) {
            double cx=from.x()+.5,cz=from.z()+.5;
            C2MoveGeometry.Plan align;
            try { align=C2MoveGeometry.plan(avatar.getX(),avatar.getY(),avatar.getZ(),
                    cx,avatar.getY(),cz); }
            catch (IllegalArgumentException ex) { return "S3 origin centering unsafe: "+ex.getMessage(); }
            String yaw=C1P1MotionRuntime.turn(server,name,align.yaw());
            if (!yaw.startsWith("Turned ")) return "S3 C1 centering rotation refused: "+yaw;
            String center=C1P1MotionRuntime.moveC2Precision(server,name,"forward",align.distance());
            if (!center.startsWith("Started B+ motion:")) return "S3 C1 centering refused: "+center;
            var owned=C1P1MotionRuntime.c2Ticket(profile);
            if (!owned.active() || owned.token()==null || owned.combatActive())
                return "S3 C1 centering ticket unavailable";
            route.startedCentering(owned.token());
            
            return null;
        }
        String moved;
        if (kind==C2TraversabilityGraph.MoveKind.WALK) {
            moved=C1P1MotionRuntime.moveC2Precision(server,name,"forward",plan.distance());
            if (!moved.startsWith("Started B+ motion:")) return "P1-C C1 movement refused: "+moved;
        } else {
            if (Math.abs(avatar.getY()-route.previousNode().footY())
                    > C1S2TraversalPlan.HEIGHT_TOLERANCE)
                return "S3 vertical origin height deviated; no movement";
            C1S2TraversalPlan.Kind primitive=C1S2TraversalPlan.Kind.valueOf(kind.name());
            moved=C1P1MotionRuntime.traverseC2(server,name,primitive,plan.distance());
            if (!moved.startsWith("S3 C2 traversal started "))
                return "S3 C1 traversal refused: "+moved;
        }
        var ticket=C1P1MotionRuntime.c2Ticket(profile);
        if (!ticket.active() || ticket.token()==null || ticket.combatActive()) {
            C1P1MotionRuntime.stop(server,name);
            return "P1-C C1 motion ticket unavailable";
        }
        route.started(ticket.token());
        
        return null;
    }

    /** F2 Fix #1 Follow-only WALK chain: no new owner and no C0 goal.
     * All directed edges, omitted cells, and swept player collision volumes
     * are checked BEFORE C1 accepts one bounded motion ticket. */
    static String beginFollowWalkChain(C2WaypointExecution route,C1PlayerAvatar avatar,
            net.minecraft.server.MinecraftServer server,String name,java.util.UUID profile) {
        if(route==null || route.done() || route.waiting()
                || route.currentKind()!=C2TraversabilityGraph.MoveKind.WALK)
            return "F2 no available verified flat WALK chain";
        if(avatar==null || !avatar.onGround() || C1P1MotionRuntime.c2Ticket(profile).active())
            return "F2 actor busy/not grounded";
        int first=route.currentIndex();
        java.util.ArrayList<C1F2ContinuousWalk.Point> targets=new java.util.ArrayList<>();
        double length=0,x=avatar.getX(),z=avatar.getZ(),y=avatar.getY();
        for(int index=first;index<route.path().size()
                && targets.size()<C1F2ContinuousWalk.MAX_TARGETS;index++) {
            var node=route.path().get(index);
            if(Math.abs(node.footY()-y)>.02 ||
                    (index>first && Math.abs(route.path().get(index-1).footY()-node.footY())>.0001)) break;
            if(index>first && C2TraversabilityGraph.kind(
                    node.footY()-route.path().get(index-1).footY())
                    !=C2TraversabilityGraph.MoveKind.WALK) break;
            double tx=node.x()+.5,tz=node.z()+.5;
            double step=Math.hypot(tx-x,tz-z);
            if(step<.03 || step>4.5 || length+step>C1F2ContinuousWalk.MAX_TOTAL_LENGTH) break;
            targets.add(new C1F2ContinuousWalk.Point(tx,y,tz));
            length+=step;x=tx;z=tz;
        }
        if(targets.isEmpty()) return "F2 no valid forward WALK target";
        C2GraphWorldProbe.Capture capture=C2GraphWorldProbe.captureWithSnapshot(avatar,C2NavGrid.MAX_RADIUS);
        if(!route.walkChainAllowed(capture.graph(),targets.size()))
            return "P1-C path edge became unsafe; follow chain rejected";
        double fx=avatar.getX(),fz=avatar.getZ();
        for(var target:targets) {
            if(!sweptFollowCorridorClear(avatar,fx,fz,target.x(),target.y(),target.z()))
                return "F2 continuous WALK corridor blocked/unloaded";
            fx=target.x();fz=target.z();
        }
        String started=C1P1MotionRuntime.moveC2ContinuousWalk(server,name,targets);
        if(!started.startsWith("F2 continuous WALK started")) return started;
        var ticket=C1P1MotionRuntime.c2Ticket(profile);
        if(!ticket.active() || ticket.token()==null || ticket.combatActive()
                || ticket.origin()!=C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND) {
            C1P1MotionRuntime.stop(server,name);
            return "F2 C1 continuous WALK ticket unavailable";
        }
        route.startedWalkChain(ticket.token(),targets.size());
        return null;
    }

    /** F2 Fix #2: opportunistic, bounded LOOK-AHEAD HANDOFF within an already
     * claimed Follow route. The route is still WAITING for the SAME C1 token;
     * we must validate every additional edge against a fresh local graph and
     * sweep every new player-sized corridor before atomically extending C1.
     * Failure means no extension, NOT a second concurrent motion command. */
    static boolean extendFollowWalkChain(C2WaypointExecution route,C1PlayerAvatar avatar,
            java.util.UUID profile) {
        if(route==null || avatar==null || !route.waiting() || route.done()
                || !avatar.onGround()) return false;
        java.util.UUID ticket=route.expectedTicket();
        var progress=C1P1MotionRuntime.c2WalkProgress(profile,ticket);
        if(progress==null || progress.remaining()>2 || progress.remaining()<1) return false;
        int start=route.lastCommittedIndex()+1;
        if(start>=route.path().size()) return false;
        int previous=start-1;
        var anchor=route.path().get(previous);
        if(Math.abs(avatar.getY()-anchor.footY())>.02) return false;
        java.util.ArrayList<C1F2ContinuousWalk.Point> more=new java.util.ArrayList<>();
        double x=anchor.x()+.5,z=anchor.z()+.5,y=anchor.footY(),length=0;
        for(int i=start;i<route.path().size() && more.size()<C1F2ContinuousWalk.MAX_TARGETS;i++) {
            var node=route.path().get(i);
            if(route.path().get(i-1).footY()!=node.footY()) break;
            double nx=node.x()+.5,nz=node.z()+.5;
            double step=Math.hypot(nx-x,nz-z);
            if(step<.03 || step>4.5 || length+step>C1F2ContinuousWalk.MAX_TOTAL_LENGTH) break;
            more.add(new C1F2ContinuousWalk.Point(nx,y,nz));
            length+=step;x=nx;z=nz;
        }
        if(more.isEmpty()) return false;
        C2GraphWorldProbe.Capture captured=C2GraphWorldProbe.captureWithSnapshot(avatar,C2NavGrid.MAX_RADIUS);
        if(!route.walkExtensionAllowed(captured.graph(),more.size())) return false;
        x=anchor.x()+.5;z=anchor.z()+.5;
        for(var next:more) {
            if(!sweptFollowCorridorClear(avatar,x,z,next.x(),next.y(),next.z())) return false;
            x=next.x();z=next.z();
        }
        // Both calls execute on the Forge server thread; no async race between
        // validated route count and extending the single owned C1 session.
        if(!C1P1MotionRuntime.extendC2ContinuousWalk(profile,ticket,more)) return false;
        route.extendedWalkChain(ticket,more.size());
        return true;
    }

    /** C-S2: while the SAME Follow WALK token is active, append a freshly
     * planned flat continuation. No C1 motion restart, unverified steering,
     * combat override or journal replay. Local 3D graph + current world sweep
     * independently certify every new edge. Failure has NO side effect. */
    static boolean appendPersistentFollowWalk(C2WaypointExecution route,
            C1PlayerAvatar avatar,java.util.UUID profile,
            C2TraversabilityGraph graph,java.util.List<C2TraversabilityGraph.Node> extra) {
        if(route==null || avatar==null || graph==null || extra==null
                || !route.canAppendWalk(extra) || !avatar.onGround()) return false;
        java.util.UUID token=route.expectedTicket();
        var ticket=C1P1MotionRuntime.c2Ticket(profile);
        var progress=C1P1MotionRuntime.c2WalkProgress(profile,token);
        if(!C2FollowPolicy.ownedTicket(token,ticket.token(),ticket.active(),ticket.origin())
                || ticket.combatActive() || progress==null
                || progress.remaining()<1 || progress.remaining()>2) return false;
        var anchor=route.path().get(route.lastCommittedIndex());
        java.util.ArrayList<C2TraversabilityGraph.Node> proof=new java.util.ArrayList<>();
        proof.add(anchor); proof.addAll(extra);
        C2WaypointExecution validated;
        try { validated=C2WaypointExecution.followSmoothed(proof); }
        catch(IllegalArgumentException invalid) { return false; }
        if(!validated.walkChainAllowed(graph,extra.size())) return false;
        java.util.ArrayList<C1F2ContinuousWalk.Point> targets=new java.util.ArrayList<>();
        double y=anchor.footY(), x=anchor.x()+.5, z=anchor.z()+.5, length=0;
        for(var node:extra) {
            double nx=node.x()+.5,nz=node.z()+.5;
            double d=Math.hypot(nx-x,nz-z);
            if(d<.03 || d>4.5 || length+d>C1F2ContinuousWalk.MAX_TOTAL_LENGTH) return false;
            if(!sweptFollowCorridorClear(avatar,x,z,nx,y,nz)) return false;
            targets.add(new C1F2ContinuousWalk.Point(nx,y,nz));
            length+=d;x=nx;z=nz;
        }
        // Server-thread atomicity: no other owner can interleave between the
        // token-fenced C1 extension and its matching C2 receipt commitment.
        if(!C1P1MotionRuntime.extendC2ContinuousWalk(profile,token,targets)) return false;
        route.appendWalkAfterC1Extension(token,extra);
        return true;
    }

    private static boolean sweptWalkClear(C1PlayerAvatar avatar,double tx,double ty,double tz) {
        return sweptWalkClear(avatar,avatar.getX(),avatar.getZ(),tx,ty,tz);
    }

    static boolean sweptWalkClear(C1PlayerAvatar avatar,double sx,double sz,double tx,double ty,double tz) {
        if(Math.abs(avatar.getY()-ty)>0.0001) return false;
        double half=avatar.getBbWidth()*.5+.01;
        double minX=Math.min(sx,tx)-half, maxX=Math.max(sx,tx)+half;
        double minZ=Math.min(sz,tz)-half, maxZ=Math.max(sz,tz)+half;
        int lowX=Mth.floor(minX),highX=Mth.floor(maxX);
        int lowZ=Mth.floor(minZ),highZ=Mth.floor(maxZ);
        for(int x:new int[]{lowX,highX}) for(int z:new int[]{lowZ,highZ}) {
            var corner=new BlockPos(x,Mth.floor(ty),z);
            if(!avatar.serverLevel().hasChunkAt(corner)
                    || !avatar.serverLevel().getWorldBorder().isWithinBounds(corner)) return false;
        }
        AABB corridor=new AABB(minX,ty+.001,minZ,maxX,ty+avatar.getBbHeight(),maxZ);
        return avatar.serverLevel().noCollision(avatar,corridor)
                && C2BlockTraversalWorld.assess(avatar.serverLevel(),corridor).passable();
    }

    static boolean sweptFollowCorridorClear(C1PlayerAvatar avatar,double sx,double sz,double tx,double ty,double tz) {
        if(avatar==null || Math.abs(avatar.getY()-ty)>0.0001
                || !Double.isFinite(sx) || !Double.isFinite(sz)
                || !Double.isFinite(tx) || !Double.isFinite(tz)) return false;
        // H2: bounded true player-width sweep, NOT the entire enclosing
        // rectangle. Check loaded chunks, world border, Vanilla collision and
        // dynamic hazard at every sampled footprint. C2 graph independently
        // certifies compatible floor/support before this is ever called.
        double length=Math.hypot(tx-sx,tz-sz);
        int steps=Math.max(1,(int)Math.ceil(length/.16));
        if(steps>48) return false;
        double half=avatar.getBbWidth()*.5+.01;
        for(int i=0;i<=steps;i++) {
            double t=(double)i/steps,x=sx+(tx-sx)*t,z=sz+(tz-sz)*t;
            for(double ox:new double[]{-half,half}) for(double oz:new double[]{-half,half}) {
                BlockPos column=BlockPos.containing(x+ox,ty,z+oz);
                if(!avatar.serverLevel().hasChunkAt(column)
                        || !avatar.serverLevel().getWorldBorder().isWithinBounds(column)) return false;
            }
            AABB body=new AABB(x-half,ty+.001,z-half,x+half,ty+avatar.getBbHeight(),z+half);
            if(!avatar.serverLevel().noCollision(avatar,body)
                    || !C2BlockTraversalWorld.assess(avatar.serverLevel(),body).passable()) return false;
        }
        return true;
    }

}
