package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** HTTP calls never block the Minecraft server thread. */
public final class ChatBridge {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public record CharacterProfile(String id, String name, String skin, String model) {}
    public record AgentReply(String reply, String action) {}

    private ChatBridge() {}

    private static URI endpoint(String path) {
        String base = NPCortexConfig.BACKEND_URL.get().replaceAll("/+$", "");
        return URI.create(base + path);
    }

    public static CompletableFuture<String> health() {
        HttpRequest request = HttpRequest.newBuilder(endpoint("/health"))
                .timeout(Duration.ofSeconds(6)).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode());
                    JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!payload.has("status") || !"ok".equals(payload.get("status").getAsString())) {
                        throw new IllegalStateException("Unexpected health status");
                    }
                    String model = payload.has("model") ? payload.get("model").getAsString() : "unknown";
                    return "Backend online | model: " + model + " (Ollama not verified by /health)";
                });
    }

    public static CompletableFuture<CharacterProfile> character(String characterId) {
        if (!characterId.matches("[a-z][a-z0-9_-]{0,39}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid character ID"));
        }
        String encoded = URLEncoder.encode(characterId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(endpoint("/v1/characters/" + encoded))
                .timeout(Duration.ofSeconds(8)).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode());
                    JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonObject appearance = payload.getAsJsonObject("appearance");
                    String name = payload.get("name").getAsString();
                    String skin = appearance.get("skin").getAsString();
                    String model = appearance.get("model").getAsString();
                    if (!characterId.equals(payload.get("id").getAsString())
                            || name.isBlank() || name.length() > 80
                            || !skin.matches("[a-z][a-z0-9_-]{0,39}")
                            || !(model.equals("wide") || model.equals("slim"))) {
                        throw new IllegalArgumentException("Malformed backend character profile");
                    }
                    return new CharacterProfile(characterId, name, skin, model);
                });
    }

    public static CompletableFuture<List<String>> characters() {
        HttpRequest request = HttpRequest.newBuilder(endpoint("/v1/characters"))
                .timeout(Duration.ofSeconds(8)).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode());
                    JsonArray array = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("characters");
                    List<String> items = new ArrayList<>();
                    for (int i = 0; i < array.size() && i < 60; ++i) {
                        JsonObject character = array.get(i).getAsJsonObject();
                        items.add(character.get("id").getAsString() + " (" + character.get("name").getAsString() + ")");
                    }
                    return items;
                });
    }

    public static CompletableFuture<String> ask(String characterId, String playerId, String message) {
        return ask(characterId, playerId, message, null);
    }

    public static CompletableFuture<String> ask(String characterId, String playerId, String message, String sessionId) {
        JsonObject body = new JsonObject();
        body.addProperty("character_id", characterId);
        body.addProperty("player_id", playerId);
        body.addProperty("message", message);
        if (sessionId != null) body.addProperty("session_id", sessionId);
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

    /** World snapshots are server-generated. Structured actions are never executed here. */
    public static CompletableFuture<AgentReply> agent(String characterId, String playerId,
            String message, String sessionId, JsonObject world) {
        JsonObject body = new JsonObject();
        body.addProperty("character_id", characterId);
        body.addProperty("player_id", playerId);
        body.addProperty("message", message);
        body.addProperty("session_id", sessionId);
        body.add("world", world);
        HttpRequest request = HttpRequest.newBuilder(endpoint("/v1/agent"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(95))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode());
                    JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject();
                    String reply = data.get("reply").getAsString();
                    String action = data.get("action").getAsString();
                    if (reply.isBlank() || reply.length() > 1600 ||
                            !(action.equals("none") || action.equals("follow")
                                || action.equals("stay") || action.equals("look_at_player"))) {
                        throw new IllegalArgumentException("Invalid agent response");
                    }
                    return new AgentReply(reply, action);
                });
    }

    private static void ensureSuccess(int statusCode) {
        if (statusCode != 200) throw new IllegalStateException("NPCortex backend HTTP " + statusCode);
    }
}
