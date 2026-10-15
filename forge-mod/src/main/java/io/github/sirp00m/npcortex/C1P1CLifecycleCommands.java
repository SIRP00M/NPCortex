package io.github.sirp00m.npcortex;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;

/** Operator-only deterministic fixtures; not a player-facing AI skill API. */
final class C1P1CLifecycleCommands {
    private C1P1CLifecycleCommands() {}
    private static String name(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c, "name"); }
    private static int reply(CommandContext<CommandSourceStack> c, String value) {
        c.getSource().sendSuccess(() -> Component.literal("NPCortex: " + value), false);
        return 1;
    }
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("lifecycle")
                .then(Commands.argument("name", StringArgumentType.word()).executes(c ->
                        reply(c, C1P1CLifecycleRuntime.status(c.getSource().getServer(), name(c)))));
    }
    static LiteralArgumentBuilder<CommandSourceStack> combat() {
        return Commands.literal("combat")
                .then(Commands.argument("name", StringArgumentType.word()).executes(c ->
                        reply(c, C1P1MotionRuntime.combatStatus(c.getSource().getServer(), name(c)))));
    }
    static LiteralArgumentBuilder<CommandSourceStack> knockback() {
        return Commands.literal("knockback")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("strength", DoubleArgumentType.doubleArg(0.1, 2.0))
                                .executes(c -> reply(c, C1P1MotionRuntime.testKnockback(
                                        c.getSource().getServer(), c.getSource().getPlayerOrException(),
                                        name(c), DoubleArgumentType.getDouble(c, "strength"))))));
    }
    static LiteralArgumentBuilder<CommandSourceStack> damage() {
        return Commands.literal("damage")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0.01f, 1000f))
                                .executes(c -> reply(c, C1P1CLifecycleRuntime.damage(c.getSource().getServer(),
                                        name(c), FloatArgumentType.getFloat(c, "amount"))))));
    }
    static LiteralArgumentBuilder<CommandSourceStack> kill() {
        return Commands.literal("kill")
                .then(Commands.argument("name", StringArgumentType.word()).executes(c ->
                        reply(c, C1P1CLifecycleRuntime.kill(c.getSource().getServer(), name(c)))));
    }
    static LiteralArgumentBuilder<CommandSourceStack> respawn() {
        return Commands.literal("respawn")
                .then(Commands.argument("name", StringArgumentType.word()).executes(c ->
                        reply(c, C1P1CLifecycleRuntime.respawn(c.getSource().getServer(), name(c)))));
    }
    static LiteralArgumentBuilder<CommandSourceStack> dimension() {
        return Commands.literal("dimension")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("destination", ResourceLocationArgument.id())
                                .suggests((c,b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        java.util.List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"), b))
                                .executes(c -> reply(c, C1P1CLifecycleRuntime.dimension(c.getSource().getServer(),
                                        c.getSource().getPlayerOrException(), name(c),
                                        ResourceLocationArgument.getId(c, "destination").toString())))));
    }
}
