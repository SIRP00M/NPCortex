package io.github.sirp00m.npcortex;

import net.minecraftforge.common.ForgeConfigSpec;

public final class NPCortexConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> BACKEND_URL;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("AI backend settings. On a dedicated server, use the server's backend address.")
               .push("backend");
        BACKEND_URL = builder.comment("Local HTTP backend URL (no trailing slash)")
                             .define("url", "http://127.0.0.1:8765");
        builder.pop();
        SPEC = builder.build();
    }

    private NPCortexConfig() {}
}
