package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;

/** Small action whitelist. Runs ONLY on the Minecraft server game thread. */
public final class WorldActions {
    private WorldActions() {}

    public static String execute(ServerPlayer player, CompanionEntity npc, String action) {
        if ("none".equals(action)) return "";
        if (npc.isRemoved() || npc.level() != player.level() ||
                player.distanceToSqr(npc) > 64.0D || !player.isAlive() ||
                !player.getUUID().equals(npc.getOwnerUUID())) {
            return "[Action blocked: only the nearby owner can control this NPC.]";
        }
        return switch (action) {
            case "follow" -> {
                npc.setFollowing(true);
                yield "[Action confirmed: following owner.]";
            }
            case "stay" -> {
                npc.setFollowing(false);
                yield "[Action confirmed: staying here.]";
            }
            case "look_at_player" -> {
                npc.lookAtPlayerFor(100);
                yield "[Action confirmed: looking at player for 5 seconds.]";
            }
            default -> "[Action blocked: unknown action.]";
        };
    }
}
