package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Client half of the visible-NPC-name feature. Vanilla draws the name tag from Player#getDisplayName(), which Forge
 * computes through PlayerEvent.NameFormat and caches; we answer that event for the UUIDs the server announced and
 * refresh the cache when the map changes. The GameProfile itself is never touched.
 */
@Mod.EventBusSubscriber(modid = NPCortex.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcNameClient {
    private static final Logger LOG = LogUtils.getLogger();
    private static final NpcNameMap NAMES = new NpcNameMap();

    private NpcNameClient() {}

    static void onPacket(byte[] data) {
        NpcNameCodec.Sync sync;
        try {
            sync = NpcNameCodec.decode(data);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("NPCortex dropped a malformed NPC name packet");
            return;
        }
        refresh(NAMES.apply(sync));
    }

    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        Player player = event.getEntity();
        if (player == null || !player.level().isClientSide()) return;
        String name = NAMES.nameFor(player.getUUID());
        if (name != null) event.setDisplayname(Component.literal(name));
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        NAMES.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Leaving the world or the server: no mapping may leak into the next one.
        refresh(NAMES.clear());
    }

    private static void refresh(Set<UUID> changed) {
        if (changed.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        for (Player player : minecraft.level.players()) {
            if (changed.contains(player.getUUID())) player.refreshDisplayName();
        }
    }
}
