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
    public static final int MAX_PLAN_STEPS = 8;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public record CharacterProfile(String id, String name, String skin, String model) {}
    public record ActionStep(String action, String targetItem) {}
    public record AgentReply(String reply, String action, String targetItem, List<ActionStep> steps) {}

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

    /** Dialogue-only reaction to an ALREADY applied game action. Does not issue actions. */
    public static CompletableFuture<String> actionReply(String characterId, String playerId,
            String message, String sessionId, String action, String result) {
        JsonObject body = new JsonObject();
        body.addProperty("character_id", characterId);
        body.addProperty("player_id", playerId);
        body.addProperty("message", message);
        body.addProperty("session_id", sessionId);
        body.addProperty("action", action);
        body.addProperty("result", result);
        HttpRequest request = HttpRequest.newBuilder(endpoint("/v1/action-reply"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(95))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode());
                    JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
                    String reply = payload.get("reply").getAsString().strip();
                    if (reply.isEmpty() || reply.length() > 400)
                        throw new IllegalArgumentException("Invalid action dialogue reply");
                    return reply;
                });
    }

    /** Optional one-sentence autonomous reaction; cannot return executable actions. */
    public static CompletableFuture<String> autonomyReply(String characterId, String playerId,
            String sessionId, String threatType, String result) {
        JsonObject body = new JsonObject();
        body.addProperty("character_id", characterId);
        body.addProperty("player_id", playerId);
        body.addProperty("session_id", sessionId);
        body.addProperty("message", "A hostile mob was noticed near the NPC.");
        body.addProperty("event", "hostile_nearby");
        body.addProperty("threat_type", threatType);
        body.addProperty("result", result);
        HttpRequest request = HttpRequest.newBuilder(endpoint("/v1/autonomy-reply"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode());
                    String reply = JsonParser.parseString(response.body()).getAsJsonObject()
                            .get("reply").getAsString().strip();
                    if (reply.isEmpty() || reply.length() > 220)
                        throw new IllegalArgumentException("Invalid autonomous dialogue");
                    return reply;
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
                            !(action.equals("none") || ActionCatalog.allowed(action))) {
                        throw new IllegalArgumentException("Invalid agent response");
                    }
                    String targetItem = data.has("target_item") && !data.get("target_item").isJsonNull()
                            ? data.get("target_item").getAsString() : "";
                    if (!targetItem.isEmpty() && !targetItem.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"))
                        throw new IllegalArgumentException("Invalid target item");
                    // v0.5.3: a bounded list of steps from the backend, never executable text.
                    // Older single-action backends are still supported.
                    List<ActionStep> steps = new ArrayList<>();
                    if (data.has("steps") && data.get("steps").isJsonArray()) {
                        JsonArray plan = data.getAsJsonArray("steps");
                        if (plan.size() > MAX_PLAN_STEPS) throw new IllegalArgumentException("Plan too long");
                        for (int i = 0; i < plan.size(); ++i) {
                            JsonObject rawStep = plan.get(i).getAsJsonObject();
                            String stepAction = rawStep.get("action").getAsString();
                            String stepTarget = rawStep.get("target_item").getAsString();
                            if (!ActionCatalog.validTarget(stepAction, stepTarget))
                                throw new IllegalArgumentException("Invalid AI plan step");
                            steps.add(new ActionStep(stepAction, stepTarget));
                        }
                        // Validated steps are authoritative, not duplicated top-level fields.
                        // This tolerates Qwen returning an out-of-sync primary action.
                    } else if (!action.equals("none")) {
                        steps.add(new ActionStep(action, targetItem));
                    }
                    if (!validPlan(steps)) throw new IllegalArgumentException("Disallowed AI action sequence");
                    if (!steps.isEmpty()) {
                        action = steps.get(0).action();
                        targetItem = steps.get(0).targetItem();
                    } else {
                        action = "none";
                        targetItem = "";
                    }
                    return new AgentReply(reply, action, targetItem, List.copyOf(steps));
                });
    }

    /** Generic bounded validation delegated to the action registry. */
    private static boolean validPlan(List<ActionStep> steps) {
        if (steps.size() > MAX_PLAN_STEPS) return false;
        for (ActionStep step : steps) {
            if (!ActionCatalog.validTarget(step.action(), step.targetItem())) return false;
            if (steps.size() > 1 && ActionCatalog.itemAction(step.action())
                    && step.targetItem().isEmpty()) return false;
        }
        return true;
    }

    /** Scoped persistent memory; only the server constructs owner/player identifiers. */
    public static CompletableFuture<JsonObject> memory(String playerId, String sessionId, String characterId,
            String operation, String note, int noteId) {
        JsonObject body = new JsonObject();
        body.addProperty("player_id", playerId);
        body.addProperty("session_id", sessionId);
        body.addProperty("character_id", characterId);
        HttpRequest.Builder builder;
        if ("list".equals(operation)) {
            String query = "?player_id=" + playerId + "&session_id=" + sessionId +
                    "&character_id=" + characterId;
            builder = HttpRequest.newBuilder(endpoint("/v1/memory" + query)).GET();
        } else {
            body.addProperty("operation", operation);
            body.addProperty("text", note);
            body.addProperty("note_id", noteId);
            builder = HttpRequest.newBuilder(endpoint("/v1/memory"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        return HTTP.sendAsync(builder.timeout(Duration.ofSeconds(8)).build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureSuccess(response.statusCode());
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                });
    }

    private static void ensureSuccess(int statusCode) {
        if (statusCode != 200) throw new IllegalStateException("NPCortex backend HTTP " + statusCode);
    }
}
