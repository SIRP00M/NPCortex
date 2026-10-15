package io.github.sirp00m.npcortex;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** Operator-only P1-B fixture commands under the existing permission-2 C1 root. */
final class C1P1InventoryCommands {
    private C1P1InventoryCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("inventory")
                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx ->
                        reply(ctx, C1P1InventoryRuntime.inventory(ctx.getSource().getServer(), name(ctx)))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> equipment() {
        return Commands.literal("equipment")
                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx ->
                        reply(ctx, C1P1InventoryRuntime.equipment(ctx.getSource().getServer(), name(ctx)))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> ledger() {
        return Commands.literal("ledger")
                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx ->
                        reply(ctx, C1P1InventoryRuntime.ledger(ctx.getSource().getServer(), name(ctx)))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> give() {
        var count = Commands.argument("count", IntegerArgumentType.integer(1, 64))
                .executes(ctx -> give(ctx, UUID.randomUUID()))
                .then(Commands.argument("tx", StringArgumentType.word()).executes(ctx -> give(ctx, transaction(ctx))));
        return Commands.literal("give")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("item", ResourceLocationArgument.id()).then(count)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> clear() {
        var slot = Commands.argument("slot", IntegerArgumentType.integer(0, 40))
                .executes(ctx -> clear(ctx, UUID.randomUUID()))
                .then(Commands.argument("tx", StringArgumentType.word()).executes(ctx -> clear(ctx, transaction(ctx))));
        return Commands.literal("clear")
                .then(Commands.argument("name", StringArgumentType.word()).then(slot));
    }

    static LiteralArgumentBuilder<CommandSourceStack> equip() {
        var position = Commands.argument("position", StringArgumentType.word())
                .executes(ctx -> equip(ctx, UUID.randomUUID()))
                .then(Commands.argument("tx", StringArgumentType.word()).executes(ctx -> equip(ctx, transaction(ctx))));
        return Commands.literal("equip")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 35)).then(position)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> copyHand() {
        var slot = Commands.argument("slot", IntegerArgumentType.integer(0, 40))
                .executes(ctx -> copyHand(ctx, UUID.randomUUID()))
                .then(Commands.argument("tx", StringArgumentType.word()).executes(ctx -> copyHand(ctx, transaction(ctx))));
        return Commands.literal("copyhand")
                .then(Commands.argument("name", StringArgumentType.word()).then(slot));
    }

    private static String name(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "name");
    }

    private static UUID transaction(CommandContext<CommandSourceStack> ctx) {
        try {
            return UUID.fromString(StringArgumentType.getString(ctx, "tx"));
        } catch (IllegalArgumentException invalid) {
            // Caught at the wrapper below; never invoke the runtime with random fallback UUID.
            return null;
        }
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal("NPCortex: " + text), false);
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, UUID tx) {
        if (tx == null) return reply(ctx, "INVALID transaction UUID");
        return reply(ctx, C1P1InventoryRuntime.give(ctx.getSource().getServer(), name(ctx),
                ResourceLocationArgument.getId(ctx, "item"), IntegerArgumentType.getInteger(ctx, "count"), tx));
    }

    private static int clear(CommandContext<CommandSourceStack> ctx, UUID tx) {
        if (tx == null) return reply(ctx, "INVALID transaction UUID");
        return reply(ctx, C1P1InventoryRuntime.clear(ctx.getSource().getServer(), name(ctx),
                IntegerArgumentType.getInteger(ctx, "slot"), tx));
    }

    private static int equip(CommandContext<CommandSourceStack> ctx, UUID tx) {
        if (tx == null) return reply(ctx, "INVALID transaction UUID");
        return reply(ctx, C1P1InventoryRuntime.equip(ctx.getSource().getServer(), name(ctx),
                IntegerArgumentType.getInteger(ctx, "slot"), StringArgumentType.getString(ctx, "position"), tx));
    }

    private static int copyHand(CommandContext<CommandSourceStack> ctx, UUID tx) throws CommandSyntaxException {
        if (tx == null) return reply(ctx, "INVALID transaction UUID");
        return reply(ctx, C1P1InventoryRuntime.copyHand(ctx.getSource().getServer(), name(ctx),
                ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "slot"), tx));
    }
}
