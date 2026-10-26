package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sirp00m.npcortex.cplus.CPlusF1Delivery;
import io.github.sirp00m.npcortex.cplus.CPlusLocalAuth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * E-P0 opt-in proof of multi-step planning across the real Forge->Backend boundary.
 * Server-thread C-F2 capture -> async authenticated HTTP -> durable read-only DAG.
 * No motion ownership, world mutation, inventory, or Saga execution is exposed here.
 */
final class CPlusEPreviewRuntime {
    private static final Map<MinecraftServer,ExecutorService> WORKERS = new IdentityHashMap<>();
    private CPlusEPreviewRuntime() {}

    private static ExecutorService worker(MinecraftServer server) {
        return WORKERS.computeIfAbsent(server, ignored -> new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
                job -> { Thread thread = new Thread(job, "npcortex-cplus-e-preview");
                         thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy()));
    }

    static int preview(CommandSourceStack source, String actorName) {
        MinecraftServer server = source.getServer();
        final JsonObject observation;
        final String token;
        final URI endpoint;
        try {
            // Captured on the server thread only. The worker sees an immutable JSON string.
            observation = CPlusF2WorldSampler.capture(server,actorName);
            token = CPlusLocalAuth.readToken();
            endpoint = CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get())
                    .resolve("/v1/cplus/e/preview");
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            respond(source,"E preview HOLD: " + error.getClass().getSimpleName()
                    + "; start Backend and ensure NPC is active");
            return 0;
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("request_id", UUID.randomUUID().toString());
        envelope.add("snapshot", observation);
        String body = envelope.toString();
        try {
            CompletableFuture.supplyAsync(() -> send(endpoint,token,body),worker(server))
                    .whenComplete((reply,error) -> server.execute(() -> {
                        if (error != null) {
                            respond(source,"E preview HOLD: backend unavailable; no automatic replay or world changes");
                            return;
                        }
                        try {
                            JsonObject json = JsonParser.parseString(reply).getAsJsonObject();
                            if (!json.has("world_effects") || json.get("world_effects").getAsBoolean()
                                    || !json.has("state") || !json.has("task_id"))
                                throw new IllegalArgumentException("unexpected response");
                            respond(source,"E READ-ONLY DAG task="+json.get("task_id").getAsString()
                                    +" state="+json.get("state").getAsString()
                                    +" steps="+json.getAsJsonArray("steps").size()
                                    +" cells="+json.get("observed").getAsInt()
                                    +" permission="+json.get("permission").getAsString()
                                    +"; NO world mutation");
                        } catch (RuntimeException bad) {
                            respond(source,"E preview HOLD: invalid Backend reply; NO world mutation");
                        }
                    }));
        } catch (RejectedExecutionException busy) {
            respond(source,"E preview HOLD: bounded HTTP queue is busy, retry manually");
            return 0;
        }
        respond(source,"E read-only DAG preview queued (3 steps, no world mutation)");
        return 1;
    }

    private static String send(URI uri,String token,String json) {
        return io.github.sirp00m.npcortex.cplus.CPlusTransport.exchange(uri,token,json,4,4096);
    }

    static void onServerStopping(ServerStoppingEvent event) {
        ExecutorService worker=WORKERS.remove(event.getServer());
        if (worker!=null) worker.shutdownNow();
    }
    private static void respond(CommandSourceStack source,String message) {
        source.sendSuccess(() -> Component.literal("NPCortex: " + message),false);
    }
}
