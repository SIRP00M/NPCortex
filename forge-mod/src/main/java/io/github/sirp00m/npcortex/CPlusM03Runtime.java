package io.github.sirp00m.npcortex;

import com.google.gson.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.sirp00m.npcortex.cplus.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/** TEMP opt-in acceptance observer. No automatic movement, recovery, inventory
 * changes or process control. All live reads on server thread, IO on worker. */
final class CPlusM03Runtime {
    private static final Map<MinecraftServer,Session> SESSIONS=new IdentityHashMap<>();
    private static final class Session {
        final UUID operator,run=UUID.randomUUID();
        final ExecutorService io=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),
                r->{Thread t=new Thread(r,"npcortex-m03-evidence");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        long tickStart,started;
        int remaining,expected;
        String[] names;
        UUID measurement;
        final Map<String,String> observed=new HashMap<>();
        JsonArray transitions=new JsonArray();
        final ArrayList<Double> ticks=new ArrayList<>();
        boolean invalid;
        Session(UUID operator){this.operator=operator;}
    }
    static LiteralArgumentBuilder<CommandSourceStack> commands(){
        // Minecraft loads .mcfunction commands using a synthetic level-2 source
        // without a player entity.  A player-only Brigadier requirement hides
        // this literal during function parsing and causes "Unknown function".
        // Keep the parse gate permission-only; enforce real OP/operator identity
        // at execution time in enable() and session() below.
        var root=Commands.literal("m03").requires(s->s.hasPermission(2));
        root.then(Commands.literal("enable").executes(c->{
            if(c.getSource().getPlayer()==null || !c.getSource().hasPermission(2))
                return say(c.getSource(),"HOLD: enable requires an online OP player");
            if(SESSIONS.containsKey(c.getSource().getServer()))return say(c.getSource(),"HOLD: already enabled");
            SESSIONS.put(c.getSource().getServer(),new Session(c.getSource().getPlayer().getUUID()));
            return say(c.getSource(),"enabled; explicit commands only; evidence is NOT acceptance PASS");}));
        root.then(Commands.literal("disable").executes(c->{
            var s=session(c.getSource());if(s==null)return 0;
            if(s.remaining>0)return say(c.getSource(),"HOLD: measurement in progress");
            SESSIONS.remove(c.getSource().getServer());CPlusM03Fault.clear(CPlusOutbox.directory(c.getSource().getServer()));
            s.io.shutdown();return say(c.getSource(),"disabled; all durable records retained");}));
        root.then(Commands.literal("status").executes(c->{var s=session(c.getSource());return s==null?0:say(c.getSource(),"run="+s.run+" measurementTicksRemaining="+s.remaining);}));
        for(String op:new String[]{"snapshot","move","outage6a","loss-reserve"}) {
            root.then(Commands.literal(op).then(Commands.argument("name",StringArgumentType.word()).executes(c->
                action(c.getSource(),StringArgumentType.getString(c,"name"),op))));
        }
        // TEMP acceptance convenience: bind terminal fault to the backend's
        // current exact task instead of asking the operator to type a UUID.
        root.then(Commands.literal("loss-terminal-auto")
            .then(Commands.argument("name",StringArgumentType.word())
            .then(Commands.argument("route",StringArgumentType.word())
            .executes(c->terminalAuto(c.getSource(),StringArgumentType.getString(c,"name"),
                StringArgumentType.getString(c,"route"))))));
        root.then(Commands.literal("fault-clear").executes(c->{
            var s=session(c.getSource());if(s==null)return 0;
            CPlusM03Fault.clear(CPlusOutbox.directory(c.getSource().getServer()));
            return say(c.getSource(),"scoped test fault disarmed (durable records untouched)");}));
        root.then(Commands.literal("measure-auto")
            .then(Commands.argument("count",IntegerArgumentType.integer(1,10))
            .then(Commands.argument("seconds",IntegerArgumentType.integer(10,600))
            .executes(c->measureAuto(c.getSource(),IntegerArgumentType.getInteger(c,"count"),
                IntegerArgumentType.getInteger(c,"seconds"))))));
        root.then(Commands.literal("follow-self").then(Commands.argument("name",StringArgumentType.word())
            .executes(c->followSelf(c.getSource(),StringArgumentType.getString(c,"name"),true))));
        root.then(Commands.literal("unfollow").then(Commands.argument("name",StringArgumentType.word())
            .executes(c->followSelf(c.getSource(),StringArgumentType.getString(c,"name"),false))));
        root.then(Commands.literal("move-all").then(Commands.argument("count",IntegerArgumentType.integer(1,10))
            .executes(c->moveAll(c.getSource(),IntegerArgumentType.getInteger(c,"count")))));
        root.then(Commands.literal("loss-terminal").then(Commands.argument("name",StringArgumentType.word())
            .then(Commands.argument("route",StringArgumentType.word()).then(Commands.argument("task",StringArgumentType.word())
            .executes(c->terminalFault(c.getSource(),StringArgumentType.getString(c,"name"),
                StringArgumentType.getString(c,"route"),StringArgumentType.getString(c,"task")))))));
        root.then(Commands.literal("measure").then(Commands.argument("count",IntegerArgumentType.integer(1,10))
            .then(Commands.argument("seconds",IntegerArgumentType.integer(10,600)).then(Commands.argument("names",StringArgumentType.greedyString())
            .executes(c->measure(c.getSource(),IntegerArgumentType.getInteger(c,"count"),IntegerArgumentType.getInteger(c,"seconds"),
                StringArgumentType.getString(c,"names")))))));
        return root;
    }
    private static int say(CommandSourceStack src,String text){src.sendSuccess(()->Component.literal("NPCortex M03: "+text),false);return 1;}
    private static Session session(CommandSourceStack src){
        Session s=SESSIONS.get(src.getServer());
        if(s==null||src.getPlayer()==null||!src.hasPermission(2)||!s.operator.equals(src.getPlayer().getUUID())){
            say(src,"HOLD: enable as original online OP first");return null;}
        return s;
    }
    private static JsonObject live(MinecraftServer server,String name){
        JsonObject snap=CPlusF2WorldSampler.capture(server,name),out=new JsonObject();
        for(String key:new String[]{"world_id","actor_id","session_id","dimension_id","capture_tick"})out.add(key,snap.get(key));
        var id=C1IdentityData.get(server).find(name);var avatar=C1P0Runtime.activeAvatar(server,name);
        out.addProperty("name",name);out.addProperty("profile_id",id.profileId().toString());
        out.addProperty("x",avatar.getX());out.addProperty("y",avatar.getY());out.addProperty("z",avatar.getZ());
        out.addProperty("grounded",avatar.onGround());
        out.addProperty("c1_active",C1P1MotionRuntime.c2Receipt(id.profileId()).active());
        out.addProperty("c2_active",C2AgentRuntime.hasActiveActor(id.actorId()));
        out.addProperty("follow_active",C2FollowRuntime.hasActiveActor(id.actorId()));
        out.addProperty("g2_pending",CPlusE3G2Runtime.pending(server,id.actorId()));
        out.addProperty("g3_pending",CPlusE3G3Runtime.pending(server,id.actorId()));
        out.addProperty("recovery_pending",CPlusTransportRecovery.busy(server,id.actorId()));
        var row=C2ActionJournal.get(server).find(id.actorId());
        if(row!=null){var j=new JsonObject();j.addProperty("goal_id",row.goal()==null?null:row.goal().toString());
            j.addProperty("action_id",row.action()==null?null:row.action().toString());j.addProperty("state",row.state());out.add("c2_journal",j);}
        out.addProperty("inventory_nbt",avatar.getInventory().save(new net.minecraft.nbt.ListTag()).toString());
        out.addProperty("equipment",C1P1InventoryRuntime.equipment(server,name));
        return out;
    }
    private static int action(CommandSourceStack src,String name,String op){
        Session s=session(src);if(s==null)return 0;
        try {
            JsonObject before=live(src.getServer(),name);
            snapshot(src,s,before,"before-"+op);
            if(op.equals("snapshot"))return 1;
            var avatar=C1P0Runtime.activeAvatar(src.getServer(),name);
            var target=CPlusTest5Runtime.chooseFlatTarget(avatar);
            if(target==null)return say(src,"HOLD: no safe loaded NPC-derived 5-block target");
            // Production G2 repeats all current ownership/navigation guards.
            if(!op.equals("move")) {
                JsonObject scope=new JsonObject();for(String k:new String[]{"world_id","actor_id","session_id"})scope.add(k,before.get(k));
                scope.addProperty("requester_id",s.operator.toString());
                CPlusM03Fault.arm(CPlusOutbox.directory(src.getServer()),"reserve",scope,op.equals("loss-reserve"));
            }
            int submitted=CPlusE3G2Runtime.move(src,name,target.getX(),target.getY(),target.getZ());
            if(submitted==0)CPlusM03Fault.clear(CPlusOutbox.directory(src.getServer()));
            return submitted;
        }catch(Exception e){return say(src,"HOLD: "+CPlusTransport.diagnosticCode(e)+"; preserve evidence");}
    }
    /** All selection is on the server thread; no spawning, teleporting or mutation. */
    private static List<String> activeNames(CommandSourceStack src,int count){
        if(!Set.of(1,3,5,10).contains(count))throw new IllegalArgumentException("count must be 1,3,5,10");
        List<String> names=C1IdentityData.get(src.getServer()).all().stream()
            .filter(id->id.active() && C1P0Runtime.activeAvatar(src.getServer(),id.character())!=null)
            .map(C1IdentityData.Entry::character).sorted().toList();
        if(names.size()!=count)throw new IllegalArgumentException(
            "live NPC count="+names.size()+"; expected EXACTLY "+count+"; no NPCs spawned");
        return names;
    }
    private static int measureAuto(CommandSourceStack src,int count,int seconds){
        if(session(src)==null)return 0;
        try{return measure(src,count,seconds,String.join(" ",activeNames(src,count)));}
        catch(RuntimeException e){return say(src,"HOLD: "+e.getMessage());}
    }
    private static int moveAll(CommandSourceStack src,int count){
        if(session(src)==null)return 0;
        try {
            List<String> names=activeNames(src,count);
            // Preflight EVERY actor before submitting ANY movement. The existing
            // C2/G2 guards still decide acceptance; rejection is never PASS.
            Map<String,net.minecraft.core.BlockPos> targets=new LinkedHashMap<>();
            for(String name:names){
                JsonObject local=live(src.getServer(),name);
                if(local.get("c1_active").getAsBoolean() || local.get("c2_active").getAsBoolean()
                        || local.get("follow_active").getAsBoolean() || local.get("g2_pending").getAsBoolean()
                        || local.get("g3_pending").getAsBoolean() || local.get("recovery_pending").getAsBoolean())
                    throw new IllegalStateException("actor busy: "+name);
                net.minecraft.core.BlockPos target=CPlusTest5Runtime.chooseFlatTarget(C1P0Runtime.activeAvatar(src.getServer(),name));
                if(target==null)throw new IllegalStateException("no safe loaded corridor: "+name);
                targets.put(name,target);
            }
            int submitted=0;
            for(var entry:targets.entrySet()){
                var p=entry.getValue();
                submitted+=CPlusE3G2Runtime.move(src,entry.getKey(),p.getX(),p.getY(),p.getZ());
            }
            return say(src,"submitted "+submitted+"/"+count+" unique guarded moves; inspect outcomes; no automatic retry");
        }catch(Exception e){return say(src,"HOLD: "+CPlusTransport.diagnosticCode(e)+"; no workload submitted");}
    }
    private static int followSelf(CommandSourceStack src,String name,boolean start){
        if(session(src)==null)return 0;
        String response=start?C2FollowRuntime.follow(src.getServer(),name,src.getPlayer().getGameProfile().getName())
                :C2FollowRuntime.unfollow(src.getServer(),name);
        return say(src,response+"; observe actual Follow behavior; NOT an automatic PASS");
    }
    /** One scoped one-shot response-loss operation using authoritative current task ID.
     * Never invents task IDs, terminal witnesses, or durable success. */
    private static int terminalAuto(CommandSourceStack src,String name,String route){
        Session s=session(src);if(s==null)return 0;
        if(!Set.of("finalize","cancel-ack").contains(route))
            return say(src,"HOLD: route is finalize or cancel-ack");
        final JsonObject before,lookup;
        final URI base;final String token;
        final MinecraftServer server=src.getServer();
        try {
            before=live(server,name);
            UUID actor=UUID.fromString(before.get("actor_id").getAsString());
            if(CPlusE3G2Runtime.pending(server,actor) || CPlusE3G3Runtime.pending(server,actor)
                    || CPlusTransportRecovery.busy(server,actor)
                    || before.get("c1_active").getAsBoolean()
                    || before.get("c2_active").getAsBoolean()
                    || before.get("follow_active").getAsBoolean()
                    || !before.get("grounded").getAsBoolean())
                return say(src,"HOLD: actor/transport busy or not grounded");
            C2ActionJournal.Row journal=C2ActionJournal.get(server).find(actor);
            String wanted=route.equals("finalize")?"COMPLETED":"CANCELLED";
            if(journal==null||!wanted.equals(journal.state())||journal.goal()==null)
                return say(src,"HOLD: local C2 terminal witness is not "+wanted);
            base=CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get());
            token=CPlusLocalAuth.readToken();
            lookup=new JsonObject();lookup.add("world_id",before.get("world_id"));lookup.add("actor_id",before.get("actor_id"));
            snapshot(src,s,before,"before-terminal-auto-"+route);
        }catch(Exception e){return say(src,"HOLD: "+CPlusTransport.diagnosticCode(e));}
        try{s.io.submit(()->{
            final JsonObject backend;
            try{backend=JsonParser.parseString(CPlusTransport.post(base.resolve("/v1/cplus/e3/status"),token,lookup.toString(),5,24000)).getAsJsonObject();}
            catch(Exception e){server.execute(()->say(src,"HOLD: backend lookup "+CPlusTransport.diagnosticCode(e)));return;}
            server.execute(()->{
                try {
                    if(SESSIONS.get(server)!=s || src.getPlayer()==null
                            || server.getPlayerList().getPlayer(s.operator)==null)
                        throw new IllegalStateException("operator session changed");
                    JsonObject fresh=live(server,name);
                    for(String key:new String[]{"world_id","actor_id","session_id"})
                        if(!before.get(key).equals(fresh.get(key)))
                            throw new IllegalStateException("NPC identity changed: "+key);
                    if(!lookup.get("world_id").equals(backend.get("world_id"))
                            || !lookup.get("actor_id").equals(backend.get("actor_id"))
                            || !s.operator.toString().equals(backend.get("requester_id").getAsString()))
                        throw new IllegalStateException("backend owner mismatch");
                    String state=backend.get("state").getAsString();
                    if(!(state.equals(route.equals("finalize")?"C2_ACCEPTED":"CANCEL_PENDING")))
                        throw new IllegalStateException("backend state "+state+" cannot arm "+route);
                    String task=backend.get("task_id").getAsString();
                    UUID.fromString(task);
                    C2ActionJournal.Row j=C2ActionJournal.get(server).find(UUID.fromString(before.get("actor_id").getAsString()));
                    if(j==null || j.goal()==null || !j.goal().toString().equals(backend.get("goal_id").getAsString())
                            || !j.state().equals(route.equals("finalize")?"COMPLETED":"CANCELLED"))
                        throw new IllegalStateException("C2 goal/terminal changed before arming");
                    if(fresh.get("c1_active").getAsBoolean()||fresh.get("c2_active").getAsBoolean()
                            || fresh.get("follow_active").getAsBoolean()
                            || fresh.get("g2_pending").getAsBoolean()
                            || fresh.get("g3_pending").getAsBoolean()
                            || fresh.get("recovery_pending").getAsBoolean())
                        throw new IllegalStateException("another operation became active");
                    JsonObject match=new JsonObject();
                    for(String key:new String[]{"world_id","actor_id","session_id"})match.add(key,before.get(key));
                    match.addProperty("task_id",task);match.addProperty("requester_id",s.operator.toString());
                    Path dir=CPlusOutbox.directory(server);
                    CPlusM03Fault.arm(dir,route,match,true);
                    int result=route.equals("finalize")?CPlusE3G3Runtime.finalizeMove(src,name)
                        :CPlusE3G3Runtime.reconcileCancel(src,name);
                    if(result==0){CPlusM03Fault.clear(dir);say(src,"HOLD: terminal request rejected; fault cleared");}
                    else say(src,"one-shot "+route+" response loss ARMED for task="+task+
                        "; wait for G3 HOLD and inspect fault evidence; no blind retry");
                }catch(Exception e){say(src,"HOLD: terminal auto "+CPlusTransport.diagnosticCode(e)+
                    "; no terminal request initiated");}
            });
        });}catch(RejectedExecutionException e){return say(src,"HOLD: evidence worker full");}
        return say(src,"terminal task identity lookup queued (no mutation; awaiting exact C2/backend match)");
    }
    private static int terminalFault(CommandSourceStack src,String name,String route,String task){
        Session s=session(src);if(s==null)return 0;
        if(!Set.of("finalize","cancel-ack").contains(route))return say(src,"HOLD: route must be finalize or cancel-ack");
        try {
            UUID.fromString(task);JsonObject scope=live(src.getServer(),name);JsonObject match=new JsonObject();
            for(String k:new String[]{"world_id","actor_id","session_id"})match.add(k,scope.get(k));
            match.addProperty("task_id",task);match.addProperty("requester_id",s.operator.toString());
            CPlusM03Fault.arm(CPlusOutbox.directory(src.getServer()),route,match,true);
            return say(src,"armed for 60s; invoke existing taskmovefinalize/taskmovereconcile for this exact task");
        }catch(Exception e){return say(src,"HOLD: invalid or already armed fault");}
    }
    private static void snapshot(CommandSourceStack src,Session s,JsonObject out,String label){
        Path dir=CPlusOutbox.directory(src.getServer());
        final URI base=CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get());
        final String token;
        try{token=CPlusLocalAuth.readToken();}catch(Exception e){say(src,"HOLD: credential unavailable");return;}
        out.addProperty("run_id",s.run.toString());out.addProperty("label",label);
        if(label.startsWith("performance") && s.measurement!=null)out.addProperty("measurement_id",s.measurement.toString());
        out.addProperty("captured_utc",java.time.Instant.now().toString());
        try{s.io.submit(()->{
            try {
                JsonObject query=new JsonObject();query.add("world_id",out.get("world_id"));query.add("actor_id",out.get("actor_id"));
                long start=System.nanoTime();
                try {var back=JsonParser.parseString(CPlusTransport.post(base.resolve("/v1/cplus/e3/status"),token,query.toString(),5,24000)).getAsJsonObject();
                    for(String k:new String[]{"world_id","actor_id"})if(!query.get(k).equals(back.get(k)))throw new IllegalStateException("identity");
                    out.add("backend",back);
                }catch(Exception e){out.addProperty("backend_error",CPlusTransport.diagnosticCode(e));}
                out.addProperty("backend_request_ms",(System.nanoTime()-start)/1e6);
                JsonArray records=new JsonArray();int count=0;long bytes=0;
                if(Files.isDirectory(dir))try(var paths=Files.list(dir)){
                    for(Path p:paths.limit(513).toList()){
                        if(++count>512)throw new IllegalStateException("outbox scan limit");
                        if(!p.getFileName().toString().endsWith(".json"))continue;
                        JsonObject r=CPlusOutbox.read(p),b=r.getAsJsonObject("body");
                        if(out.get("actor_id").equals(b.get("actor_id"))&&out.get("world_id").equals(b.get("world_id"))){
                            JsonObject item=new JsonObject();item.addProperty("file",p.getFileName().toString());
                            item.addProperty("sha256",hash(Files.readAllBytes(p)));item.add("record",r);records.add(item);bytes+=Files.size(p);
                        }
                    }
                }
                out.add("outbox",records);out.addProperty("outbox_retained_count",records.size());out.addProperty("outbox_retained_bytes",bytes);
                Path file=dir.getParent().resolve("m03-evidence").resolve(s.run+"-"+UUID.randomUUID()+".json");
                CPlusOutbox.write(file,out);
                src.getServer().execute(()->say(src,"snapshot saved "+file.getFileName()+" (observations, not PASS)"));
            }catch(Exception e){src.getServer().execute(()->say(src,"HOLD: evidence capture failed; "+CPlusTransport.diagnosticCode(e)));}
        });}catch(RejectedExecutionException e){say(src,"HOLD: evidence queue full");}
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static int measure(CommandSourceStack src,int count,int seconds,String names){
        Session s=session(src);if(s==null)return 0;
        String[] list=names.trim().split("\\s+");
        if(s.remaining>0||!Set.of(1,3,5,10).contains(count)||list.length!=count||new HashSet<>(Arrays.asList(list)).size()!=count)
            return say(src,"HOLD: counts 1/3/5/10; unique explicit NPC names; one measurement at a time");
        s.measurement=UUID.randomUUID();s.observed.clear();s.transitions=new JsonArray();
        try {for(String name:list)snapshot(src,s,live(src.getServer(),name),"performance-start");}
        catch(Exception e){return say(src,"HOLD: an NPC is unavailable");}
        s.names=list;s.expected=count;s.remaining=seconds*20;s.started=System.nanoTime();s.ticks.clear();s.invalid=false;
        return say(src,"measurement started; tick-event work time (not scheduler interval); no automatic tasks or NPC spawning");
    }
    static void tick(TickEvent.ServerTickEvent e){
        Session s=SESSIONS.get(e.getServer());if(s==null||s.remaining==0)return;
        if(e.phase==TickEvent.Phase.START){s.tickStart=System.nanoTime();return;}
        s.ticks.add((System.nanoTime()-s.tickStart)/1e6);
        var operator=e.getServer().getPlayerList().getPlayer(s.operator);
        if(operator==null||!operator.hasPermissions(2))s.invalid=true;
        long active=C1IdentityData.get(e.getServer()).all().stream().filter(id->id.active()&&C1P0Runtime.activeAvatar(e.getServer(),id.character())!=null).count();
        if(active!=s.expected)s.invalid=true;
        for(String name:s.names)if(C1P0Runtime.activeAvatar(e.getServer(),name)==null)s.invalid=true;
        for(String name:s.names){
            var id=C1IdentityData.get(e.getServer()).find(name);if(id==null){s.invalid=true;continue;}
            var row=C2ActionJournal.get(e.getServer()).find(id.actorId());
            String current=row==null?"NONE":row.goal()+"/"+row.state();
            boolean running=C2AgentRuntime.hasActiveActor(id.actorId());current+="/"+running;
            String previous=s.observed.put(name,current);
            if(!current.equals(previous)&&s.transitions.size()<2000){
                JsonObject t=new JsonObject();t.addProperty("name",name);t.addProperty("actor_id",id.actorId().toString());
                t.addProperty("seconds",(System.nanoTime()-s.started)/1e9);t.addProperty("initial",previous==null);
                t.addProperty("active",running);t.addProperty("state",row==null?"NONE":row.state());
                t.addProperty("goal_id",row==null||row.goal()==null?null:row.goal().toString());s.transitions.add(t);
            }else if(s.transitions.size()>=2000)s.invalid=true;
        }
        if(--s.remaining>0)return;
        var sorted=s.ticks.stream().sorted().toList();JsonObject result=new JsonObject();
        result.addProperty("kind","performance");result.addProperty("run_id",s.run.toString());result.addProperty("npc_count",s.expected);
        result.addProperty("measurement_id",s.measurement.toString());result.add("navigation_transitions",s.transitions);
        result.addProperty("status",s.invalid?"HOLD":"MEASURED_NOT_ACCEPTED");result.addProperty("samples",sorted.size());
        result.addProperty("duration_seconds",(System.nanoTime()-s.started)/1e9);
        result.addProperty("tick_event_mean_ms",sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        result.addProperty("tick_event_p95_ms",sorted.get((int)Math.ceil(sorted.size()*.95)-1));result.addProperty("tick_event_max_ms",sorted.get(sorted.size()-1));
        result.addProperty("heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        result.addProperty("server_average_tick_ms",e.getServer().getAverageTickTime());
        result.addProperty("available_processors",Runtime.getRuntime().availableProcessors());result.addProperty("java",System.getProperty("java.version"));
        result.addProperty("os",System.getProperty("os.name"));result.addProperty("cpu_load", "UNAVAILABLE; heap is not process RSS");
        Path file=CPlusOutbox.directory(e.getServer()).getParent().resolve("m03-evidence").resolve("performance-"+UUID.randomUUID()+".json");
        try{s.io.submit(()->{try{CPlusOutbox.write(file,result);}catch(Exception ex){com.mojang.logging.LogUtils.getLogger().error("M03 performance evidence write failed");}});}catch(RejectedExecutionException ex){s.invalid=true;}
        if(operator!=null){var src=operator.createCommandSourceStack();
            for(String name:s.names)try{snapshot(src,s,live(e.getServer(),name),"performance-end");}catch(Exception ignored){say(src,"HOLD: end snapshot unavailable");}
            say(src,"measurement ended; inspect evidence; gameplay acceptance still requires task outcomes/baseline");}
    }
    static void stopping(ServerStoppingEvent e){Session s=SESSIONS.remove(e.getServer());
        CPlusM03Fault.clear(CPlusOutbox.directory(e.getServer()));
        if(s!=null){s.io.shutdown();if(s.remaining>0)com.mojang.logging.LogUtils.getLogger().warn("M03 HOLD: measurement interrupted by server stop");}}
}
