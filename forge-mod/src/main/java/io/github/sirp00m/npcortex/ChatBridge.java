package io.github.sirp00m.npcortex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** HTTP calls happen off the Minecraft server thread. JSON errors stay readable in game. */
public final class ChatBridge {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private ChatBridge() {}

    private static URI endpoint(String path) {
        String raw = NPCortexConfig.BACKEND_URL.get().replaceAll("/+$", "");
        URI base = URI.create(raw);
        if ((!"http".equalsIgnoreCase(base.getScheme()) && !"https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || (base.getPath() != null && !base.getPath().isEmpty())
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Backend URL must be an HTTP(S) origin without path or credentials");
        }
        return URI.create(raw + path);
    }

    /** Sends the shared local secret when one exists; the backend only checks it when NPCORTEX_REQUIRE_TOKEN is on. Never logged. */
    private static HttpRequest.Builder withLocalToken(HttpRequest.Builder builder) {
        try {
            return builder.header("X-NPCortex-CPlus-Token", io.github.sirp00m.npcortex.cplus.CPlusLocalAuth.readToken());
        } catch (java.io.IOException | RuntimeException ignored) {
            return builder;
        }
    }

    private static JsonObject payload(HttpResponse<String> response) {
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("Backend returned a non-object JSON response");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Backend returned invalid JSON", exception);
        }
    }

    private static void ensureSuccess(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            return;
        }
        String reason = "Backend returned HTTP " + status;
        try {
            JsonObject body = payload(response);
            if (body.has("error") && body.get("error").isJsonPrimitive()) {
                String detail = body.get("error").getAsString();
                // Avoid unbounded or multiline text from a misconfigured HTTP server.
                detail = detail.replace('\n', ' ').replace('\r', ' ').trim();
                if (!detail.isEmpty()) {
                    reason += ": " + detail.substring(0, Math.min(detail.length(), 180));
                }
            }
        } catch (RuntimeException ignored) {
            // Preserve the HTTP status if the error body was not valid JSON.
        }
        throw new IllegalStateException(reason);
    }

    public static CompletableFuture<String> health() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint("/health"))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        ensureSuccess(response);
                        JsonObject body = payload(response);
                        if (!body.has("status") || !"ok".equals(body.get("status").getAsString())) {
                            throw new IllegalStateException("Backend returned unexpected health status");
                        }
                        String model = body.has("model") ? body.get("model").getAsString() : "unknown";
                        return "Backend online | model: " + model + " (Ollama not verified by /health)";
                    });
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public static CompletableFuture<String> ask(String characterId, String playerId, String message) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("character_id", characterId);
            body.addProperty("player_id", playerId);
            body.addProperty("message", message);
            HttpRequest request = withLocalToken(HttpRequest.newBuilder(endpoint("/v1/chat")))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(95))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        ensureSuccess(response);
                        JsonObject result = payload(response);
                        if (!result.has("reply") || !result.get("reply").isJsonPrimitive()) {
                            throw new IllegalStateException("Backend response is missing the reply");
                        }
                        return result.get("reply").getAsString();
                    });
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** C2 structured calls. Never block the Minecraft server thread. */
    static CompletableFuture<JsonObject> c2Post(String path, JsonObject body, int timeoutSeconds) {
        try {
            if (!path.matches("/v1/(worlds|actors|agent/goals(?:/[0-9a-f-]+/(?:observe|decide|cancel))?|agent/actions/[0-9a-f-]+/result)")) {
                throw new IllegalArgumentException("C2 endpoint is not allowlisted");
            }
            String encoded = body.toString();
            if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192) {
                throw new IllegalArgumentException("C2 request is too large");
            }
            HttpRequest request = withLocalToken(HttpRequest.newBuilder(endpoint(path)))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(encoded))
                    .build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 200 && response.statusCode() != 201) {
                            ensureSuccess(response);
                        }
                        return payload(response);
                    });
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public static String describeFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException) {
            return "Backend request timed out. Check Ollama and retry.";
        }
        if (cause instanceof ConnectException) {
            return "Cannot connect to the Python backend. Start dev-backend.ps1.";
        }
        if (cause instanceof IllegalStateException || cause instanceof IllegalArgumentException) {
            return cause.getMessage() == null ? "Backend returned an error" : cause.getMessage();
        }
        return "Backend request failed (" + cause.getClass().getSimpleName() + "). Check backend logs.";
    }
}
