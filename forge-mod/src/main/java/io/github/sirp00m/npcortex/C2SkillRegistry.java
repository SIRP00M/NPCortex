package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Extensible single-executor registry; only move_to is advertised for P0. */
final class C2SkillRegistry {
    interface Executor {
        String id();
        String start(C2AgentRuntime.Run run, JsonObject args);
    }
    private static final Map<String, Executor> EXECUTORS = new LinkedHashMap<>();

    static {
        register(new C2MoveExecutor());
    }

    private C2SkillRegistry() {}
    private static void register(Executor executor) {
        if (EXECUTORS.putIfAbsent(executor.id(), executor) != null) {
            throw new IllegalStateException("Duplicate C2 skill: " + executor.id());
        }
    }
    static boolean supports(String id) { return EXECUTORS.containsKey(id); }
    static Executor get(String id) { return EXECUTORS.get(id); }
    static java.util.List<String> advertised() { return java.util.List.copyOf(EXECUTORS.keySet()); }
}
