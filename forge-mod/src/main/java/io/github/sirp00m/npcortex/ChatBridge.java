package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Server-side network bridge only; Minecraft actions are not executed by the AI. */
public final class ChatBridge {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private ChatBridge() {}

    public static CompletableFuture<String> ask(String characterId, String playerId, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("character_id", characterId);
        body.addProperty("player_id", playerId);
        body.addProperty("message", message);

        String base = NPCortexConfig.BACKEND_URL.get().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(95))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Backend HTTP " + response.statusCode());
                    }
                    JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!payload.has("reply") || !payload.get("reply").isJsonPrimitive()) {
                        throw new IllegalStateException("Backend JSON reply missing");
                    }
                    return payload.get("reply").getAsString();
                });
    }
}
