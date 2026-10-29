package io.github.sirp00m.npcortex;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;

/**
 * Operator-only trigger for the D4 engine contracts (C1PlayerActions), so the acceptance Data Pack and an operator can make the NPC
 * attack / use an item / sleep / open and click a container. Same status as c1 give/damage/kill: a deterministic fixture, NOT the AI's
 * (ledgered, authorised) action route.
 */
final class C1ActionCommands {
    private C1ActionCommands() {}

    private static C1PlayerAvatar npc(CommandContext<CommandSourceStack> c) {
        return C1P0Runtime.activeAvatar(c.getSource().getServer(), StringArgumentType.getString(c, "name"));
    }

    private static int reply(CommandContext<CommandSourceStack> c, C1PlayerActions.Result r) {
        c.getSource().sendSuccess(() -> Component.literal("NPCortex: " + (r.ok() ? "ACTION_OK " : "ACTION_REFUSED ") + r.detail()), false);
        return r.ok() ? 1 : 0;
    }

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        var name = Commands.argument("name", StringArgumentType.word());
        return Commands.literal("act").then(name
                .then(Commands.literal("attack").then(Commands.argument("target", EntityArgument.entity())
                        .executes(c -> reply(c, C1PlayerActions.attack(npc(c), EntityArgument.getEntity(c, "target"))))))
                .then(Commands.literal("select").then(Commands.argument("slot", IntegerArgumentType.integer(0, 8))
                        .executes(c -> reply(c, C1PlayerActions.select(npc(c), IntegerArgumentType.getInteger(c, "slot"))))))
                .then(Commands.literal("use").executes(c -> reply(c, C1PlayerActions.useItem(npc(c), InteractionHand.MAIN_HAND))))
                .then(Commands.literal("sleep").then(Commands.argument("bed", BlockPosArgument.blockPos())
                        .executes(c -> reply(c, C1PlayerActions.sleep(npc(c), BlockPosArgument.getLoadedBlockPos(c, "bed"))))))
                .then(Commands.literal("wake").executes(c -> reply(c, C1PlayerActions.wake(npc(c)))))
                .then(Commands.literal("open").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> reply(c, C1PlayerActions.openContainer(npc(c), BlockPosArgument.getLoadedBlockPos(c, "pos"))))))
                .then(Commands.literal("click").then(Commands.argument("slot", IntegerArgumentType.integer(-999, 255))
                        .then(Commands.argument("type", StringArgumentType.word()).executes(c -> {
                            ClickType type;
                            try { type = ClickType.valueOf(StringArgumentType.getString(c, "type").toUpperCase(java.util.Locale.ROOT)); }
                            catch (IllegalArgumentException ex) { return reply(c, C1PlayerActions.Result.no("click type: PICKUP, QUICK_MOVE, SWAP, THROW, ...")); }
                            return reply(c, C1PlayerActions.click(npc(c), IntegerArgumentType.getInteger(c, "slot"), 0, type));
                        }))))
                .then(Commands.literal("close").executes(c -> reply(c, C1PlayerActions.closeContainer(npc(c))))));
    }
}
