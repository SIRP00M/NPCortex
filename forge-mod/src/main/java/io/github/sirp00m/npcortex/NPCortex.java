package io.github.sirp00m.npcortex;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import io.github.sirp00m.npcortex.entity.NpcEntities;
import io.github.sirp00m.npcortex.network.NpcNetwork;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Phase 4: generic persistent NPC entity, local dialogue and basic follow/stay. */
@Mod(NPCortex.MOD_ID)
public final class NPCortex {
    public static final String MOD_ID = "npcortex";

    public NPCortex() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, NPCortexConfig.SPEC);
        NpcEntities.register(FMLJavaModLoadingContext.get().getModEventBus());
        NpcNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(NPCortex::registerCommands);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("npcortex")
                // All commands are developer-only in alpha. Enable cheats in test worlds.
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    var source = context.getSource();
                    source.sendSuccess(() -> Component.literal("NPCortex: Checking backend...")
                            .withStyle(ChatFormatting.GRAY), false);
                    ChatBridge.health().whenComplete((result, error) -> source.getServer().execute(() -> {
                        if (error != null) {
                            source.sendFailure(Component.literal("NPCortex: Backend unreachable."));
                        } else {
                            source.sendSuccess(() -> Component.literal("NPCortex: " + result)
                                    .withStyle(ChatFormatting.GREEN), false);
                        }
                    }));
                    return 1;
                }))
                .then(Commands.literal("ask")
                        .then(Commands.argument("character", StringArgumentType.word())
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            var source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();
                                            String characterId = StringArgumentType.getString(context, "character");
                                            String message = StringArgumentType.getString(context, "message");
                                            source.sendSuccess(() -> Component.literal("NPCortex: Thinking...")
                                                    .withStyle(ChatFormatting.GRAY), false);
                                            CompletableFuture<String> future = ChatBridge.ask(
                                                    characterId, player.getUUID().toString(), message);
                                            future.whenComplete((reply, error) -> source.getServer().execute(() -> {
                                                if (error != null) {
                                                    source.sendFailure(Component.literal(
                                                            "NPCortex: Chat failed. Check backend and character ID."));
                                                } else {
                                                    source.sendSuccess(() -> Component.literal(characterId + ": " + reply), false);
                                                }
                                            }));
                                            return 1;
                                        }))))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("character", StringArgumentType.word())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    ServerLevel level = player.serverLevel();
                                    String id = StringArgumentType.getString(context, "character");
                                    if (!id.matches("[a-zA-Z0-9_-]{1,64}")) {
                                        context.getSource().sendFailure(Component.literal("Invalid character ID."));
                                        return 0;
                                    }
                                    long count = level.getEntitiesOfClass(CompanionEntity.class,
                                            player.getBoundingBox().inflate(32.0D),
                                            npc -> player.getUUID().equals(npc.getOwnerUUID())).size();
                                    if (count >= 8) {
                                        context.getSource().sendFailure(Component.literal(
                                                "Too many companions nearby (8 max for this alpha)."));
                                        return 0;
                                    }
                                    CompanionEntity npc = NpcEntities.COMPANION.get().create(level);
                                    if (npc == null) return 0;
                                    npc.setCharacterId(id);
                                    npc.setOwnerUUID(player.getUUID());
                                    npc.moveTo(player.getX() + 1.5D, player.getY(), player.getZ(),
                                            player.getYRot(), 0.0F);
                                    if (!level.addFreshEntity(npc)) {
                                        context.getSource().sendFailure(Component.literal("Unable to spawn companion."));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "NPCortex: Spawned " + id + ". Right-click to chat."), false);
                                    return 1;
                                })))
                .then(Commands.literal("follow").executes(context -> setFollow(context.getSource().getPlayerOrException(), true)))
                .then(Commands.literal("stay").executes(context -> setFollow(context.getSource().getPlayerOrException(), false))));
    }

    private static int setFollow(ServerPlayer player, boolean following) {
        List<CompanionEntity> nearby = player.serverLevel().getEntitiesOfClass(
                CompanionEntity.class, player.getBoundingBox().inflate(12.0D),
                npc -> player.getUUID().equals(npc.getOwnerUUID()));
        CompanionEntity npc = nearby.stream()
                .min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (npc == null) {
            player.sendSystemMessage(Component.literal("NPCortex: No owned companion within 12 blocks."));
            return 0;
        }
        npc.setFollowing(following);
        player.sendSystemMessage(Component.literal("NPCortex: " + npc.getCharacterId()
                + (following ? " is following you." : " will stay here.")));
        return 1;
    }
}
