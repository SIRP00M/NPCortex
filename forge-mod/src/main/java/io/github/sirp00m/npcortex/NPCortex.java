package io.github.sirp00m.npcortex;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

import java.util.concurrent.CompletableFuture;

/** Phase 3: developer-only command bridge. No NPC entity is spawned yet. */
@Mod(NPCortex.MOD_ID)
public final class NPCortex {
    public static final String MOD_ID = "npcortex";

    public NPCortex() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, NPCortexConfig.SPEC);
        MinecraftForge.EVENT_BUS.addListener(NPCortex::registerCommands);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("npcortex")
                        // This is a developer command; limit access to operators in multiplayer.
                        // Enable cheats in a local test world to use it.
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    var source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("NPCortex: Checking backend...")
                                            .withStyle(ChatFormatting.GRAY), false);
                                    CompletableFuture<String> future = ChatBridge.health();
                                    future.whenComplete((result, error) -> source.getServer().execute(() -> {
                                        if (error != null) {
                                            source.sendFailure(Component.literal("NPCortex: " + ChatBridge.describeFailure(error)));
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
                                                    var player = source.getPlayerOrException();
                                                    String characterId = StringArgumentType.getString(context, "character");
                                                    String message = StringArgumentType.getString(context, "message");
                                                    source.sendSuccess(() -> Component.literal("NPCortex: Thinking...")
                                                            .withStyle(ChatFormatting.GRAY), false);
                                                    CompletableFuture<String> future = ChatBridge.ask(
                                                            characterId, player.getUUID().toString(), message);
                                                    future.whenComplete((reply, error) -> source.getServer().execute(() -> {
                                                        if (error != null) {
                                                            source.sendFailure(Component.literal(
                                                                    "NPCortex: " + ChatBridge.describeFailure(error)));
                                                        } else {
                                                            source.sendSuccess(() -> Component.literal(
                                                                    characterId + ": " + reply), false);
                                                        }
                                                    }));
                                                    return 1;
                                                })))));
    }
}
