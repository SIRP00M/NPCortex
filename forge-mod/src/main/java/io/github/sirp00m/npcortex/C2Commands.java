package io.github.sirp00m.npcortex;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
                .then(Commands.literal("navigate")
                        .then(Commands.argument("name",StringArgumentType.word())
                                .then(Commands.argument("x",IntegerArgumentType.integer())
                                        .then(Commands.argument("y",DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z",IntegerArgumentType.integer())
                                                        .executes(context -> navigate(context)))))))
                .then(Commands.literal("navscan")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> scan(context, C2NavGrid.DEFAULT_RADIUS))
                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,C2NavGrid.MAX_RADIUS))
                                        .executes(context -> scan(context,
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context,"radius"))))))
                .then(Commands.literal("terraininspect")
                        .then(Commands.argument("name",StringArgumentType.word())
                                .then(Commands.argument("x",IntegerArgumentType.integer())
                                        .then(Commands.argument("supportY",IntegerArgumentType.integer())
                                                .then(Commands.argument("z",IntegerArgumentType.integer())
                                                        .executes(context -> terrainInspect(context)))))))
                .then(Commands.literal("navgraph")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> graph(context, C2NavGrid.DEFAULT_RADIUS))
                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,C2NavGrid.MAX_RADIUS))
                                        .executes(context -> graph(context,
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context,"radius"))))))
                .then(Commands.literal("navplan")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> plan(context, C2NavGrid.MAX_RADIUS,
                                                                C2AStarPlanner.DEFAULT_EXPANSION_BUDGET))
                                                        .then(Commands.argument("radius",IntegerArgumentType.integer(1,C2NavGrid.MAX_RADIUS))
                                                                .executes(context -> plan(context,
                                                                        IntegerArgumentType.getInteger(context,"radius"),
                                                                        C2AStarPlanner.DEFAULT_EXPANSION_BUDGET))
                                                                .then(Commands.argument("budget",IntegerArgumentType.integer(1,C2AStarPlanner.MAX_EXPANSION_BUDGET))
                                                                        .executes(context -> plan(context,
                                                                                IntegerArgumentType.getInteger(context,"radius"),
                                                                                IntegerArgumentType.getInteger(context,"budget"))))))))))
                .then(Commands.literal("navregion")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> region(context,C2NavGrid.MAX_RADIUS,C2RegionGraph.DEFAULT_TILE_SIZE))
                                .then(Commands.argument("radius",IntegerArgumentType.integer(1,C2NavGrid.MAX_RADIUS))
                                        .executes(context -> region(context,IntegerArgumentType.getInteger(context,"radius"),
                                                C2RegionGraph.DEFAULT_TILE_SIZE))
                                        .then(Commands.argument("tileSize",IntegerArgumentType.integer(
                                                C2RegionGraph.MIN_TILE_SIZE,C2RegionGraph.MAX_TILE_SIZE))
                                                .executes(context -> region(context,
                                                        IntegerArgumentType.getInteger(context,"radius"),
                                                        IntegerArgumentType.getInteger(context,"tileSize")))))))
                .then(Commands.literal("navhier")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("x",IntegerArgumentType.integer())
                                        .then(Commands.argument("y",DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z",IntegerArgumentType.integer())
                                                        .executes(context -> hierarchical(context,
                                                                C2NavGrid.MAX_RADIUS,C2HierarchicalPlanner.DEFAULT_BUDGET))
                                                        .then(Commands.argument("radius",IntegerArgumentType.integer(1,C2NavGrid.MAX_RADIUS))
                                                                .executes(context -> hierarchical(context,
                                                                        IntegerArgumentType.getInteger(context,"radius"),
                                                                        C2HierarchicalPlanner.DEFAULT_BUDGET))
                                                                .then(Commands.argument("budget",IntegerArgumentType.integer(1,C2HierarchicalPlanner.MAX_BUDGET))
                                                                        .executes(context -> hierarchical(context,
                                                                                IntegerArgumentType.getInteger(context,"radius"),
                                                                                IntegerArgumentType.getInteger(context,"budget"))))))))))
                .then(Commands.literal("follow")
                        .then(Commands.argument("name",StringArgumentType.word())
                                .then(Commands.argument("player",StringArgumentType.word())
                                        .executes(context -> {
                                            var src=context.getSource();
                                            String result=C2FollowRuntime.follow(src.getServer(),
                                                    StringArgumentType.getString(context,"name"),
                                                    StringArgumentType.getString(context,"player"));
                                            src.sendSuccess(() -> Component.literal("NPCortex: "+result),false);
                                            return 1;
                                        }))))
                .then(Commands.literal("unfollow")
                        .then(Commands.argument("name",StringArgumentType.word()).executes(context -> {
                            var src=context.getSource();
                            String result=C2FollowRuntime.unfollow(src.getServer(),
                                    StringArgumentType.getString(context,"name"));
                            src.sendSuccess(() -> Component.literal("NPCortex: "+result),false);
                            return 1;
                        })))
                .then(Commands.literal("followstatus")
                        .then(Commands.argument("name",StringArgumentType.word()).executes(context -> {
                            var src=context.getSource();
                            String result=C2FollowRuntime.status(src.getServer(),
                                    StringArgumentType.getString(context,"name"));
                            src.sendSuccess(() -> Component.literal("NPCortex: "+result),false);
                            return 1;
                        })))
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

    private static int terrainInspect(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
        var src=ctx.getSource();
        String message=C2NavigationSensor.inspect(src.getServer(),StringArgumentType.getString(ctx,"name"),
                IntegerArgumentType.getInteger(ctx,"x"),IntegerArgumentType.getInteger(ctx,"supportY"),
                IntegerArgumentType.getInteger(ctx,"z"));
        src.sendSuccess(() -> Component.literal("NPCortex: "+message),false);
        return 1;
    }

    private static int navigate(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
        var src=ctx.getSource();
        String msg=C2AgentRuntime.navigate(src.getServer(),StringArgumentType.getString(ctx,"name"),
                IntegerArgumentType.getInteger(ctx,"x"),DoubleArgumentType.getDouble(ctx,"y"),
                IntegerArgumentType.getInteger(ctx,"z"));
        src.sendSuccess(() -> Component.literal("NPCortex: "+msg),false);
        return 1;
    }

    private static int scan(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
                            int radius) {
        var src = ctx.getSource();
        String result = C2NavigationSensor.diagnostic(src.getServer(),
                StringArgumentType.getString(ctx,"name"), radius);
        src.sendSuccess(() -> Component.literal("NPCortex: " + result),false);
        return 1;
    }

    private static int graph(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
                             int radius) {
        var src = ctx.getSource();
        String result = C2GraphWorldProbe.diagnostic(src.getServer(),
                StringArgumentType.getString(ctx,"name"),radius);
        src.sendSuccess(() -> Component.literal("NPCortex: " + result),false);
        return 1;
    }

    /** y is target FEET block coordinate; support block is y-1. */
    private static int plan(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
                            int radius,int budget) {
        var src=ctx.getSource();
        String result=C2PathDiagnostics.diagnostic(src.getServer(),
                StringArgumentType.getString(ctx,"name"),
                IntegerArgumentType.getInteger(ctx,"x"),
                DoubleArgumentType.getDouble(ctx,"y"),
                IntegerArgumentType.getInteger(ctx,"z"),radius,budget);
        src.sendSuccess(() -> Component.literal("NPCortex: " + result),false);
        return 1;
    }

    private static int region(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
                              int radius,int tileSize) {
        var src=ctx.getSource();
        String value=C2RegionDiagnostics.regions(src.getServer(),StringArgumentType.getString(ctx,"name"),radius,tileSize);
        src.sendSuccess(() -> Component.literal("NPCortex: "+value),false);
        return 1;
    }

    private static int hierarchical(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
                                    int radius,int budget) {
        var src=ctx.getSource();
        String value=C2RegionDiagnostics.hierarchical(src.getServer(),StringArgumentType.getString(ctx,"name"),
                IntegerArgumentType.getInteger(ctx,"x"),DoubleArgumentType.getDouble(ctx,"y"),
                IntegerArgumentType.getInteger(ctx,"z"),radius,C2RegionGraph.DEFAULT_TILE_SIZE,budget);
        src.sendSuccess(() -> Component.literal("NPCortex: "+value),false);
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
