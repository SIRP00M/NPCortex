package io.github.sirp00m.npcortex.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.github.sirp00m.npcortex.NPCortex;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * Textures are client-local to the current Minecraft instance, not the repository.
 * Access on the Minecraft render thread only. Reload actively releases GPU textures.
 */
public final class SkinTextureCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, ResourceLocation> LOADED = new HashMap<>();
    private static final Map<String, Long> RETRY_AFTER = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();
    private static final long RETRY_DELAY = TimeUnit.SECONDS.toNanos(10);

    private SkinTextureCache() {}

    public static Path skinDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(NPCortex.MOD_ID).resolve("skins");
    }

    public static boolean exists(String id) {
        return SkinFileManager.validId(id) && Files.isRegularFile(skinDirectory().resolve(id + ".png"));
    }

    public static ResourceLocation resolve(String id, ResourceLocation fallback) {
        if (!SkinFileManager.validId(id)) return fallback;
        ResourceLocation cached = LOADED.get(id);
        if (cached != null) return cached;
        long now = System.nanoTime();
        Long next = RETRY_AFTER.get(id);
        if (next != null && now - next < 0) return fallback;
        RETRY_AFTER.put(id, now + RETRY_DELAY);

        Path source = skinDirectory().resolve(id + ".png");
        if (!Files.isRegularFile(source)) {
            if (WARNED.add(id)) LOGGER.warn("NPCortex: skin '{}' not found: {}", id, source);
            return fallback;
        }
        try {
            if (Files.size(source) > SkinFileManager.MAX_SIZE)
                throw new IllegalArgumentException("Skin is larger than 1 MiB");
            try (InputStream stream = Files.newInputStream(source)) {
                NativeImage image = NativeImage.read(stream);
                if (image.getWidth() != 64 || image.getHeight() != 64) {
                    image.close();
                    throw new IllegalArgumentException("Skin must be 64x64");
                }
                ResourceLocation location = new ResourceLocation(NPCortex.MOD_ID, "uploaded_skins/" + id);
                // DynamicTexture owns the image after registration.
                Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
                LOADED.put(id, location);
                RETRY_AFTER.remove(id);
                WARNED.remove(id);
                LOGGER.info("NPCortex: loaded skin '{}' from {}", id, source);
                return location;
            }
        } catch (Exception error) {
            if (WARNED.add(id)) LOGGER.warn("NPCortex: skin '{}' failed to load from {}", id, source, error);
            return fallback;
        }
    }

    /** Force the next render to load the new PNG without restarting Minecraft. */
    public static void reload(String id) {
        if (!SkinFileManager.validId(id)) return;
        ResourceLocation old = LOADED.remove(id);
        if (old != null) Minecraft.getInstance().getTextureManager().release(old);
        RETRY_AFTER.remove(id);
        WARNED.remove(id);
    }

    public static void reloadAll() {
        for (String id : Set.copyOf(LOADED.keySet())) reload(id);
        RETRY_AFTER.clear();
        WARNED.clear();
    }
}
