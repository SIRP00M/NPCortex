package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** HTTP calls happen off the Minecraft server thread. */
public final class ChatBridge {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private ChatBridge() {}

    private static URI endpoint(String path) {
        String base = NPCortexConfig.BACKEND_URL.get().replaceAll("/+$", "");
        return URI.create(base + path);
    }

    public static CompletableFuture<String> health() {
        HttpRequest request = HttpRequest.newBuilder(endpoint("/health"))
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode());
                    JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!payload.has("status") || !"ok".equals(payload.get("status").getAsString())) {
                        throw new IllegalStateException("Backend returned an unexpected health status");
                    }
                    String model = payload.has("model") ? payload.get("model").getAsString() : "unknown";
                    return "Backend online | model: " + model + " (Ollama not verified by /health)";
                });
    }

    public static CompletableFuture<String> ask(String characterId, String playerId, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("character_id", characterId);
        body.addProperty("player_id", playerId);
        body.addProperty("message", message);
        HttpRequest request = HttpRequest.newBuilder(endpoint("/v1/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(95))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode());
                    JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!payload.has("reply") || !payload.get("reply").isJsonPrimitive()) {
                        throw new IllegalStateException("Backend reply missing");
                    }
                    return payload.get("reply").getAsString();
                });
    }

    private static void ensureSuccess(int statusCode) {
        if (statusCode != 200) {
            throw new IllegalStateException("NPCortex backend HTTP " + statusCode);
        }
    }
}
