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

/** Phase 3 chat bridge with opt-in C1-P0 experimental ServerPlayer tracking rig. */
@Mod(NPCortex.MOD_ID)
public final class NPCortex {
    public static final String MOD_ID = "npcortex";

    public NPCortex() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, NPCortexConfig.SPEC);
        C1P0Network.register();
        ManagerNetwork.register();
        NpcNameNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(NPCortex::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(C1P0Runtime::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(CharacterManagerService::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(CharacterManagerService::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(C1VanillaLifecycleCoordinator::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(CPlusF1DiagnosticRuntime::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(CPlusEPreviewRuntime::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(CPlusECoordinatorRuntime::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(CPlusE3G2Runtime::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(WorldEffectRuntime::onServerStopping);
        // Stage 4 native break/pickup (Option D / Architecture B+)
        MinecraftForge.EVENT_BUS.addListener(NativeEffectRuntime::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(NativeEffectRuntime::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(NativeEffectRuntime::onLevelSave);
        MinecraftForge.EVENT_BUS.addListener(NativeEffectRuntime::onItemPickup);
        // TEMP Test-5 acceptance harness only.
        MinecraftForge.EVENT_BUS.addListener(CPlusTest5Runtime::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(CPlusTest5Runtime::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(CPlusM03Runtime::tick); // TEMP M03
        MinecraftForge.EVENT_BUS.addListener(CPlusM03Runtime::stopping); // TEMP M03

        MinecraftForge.EVENT_BUS.addListener(C1P0Runtime::onLogin);
        MinecraftForge.EVENT_BUS.addListener(NpcNameNetwork::onLogin);
        MinecraftForge.EVENT_BUS.addListener(NpcNameNetwork::onRespawn);
        MinecraftForge.EVENT_BUS.addListener(NpcNameNetwork::onDimensionChange);
        MinecraftForge.EVENT_BUS.addListener(NpcTabName::onTabListName);
        MinecraftForge.EVENT_BUS.addListener(C1P0Runtime::onStartTracking);
        MinecraftForge.EVENT_BUS.addListener(C1P0Runtime::onStopTracking);
        MinecraftForge.EVENT_BUS.addListener(C1P0Runtime::onLogout);
        MinecraftForge.EVENT_BUS.addListener(C1VanillaLifecycleCoordinator::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(C1P0Runtime::onViewerChangedDimension);
        MinecraftForge.EVENT_BUS.addListener(C1P0Runtime::onViewerRespawn);
    }

    private static int spawnCommand(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                                    String mode, String preset) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var source = context.getSource();
        String result = C1P0Runtime.spawn(source.getServer(), source.getPlayerOrException(),
                StringArgumentType.getString(context, "name"), mode, preset);
        source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
        return 1;
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("npcortex")
                        // This is a developer command; limit access to operators in multiplayer.
                        // Enable cheats in a local test world to use it.
                        .requires(source -> source.hasPermission(2))
                        .then(C2Commands.build())
                        .then(CPlusF1DiagnosticRuntime.debug())
                        .then(CPlusM04Runtime.commands())
                        .then(Commands.literal("c1")
                                .then(C1P1CLifecycleCommands.build())
                                .then(C1P1CLifecycleCommands.damage())
                                .then(C1P1CLifecycleCommands.combat())
                                .then(C1P1CLifecycleCommands.knockback())
                                .then(C1P1CLifecycleCommands.kill())
                                .then(C1P1CLifecycleCommands.respawn())
                                .then(C1P1CLifecycleCommands.dimension())
                                .then(C1P1InventoryCommands.build())
                        .then(C1P1InventoryCommands.equipment())
                        .then(C1P1InventoryCommands.ledger())
                        .then(C1P1InventoryCommands.give())
                        .then(C1P1InventoryCommands.equip())
                        .then(C1P1InventoryCommands.unequip())
                        .then(C1P1InventoryCommands.clear())
                        .then(C1P1InventoryCommands.copyHand())
                        .then(Commands.literal("spawn")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> spawnCommand(context, "default", ""))
                                                .then(Commands.literal("custom")
                                                        .executes(context -> spawnCommand(context, "custom", "")))
                                                .then(Commands.literal("preset")
                                                        .then(Commands.argument("pack", StringArgumentType.word())
                                                                .executes(context -> spawnCommand(context, "preset",
                                                                        StringArgumentType.getString(context, "pack")))))))
                                .then(Commands.literal("despawn")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    String result = C1P0Runtime.despawn(source.getServer(),
                                                            StringArgumentType.getString(context, "name"));
                                                    source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("info")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    String result = C1P0Runtime.info(source.getServer(),
                                                            StringArgumentType.getString(context, "name"));
                                                    source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("rotation")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    String result = C1P0Runtime.rotationInfo(source.getServer(),
                                                            StringArgumentType.getString(context, "name"));
                                                    source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("sync")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    String result = C1P0Runtime.syncInfo(source.getServer(),
                                                            StringArgumentType.getString(context, "name"));
                                                    source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("move")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("direction", StringArgumentType.word())
                                                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                                java.util.List.of("forward", "back", "left", "right"), builder))
                                                        .then(Commands.argument("blocks", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.5, 8.0))
                                                                .executes(context -> {
                                                                    var source = context.getSource();
                                                                    String result = C1P1MotionRuntime.move(source.getServer(),
                                                                            StringArgumentType.getString(context, "name"),
                                                                            StringArgumentType.getString(context, "direction"),
                                                                            com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "blocks"));
                                                                    source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                                    return 1;
                                                                })))))
                                .then(Commands.literal("turn")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("yaw", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(-180f, 180f))
                                                        .executes(context -> {
                                                            var source = context.getSource();
                                                            String result = C1P1MotionRuntime.turn(source.getServer(),
                                                                    StringArgumentType.getString(context, "name"),
                                                                    com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "yaw"));
                                                            source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                            return 1;
                                                        }))))
                                .then(Commands.literal("traverse")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                                java.util.List.of("step_up", "step_down", "jump_up", "safe_descend"), builder))
                                                        .then(Commands.argument("direction", StringArgumentType.word())
                                                                .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                                        java.util.List.of("forward", "back", "left", "right"), builder))
                                                                .then(Commands.argument("blocks", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.65, 1.45))
                                                                        .executes(context -> {
                                                                            var source = context.getSource();
                                                                            String result = C1P1MotionRuntime.traverse(source.getServer(),
                                                                                    StringArgumentType.getString(context, "name"),
                                                                                    StringArgumentType.getString(context, "type"),
                                                                                    StringArgumentType.getString(context, "direction"),
                                                                                    com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "blocks"));
                                                                            source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                                            return 1;
                                                                        }))))))
                                .then(Commands.literal("jump")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    String result = C1P1MotionRuntime.jump(source.getServer(),
                                                            StringArgumentType.getString(context, "name"));
                                                    source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("settle")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    String result = C1P1MotionRuntime.settle(source.getServer(),
                                                            StringArgumentType.getString(context, "name"));
                                                    source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("stop")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    String result = C1P1MotionRuntime.stop(source.getServer(),
                                                            StringArgumentType.getString(context, "name"));
                                                    source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("motion")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    String result = C1P1MotionRuntime.status(source.getServer(),
                                                            StringArgumentType.getString(context, "name"));
                                                    source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("list")
                                        .executes(context -> {
                                            var source = context.getSource();
                                            String result = C1P0Runtime.list(source.getServer());
                                            source.sendSuccess(() -> Component.literal("NPCortex: " + result), false);
                                            return 1;
                                        })))
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
