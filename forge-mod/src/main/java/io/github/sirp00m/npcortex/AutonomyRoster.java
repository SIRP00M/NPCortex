package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Server-authoritative list of currently LOADED NPCs owned by a player. */
public final class AutonomyRoster {
    public static final int MAX_ENTRIES = 128;
    private AutonomyRoster() {}

    public record Entry(UUID uuid, String characterId, String name, String dimension,
                        boolean enabled, boolean following) {}

    public static List<CompanionEntity> ownedLoaded(ServerPlayer player) {
        List<CompanionEntity> npcs = new ArrayList<>();
        for (ServerLevel level : player.getServer().getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof CompanionEntity npc) || npc.isRemoved() || !npc.isAlive()
                        || !player.getUUID().equals(npc.getOwnerUUID())) continue;
                npcs.add(npc);
                if (npcs.size() >= MAX_ENTRIES) return sort(npcs);
            }
        }
        return sort(npcs);
    }

    private static List<CompanionEntity> sort(List<CompanionEntity> npcs) {
        npcs.sort(Comparator.comparing(CompanionEntity::getCharacterId)
                .thenComparing(CompanionEntity::getUUID));
        return npcs;
    }

    public static List<Entry> entries(List<CompanionEntity> npcs) {
        return npcs.stream().map(n -> new Entry(n.getUUID(), n.getCharacterId(),
                n.getName().getString(), n.level().dimension().location().toString(),
                n.isAutonomyEnabled(), n.isFollowing())).toList();
    }

    public static CompanionEntity findOwned(ServerPlayer player, UUID uuid) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            var entity = level.getEntity(uuid);
            if (entity instanceof CompanionEntity npc && npc.isAlive() && !npc.isRemoved()
                    && player.getUUID().equals(npc.getOwnerUUID())) return npc;
        }
        return null;
    }
}
