package io.github.sirp00m.npcortex;

import net.minecraftforge.common.ForgeConfigSpec;

public final class NPCortexConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> BACKEND_URL;
    public static final ForgeConfigSpec.IntValue AUTONOMY_POLL_TICKS;
    public static final ForgeConfigSpec.IntValue AUTONOMY_ALERT_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.DoubleValue AUTONOMY_ALERT_RADIUS;
    public static final ForgeConfigSpec.DoubleValue AUTONOMY_RETREAT_RADIUS;
    public static final ForgeConfigSpec.BooleanValue AUTONOMY_RETREAT;
    public static final ForgeConfigSpec.BooleanValue AUTONOMY_AI_SPEECH;
    public static final ForgeConfigSpec.BooleanValue AUTONOMY_FALLBACK_WARNING;
    public static final ForgeConfigSpec.BooleanValue WORLD_EDITS_ENABLED;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("AI backend settings. On a dedicated server, use the server's backend address.")
               .push("backend");
        BACKEND_URL = builder.comment("Local HTTP backend URL (no trailing slash)")
                             .define("url", "http://127.0.0.1:8765");
        builder.pop();
        builder.comment("Autonomy is disabled per NPC until its owner uses /npcortex auto on")
                .push("autonomy");
        AUTONOMY_POLL_TICKS = builder.comment("Server-side sensor polling; no LLM calls per tick")
                .defineInRange("poll_ticks", 40, 20, 200);
        AUTONOMY_ALERT_COOLDOWN_TICKS = builder.comment("Cooldown per NPC between warnings / optional AI reactions")
                .defineInRange("alert_cooldown_ticks", 600, 100, 72000);
        AUTONOMY_ALERT_RADIUS = builder.defineInRange("alert_radius", 10.0D, 4.0D, 16.0D);
        AUTONOMY_RETREAT_RADIUS = builder.defineInRange("retreat_radius", 4.0D, 1.5D, 8.0D);
        AUTONOMY_RETREAT = builder.define("enable_retreat", true);
        AUTONOMY_AI_SPEECH = builder.define("enable_ai_reactions", true);
        AUTONOMY_FALLBACK_WARNING = builder.comment(
                "Only show a system warning if the AI reaction is unavailable or disabled; never duplicate an AI reply")
                .define("fallback_warning_when_ai_unavailable", true);
        builder.pop();
        builder.comment("Safe singleplayer world editing; OFF by default. Backup your world first.")
                .push("world_interaction");
        WORLD_EDITS_ENABLED = builder.comment("Allow registered mining/placement actions; also requires cheats")
                .define("enable_world_edits", false);
        builder.pop();
        SPEC = builder.build();
    }

    private NPCortexConfig() {}
}
