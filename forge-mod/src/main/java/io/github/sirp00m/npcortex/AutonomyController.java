package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

/** Bounded, opt-in server-side autonomy. No LLM output can execute an autonomous action. */
public final class AutonomyController {
    private AutonomyController() {}

    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        long tick = level.getGameTime();
        int pollTicks = NPCortexConfig.AUTONOMY_POLL_TICKS.get();
        if (tick % pollTicks != 0) return;
        Set<UUID> visited = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            // Only loaded, owned NPCs near their owner; each NPC is processed at most once.
            var companions = level.getEntitiesOfClass(CompanionEntity.class,
                    player.getBoundingBox().inflate(32.0D),
                    npc -> npc.isAlive() && npc.isAutonomyEnabled()
                            && player.getUUID().equals(npc.getOwnerUUID()));
            for (CompanionEntity npc : companions) {
                if (!visited.add(npc.getUUID()) || player.distanceToSqr(npc) > 32.0D * 32.0D)
                    continue;
                sense(player, npc, tick);
            }
        }
    }

    private static void sense(ServerPlayer owner, CompanionEntity npc, long tick) {
        double radius = NPCortexConfig.AUTONOMY_ALERT_RADIUS.get();
        var closest = npc.level().getEntitiesOfClass(Monster.class,
                npc.getBoundingBox().inflate(radius), mob -> mob.isAlive()
                    && mob.distanceToSqr(npc) <= radius * radius
                    && npc.getSensing().hasLineOfSight(mob)).stream()
                .min(Comparator.comparingDouble(npc::distanceToSqr));
        if (closest.isEmpty()) return;
        Monster threat = closest.get();
        double distance = Math.sqrt(npc.distanceToSqr(threat));
        var decision = AutonomyPolicy.decide(npc.isAutonomyEnabled(), true, true,
                npc.isFollowing(), ActionPlanRunner.isBusy(npc), npc.isRetreating(),
                distance, radius, NPCortexConfig.AUTONOMY_RETREAT_RADIUS.get(),
                tick, npc.nextAutonomyWarningTick());
        if (!decision.warnOwner() && !decision.tryRetreat()) return;
        String mob = BuiltInRegistries.ENTITY_TYPE.getKey(threat.getType()).toString();
        boolean retreatStarted = false;
        if (decision.tryRetreat() && NPCortexConfig.AUTONOMY_RETREAT.get()) {
            // Pathfinding only; no teleport, block edits, or hostile attacks.
            double dx = npc.getX() - threat.getX();
            double dz = npc.getZ() - threat.getZ();
            double len = Math.hypot(dx, dz);
            if (len < 0.05D) { dx = 1.0D; dz = 0; len = 1.0D; }
            Vec3 target = new Vec3(npc.getX() + 6.0D * dx / len,
                    npc.getY(), npc.getZ() + 6.0D * dz / len);
            retreatStarted = npc.beginRetreat(target, 60);
        }
        if (!decision.warnOwner()) return;
        npc.setNextAutonomyWarningTick(tick + NPCortexConfig.AUTONOMY_ALERT_COOLDOWN_TICKS.get());
        String name = npc.getName().getString();
        // Pre-format only as an ERROR/disabled-AI fallback; it is not displayed
        // when Qwen successfully generates the in-character warning.
        String fallbackText = "[NPCortex] " + name + ": Watch out! "
                + mob + " is nearby (" + Math.round(distance) + " blocks)!"
                + (retreatStarted ? " I'm trying to move away." : "");
        boolean fallbackEnabled = NPCortexConfig.AUTONOMY_FALLBACK_WARNING.get();
        if (!NPCortexConfig.AUTONOMY_AI_SPEECH.get()) {
            if (AutonomySpeechPolicy.choose(false, false, fallbackEnabled)
                    == AutonomySpeechPolicy.Output.FALLBACK) {
                owner.sendSystemMessage(Component.literal(fallbackText));
            }
            return;
        }
        // Purely cosmetic speech. Sensor decisions, retreat and cooldown already ran.
        UUID ownerId = owner.getUUID();
        UUID npcId = npc.getUUID();
        String result = retreatStarted ? "retreat_started" : "warned_owner";
        ChatBridge.autonomyReply(npc.getCharacterId(), ownerId.toString(),
                npcId.toString(), mob, result).whenComplete((reply, error) -> {
            var server = owner.getServer();
            if (server == null) return;
            server.execute(() -> {
                // Don't deliver stale AI dialogue after an owner leaves or autonomy is off.
                if (npc.isRemoved() || !npc.isAutonomyEnabled() || owner.level() != npc.level()
                        || !ownerId.equals(npc.getOwnerUUID()) || owner.distanceToSqr(npc) > 1024.0D)
                    return;
                boolean validReply = error == null && reply != null && !reply.isBlank();
                var output = AutonomySpeechPolicy.choose(true, validReply, fallbackEnabled);
                if (output == AutonomySpeechPolicy.Output.AI) {
                    owner.sendSystemMessage(Component.literal(name + ": " + reply));
                } else if (output == AutonomySpeechPolicy.Output.FALLBACK) {
                    owner.sendSystemMessage(Component.literal(fallbackText));
                }
            });
        });
    }
}
