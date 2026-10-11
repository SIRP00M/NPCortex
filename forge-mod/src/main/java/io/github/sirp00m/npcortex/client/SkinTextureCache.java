package io.github.sirp00m.npcortex.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.sirp00m.npcortex.NPCortex;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Client-local PNG skins: <gameDir>/config/npcortex/skins/<skinId>.png.
 * Called only on the render thread. A missing/invalid PNG falls back to vanilla skin.
 * Cache reload is performed by restarting the client in this alpha.
 */
public final class SkinTextureCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, ResourceLocation> LOADED = new HashMap<>();
    private static final Set<String> MISSING = new HashSet<>();

    private SkinTextureCache() {}

    public static ResourceLocation resolve(String id, ResourceLocation fallback) {
        if (id == null || !id.matches("[a-z][a-z0-9_-]{0,39}")) return fallback;
        ResourceLocation existing = LOADED.get(id);
        if (existing != null) return existing;
        if (MISSING.contains(id)) return fallback;

        Path path = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(NPCortex.MOD_ID).resolve("skins").resolve(id + ".png");
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 1024 * 1024L) {
                MISSING.add(id);
                return fallback;
            }
            try (InputStream input = Files.newInputStream(path)) {
                NativeImage image = NativeImage.read(input);
                if (image.getWidth() != 64 || image.getHeight() != 64) {
                    image.close();
                    throw new IllegalArgumentException("Skin must be a 64x64 PNG");
                }
                ResourceLocation location = new ResourceLocation(NPCortex.MOD_ID, "uploaded_skins/" + id);
                // DynamicTexture owns the NativeImage after registration.
                Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
                LOADED.put(id, location);
                return location;
            }
        } catch (Exception exception) {
            LOGGER.warn("NPCortex: cannot load skin '{}' from {}", id, path, exception);
            MISSING.add(id);
            return fallback;
        }
    }
}
