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

/** Minimal Forge integration: /npcortex ask <character> <message>. */
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
                                                    ChatBridge.ask(characterId, player.getUUID().toString(), message)
                                                            .whenComplete((reply, error) -> source.getServer().execute(() -> {
                                                                if (error != null) {
                                                                    source.sendFailure(Component.literal(
                                                                            "NPCortex backend unavailable: " + error.getClass().getSimpleName()));
                                                                } else {
                                                                    source.sendSuccess(() -> Component.literal(
                                                                            characterId + ": " + reply), false);
                                                                }
                                                            }));
                                                    return 1;
                                                })))));
    }
}
