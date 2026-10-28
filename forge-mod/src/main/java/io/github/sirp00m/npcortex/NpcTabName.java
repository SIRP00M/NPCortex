package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Server-side TAB player-list name of NPCortex avatars. ServerPlayer#getTabListDisplayName() (Forge: PlayerEvent.TabListNameFormat, cached) is what
 * the ADD_PLAYER packet carries, so every place C1P0Runtime sends player info already delivers the logical name. The GameProfile (name, UUID) is not
 * touched, refreshTabListName() is never called (it would broadcast an NPC's info to clients that do not track it), real players are never matched
 * and visibility in the list is unchanged.
 */
final class NpcTabName {
    private static final Logger LOG = LogUtils.getLogger();

    private NpcTabName() {}

    /** The logical C1 name of the NPC whose GameProfile UUID is {@code profileId}, or null (unknown NPC / invalid name): then vanilla's name stays. */
    static String resolve(UUID profileId, Iterable<C1IdentityData.Entry> entries) {
        if (profileId == null || entries == null) return null;
        for (C1IdentityData.Entry entry : entries) {
            if (profileId.equals(entry.profileId())) return NpcNameCodec.validName(entry.character()) ? entry.character() : null;
        }
        return null;
    }

    static void onTabListName(PlayerEvent.TabListNameFormat event) {
        if (!(event.getEntity() instanceof C1PlayerAvatar avatar)) return;
        try {
            MinecraftServer server = avatar.getServer();
            if (server == null) return;
            String name = resolve(avatar.getUUID(), C1IdentityData.get(server).all());
            if (name != null) event.setDisplayName(Component.literal(name));
        } catch (RuntimeException ex) {
            LOG.warn("NPCortex TAB name not set: {}", ex.getClass().getSimpleName());   // fall back to the profile name
        }
    }
}
