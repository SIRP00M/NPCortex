package io.github.sirp00m.npcortex;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.sirp00m.npcortex.cplus.CPlusF1AsyncDiagnostics;
import io.github.sirp00m.npcortex.cplus.CPlusLocalAuth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

/** Operator-only, opt-in C-F1 D+ diagnostics; NOT a world-action or C2 scheduler integration. */
final class CPlusF1DiagnosticRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<MinecraftServer,CPlusF1AsyncDiagnostics> SERVICES = new IdentityHashMap<>();
    private CPlusF1DiagnosticRuntime() {}

    static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> debug() {
        var cplus = Commands.literal("cplus");
        cplus.then(Commands.literal("inspect")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> inspect(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
        cplus.then(Commands.literal("syncstatus")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> syncStatus(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
        cplus.then(Commands.literal("sync")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> sync(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
        cplus.then(Commands.literal("audit")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> audit(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
        cplus.then(Commands.literal("world")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> world(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
        cplus.then(Commands.literal("epreview")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> CPlusEPreviewRuntime.preview(ctx.getSource(), StringArgumentType.getString(ctx,"name")))));
        // E1 operator-only; real requester UUID from the server player.
        cplus.then(Commands.literal("tasksubmit")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> CPlusECoordinatorRuntime.submit(ctx.getSource(),StringArgumentType.getString(ctx,"name")))));
        cplus.then(Commands.literal("taskqueue")
                .then(Commands.argument("name",StringArgumentType.word())
                        .executes(ctx -> CPlusECoordinatorRuntime.queue(ctx.getSource(),StringArgumentType.getString(ctx,"name")))));
        cplus.then(Commands.literal("taskstatus")
                .then(Commands.argument("name",StringArgumentType.word())
                        .then(Commands.argument("task_id",StringArgumentType.word())
                                .executes(ctx -> CPlusECoordinatorRuntime.status(ctx.getSource(),
                                        StringArgumentType.getString(ctx,"name"),StringArgumentType.getString(ctx,"task_id"))))));
        cplus.then(Commands.literal("taskcancel")
                .then(Commands.argument("name",StringArgumentType.word())
                        .then(Commands.argument("task_id",StringArgumentType.word())
                                .executes(ctx -> CPlusECoordinatorRuntime.cancel(ctx.getSource(),
                                        StringArgumentType.getString(ctx,"name"),StringArgumentType.getString(ctx,"task_id"))))));
        // E2: operator-triggered two-stage read-only dispatch; no ticking job,
        // C2 movement, inventory operation or world mutation.
        cplus.then(Commands.literal("taskdispatch")
                .then(Commands.argument("name",StringArgumentType.word())
                        .executes(ctx -> CPlusECoordinatorRuntime.dispatch(ctx.getSource(),
                                StringArgumentType.getString(ctx,"name")))));
        cplus.then(Commands.literal("taskverify")
                .then(Commands.argument("name",StringArgumentType.word())
                        .executes(ctx -> CPlusECoordinatorRuntime.verify(ctx.getSource(),
                                StringArgumentType.getString(ctx,"name")))));
        // E3-D G1: operator-only preview. No C2 navigation is invoked.
        cplus.then(Commands.literal("taskmovepreview")
                .then(Commands.argument("name",StringArgumentType.word())
                        .then(Commands.argument("x",IntegerArgumentType.integer())
                                .then(Commands.argument("y",IntegerArgumentType.integer())
                                        .then(Commands.argument("z",IntegerArgumentType.integer())
                                                .executes(ctx -> CPlusECoordinatorRuntime.movePreview(ctx.getSource(),
                                                        StringArgumentType.getString(ctx,"name"),
                                                        IntegerArgumentType.getInteger(ctx,"x"),
                                                        IntegerArgumentType.getInteger(ctx,"y"),
                                                        IntegerArgumentType.getInteger(ctx,"z"))))))));
        cplus.then(Commands.literal("probe")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> probe(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
        return Commands.literal("debug").requires(source -> source.hasPermission(2)).then(cplus);
    }

    private static CPlusF1AsyncDiagnostics service(MinecraftServer server) {
        // Always entered on Minecraft server thread; no IO in constructor.
        CPlusF1AsyncDiagnostics existing = SERVICES.get(server);
        if (existing != null) return existing;
        var directory = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("npcortex");
        var diag = new CPlusF1AsyncDiagnostics(directory.resolve("cplus_f1_diagnostics.bin"),
                C1IdentityData.get(server).worldId());
        SERVICES.put(server, diag);
        return diag;
    }

    private static int inspect(CommandSourceStack src, String name) {
        MinecraftServer server = src.getServer();
        var actor = C1IdentityData.get(server).find(name);
        if (actor == null) { respond(src, "Unknown NPC: " + name); return 0; }
        CPlusF1AsyncDiagnostics svc = service(server);
        // Completion callback never performs server/game operations off-thread.
        svc.inspect().whenComplete((snap, error) -> server.execute(() -> {
            if (error != null) respond(src, "C-F1 D+ journal held: " + safe(error));
            else respond(src, name + " C-F1 D+ DIAGNOSTIC ONLY " + format(snap));
        }));
        respond(src, "C-F1 journal inspection queued (no world action)");
        return 1;
    }

    private static int probe(CommandSourceStack src, String name) {
        MinecraftServer server = src.getServer();
        var actor = C1IdentityData.get(server).find(name);
        if (actor == null) { respond(src, "Unknown NPC: " + name); return 0; }
        CPlusF1AsyncDiagnostics svc = service(server);
        svc.probe(actor.actorId()).whenComplete((ack, error) -> server.execute(() -> {
            if (error != null) respond(src, "C-F1 D+ probe held (NO retry): " + safe(error));
            else respond(src, "C-F1 D+ SYNTHETIC ONLY durable CANCELLED action=" + ack.actionId()
                    + " epoch=" + ack.epoch() + " sequence=" + ack.durableSequence()
                    + "; no world effect; " + format(svc.status()));
        }));
        respond(src, "C-F1 synthetic intent/cancel queued to bounded IO worker; no gameplay mutation");
        return 1;
    }

    private static int syncStatus(CommandSourceStack src, String name) {
        var server=src.getServer();
        if (C1IdentityData.get(server).find(name)==null) {respond(src,"Unknown NPC: "+name);return 0;}
        // Read authoritative local durable cursor on IO worker, never on server tick.
        service(server).deliveryStatus().whenComplete((state,error)->server.execute(()->{
            if(error!=null) respond(src,"C-F1 durable syncstatus HOLD: "+safe(error));
            else respond(src,"C-F1 LOCAL DURABLE WORLD-WIDE journal state="+state.state()
                    +" checkpoint="+state.checkpoint()+" pending="+state.pending()
                    +" transferredThisInspection="+state.transferred()+" reason="+state.reason());
        }));
        respond(src,"C-F1 durable delivery status inspection queued (NO world action)");
        return 1;
    }
    private static int audit(CommandSourceStack src, String name) {
        var server=src.getServer();
        if(C1IdentityData.get(server).find(name)==null) {respond(src,"Unknown NPC: "+name);return 0;}
        String token;
        try {token=CPlusLocalAuth.readToken();}
        catch(IOException | IllegalArgumentException unavailable) {
            respond(src,"C-F1 audit HOLD: local credential unavailable; start Backend first");
            return 0;
        }
        service(server).reconcile(NPCortexConfig.BACKEND_URL.get(),token)
                .whenComplete((result,error)->server.execute(()->{
                    if(error!=null) respond(src,"C-F1 read-only recovery audit HOLD: "+safe(error));
                    else respond(src,"C-F1 READ ONLY world-wide audit="+result.state()
                            +" journal="+result.localSequence()+" cursor="+result.cursorSequence()
                            +" backend="+result.backendSequence()+" reason="+result.reason());
                }));
        respond(src,"C-F1 read-only backend recovery audit queued (NO gameplay mutation)");
        return 1;
    }

    private static int sync(CommandSourceStack src, String name) {
        var server=src.getServer();
        if (C1IdentityData.get(server).find(name)==null) {respond(src,"Unknown NPC: "+name);return 0;}
        String token;
        try { token=CPlusLocalAuth.readToken(); }
        catch (IOException | IllegalArgumentException error) {
            respond(src,"C-F1 sync held: local credential unavailable. Start NPCortex Backend, then retry (no world action)");
            LOG.warn("NPCortex C-F1 local bridge credential unavailable: {}",error.getClass().getSimpleName());
            return 0;
        }
        String base=NPCortexConfig.BACKEND_URL.get();
        var svc=service(server);
        svc.sync(base,token).whenComplete((result,error)->server.execute(()->{
            if(error!=null) respond(src,"C-F1 delivery HOLD; no automatic replay: "+safe(error));
            else respond(src,"C-F1 diagnostic delivery "+result.state()+" ACKed="+result.checkpoint()
                    +" transferred="+result.transferred()+" pending="+result.pending());
        }));
        respond(src,"C-F1 bounded WORLD-WIDE diagnostic sync queued (up to 8 events; NO world action)");
        return 1;
    }

    private static int world(CommandSourceStack src, String name) {
        // Reads live C1 avatar/server world synchronously on command's server thread.
        // Do not forward this as trusted input to the legacy unauthenticated C0 HTTP API.
        try {
            var snapshot=CPlusF2WorldSampler.capture(src.getServer(),name);
            var coverage=snapshot.getAsJsonObject("coverage");
            var permission=snapshot.getAsJsonObject("permission");
            var blocks=snapshot.getAsJsonArray("blocks");
            String centerBlock="UNKNOWN";
            for (var element:blocks) {
                var b=element.getAsJsonObject();
                if (b.getAsJsonArray("position").equals(snapshot.getAsJsonArray("center"))) {
                    centerBlock=b.has("block_id")?b.get("block_id").getAsString():b.get("status").getAsString();
                    break;
                }
            }
            respond(src,"C-F2 SERVER READ ONLY v2 tick="+snapshot.get("capture_tick").getAsLong()
                    +" observed="+coverage.get("recorded").getAsInt()+" unknown="+coverage.get("unknown").getAsInt()
                    +" center="+centerBlock+" break="+permission.get("decision").getAsString()
                    +" ("+permission.get("reason").getAsString()+")"
                    +" observation="+snapshot.get("observation_id").getAsString());
            LOG.info("NPCortex C-F2 operator read-only snapshot: {}",snapshot);
            return 1;
        } catch (RuntimeException error) {
            respond(src,"C-F2 world observation HOLD: "+safe(error));
            return 0;
        }
    }

    private static String format(CPlusF1AsyncDiagnostics.Snapshot s) {
        return "state=" + s.state() + " actions=" + s.actions() + " unresolved=" + s.unresolved()
                + " acks=" + s.durableAcks() + " rejected=" + s.rejected()
                + " last=" + s.lastProbe() + (s.error().isBlank()?"":" error="+s.error());
    }
    private static String safe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause()!=null
                ? error.getCause():error;
        return cause.getClass().getSimpleName() + " (inspect server logs; never auto-retry)";
    }
    private static void respond(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("NPCortex: " + message), false);
    }

    static void onServerStopping(ServerStoppingEvent event) {
        CPlusF1AsyncDiagnostics svc=SERVICES.remove(event.getServer());
        if (svc == null) return;
        try { svc.close(); }
        catch (IOException e) { LOG.error("NPCortex C-F1 diagnostic journal failed to close safely; check before retry",e); }
    }
}
