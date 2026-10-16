package io.github.sirp00m.npcortex;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Operator-only P0 fixture. Natural language -> target perception is future scope. */
final class C2Commands {
    private C2Commands() {}

    static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> build() {
        return Commands.literal("c2")
                .then(Commands.literal("move")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(context -> queueMove(context, C2MoveGeometry.DEFAULT_TOLERANCE))
                                                        .then(Commands.argument("tolerance", DoubleArgumentType.doubleArg(
                                                                        C2MoveGeometry.MIN_TOLERANCE,C2MoveGeometry.MAX_TOLERANCE))
                                                                .executes(context -> queueMove(context,
                                                                        DoubleArgumentType.getDouble(context,"tolerance")))))))))
                .then(Commands.literal("navscan")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> scan(context, C2NavGrid.DEFAULT_RADIUS))
                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,C2NavGrid.MAX_RADIUS))
                                        .executes(context -> scan(context,
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context,"radius"))))))
                .then(Commands.literal("status")
                        .then(Commands.argument("name",StringArgumentType.word()).executes(context -> {
                            var src=context.getSource();
                            String result=C2AgentRuntime.status(src.getServer(),StringArgumentType.getString(context,"name"));
                            src.sendSuccess(() -> Component.literal("NPCortex: " + result),false);
                            return 1;
                        })))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("name",StringArgumentType.word()).executes(context -> {
                            var src=context.getSource();
                            String result=C2AgentRuntime.cancel(src.getServer(),StringArgumentType.getString(context,"name"));
                            src.sendSuccess(() -> Component.literal("NPCortex: " + result),false);
                            return 1;
                        })))
                .then(Commands.literal("reset")
                        .then(Commands.argument("name",StringArgumentType.word()).executes(context -> {
                            var src=context.getSource();
                            String result=C2AgentRuntime.reset(src.getServer(),StringArgumentType.getString(context,"name"));
                            src.sendSuccess(() -> Component.literal("NPCortex: " + result),false);
                            return 1;
                        })));
    }

    private static int scan(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
                            int radius) {
        var src = ctx.getSource();
        String result = C2NavigationSensor.diagnostic(src.getServer(),
                StringArgumentType.getString(ctx,"name"), radius);
        src.sendSuccess(() -> Component.literal("NPCortex: " + result),false);
        return 1;
    }

    private static int queueMove(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
                                 double tolerance) {
        var src = ctx.getSource();
        String result = C2AgentRuntime.start(src.getServer(),
                StringArgumentType.getString(ctx,"name"),
                DoubleArgumentType.getDouble(ctx,"x"),
                DoubleArgumentType.getDouble(ctx,"y"),
                DoubleArgumentType.getDouble(ctx,"z"), tolerance);
        src.sendSuccess(() -> Component.literal("NPCortex: " + result),false);
        return 1;
    }
}
