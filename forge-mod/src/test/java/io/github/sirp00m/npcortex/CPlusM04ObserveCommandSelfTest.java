package io.github.sirp00m.npcortex;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** The read-only {@code /npcortex m04 observe} command as registered: who can parse it, which arguments are accepted, and that
 * the m04 command tree still contains NO mutating verb. Brigadier parsing with real CommandSourceStack objects at permission
 * levels 0..4; nothing is executed (that needs a live server). */
public final class CPlusM04ObserveCommandSelfTest {
    private static int checks;
    private static void ok(boolean pass, String message) { checks++; if (!pass) throw new AssertionError(message); }

    private static CommandSourceStack stack(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission, "t", Component.literal("t"), null, null);
    }

    private static boolean executable(CommandDispatcher<CommandSourceStack> d, String line, int permission) {
        ParseResults<CommandSourceStack> parse = d.parse(line, stack(permission));
        return !parse.getReader().canRead() && parse.getExceptions().isEmpty() && parse.getContext().getCommand() != null;
    }

    public static void main(String[] args) throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(CPlusM04Runtime.commands());

        // authorization: the whole m04 tree needs permission level 2 (a data-pack function starts at 2; see COMMAND_ORIGIN_INVESTIGATION)
        for (int level = 0; level <= 1; level++) {
            ok(!executable(dispatcher, "m04 observe yui 4 minecraft:oak_log", level), "level " + level + " cannot run observe");
            try {
                dispatcher.execute("m04 observe yui 4 minecraft:oak_log", stack(level));
                ok(false, "an unauthorized source must not execute observe");
            } catch (CommandSyntaxException expected) {
                ok(true, "unauthorized => unknown command");
            }
        }
        for (int level = 2; level <= 4; level++)
            ok(executable(dispatcher, "m04 observe yui 4 minecraft:oak_log", level), "level " + level + " may run observe");

        // argument validation happens at parse time
        ok(executable(dispatcher, "m04 observe yui 0 minecraft:oak_log", 2) && executable(dispatcher, "m04 observe yui 16 minecraft:spruce_log", 2),
                "radius 0 and 16 (the maximum) are accepted");
        ok(!executable(dispatcher, "m04 observe yui 17 minecraft:oak_log", 2), "radius 17 is refused by the argument type");
        ok(!executable(dispatcher, "m04 observe yui -1 minecraft:oak_log", 2), "negative radius refused");
        ok(!executable(dispatcher, "m04 observe yui 4", 2), "block id is mandatory");
        ok(!executable(dispatcher, "m04 observe yui 4 minecraft:oak_log extra", 2), "no trailing arguments");
        ok(!executable(dispatcher, "m04 observe", 2) && !executable(dispatcher, "m04 observe yui", 2), "incomplete commands do not execute");
        ok(CPlusWorldPerception.MAX_RADIUS == 16, "the command's radius bound equals the core's");

        // no mutating verb was added to the m04 tree
        CommandNode<CommandSourceStack> m04 = dispatcher.getRoot().getChild("m04");
        Set<String> verbs = m04.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
        ok(verbs.equals(Set.of("status", "capture", "preview-auto", "preview", "observe")), "m04 verbs are exactly the read-only set: " + verbs);

        // the observe branch never reaches C1/C2 motion or inventory commands
        String runtime = java.nio.file.Files.readString(java.nio.file.Path.of(
                java.nio.file.Files.exists(java.nio.file.Path.of("src/main/java/io/github/sirp00m/npcortex/CPlusM04Runtime.java"))
                        ? "src/main/java/io/github/sirp00m/npcortex/CPlusM04Runtime.java"
                        : "forge-mod/src/main/java/io/github/sirp00m/npcortex/CPlusM04Runtime.java"));
        String observe = runtime.substring(runtime.indexOf("private static int observe("), runtime.indexOf("private static int preview("));
        for (String forbidden : new String[]{"C2AgentRuntime", "C1P1MotionRuntime", "C1P1InventoryRuntime", "setBlock", "destroyBlock", "useItemOn"})
            ok(!observe.contains(forbidden), "observe handler does not touch " + forbidden);
        ok(observe.contains("CommandOrigin.classify(source)") && !observe.contains("hasPermission") && !observe.contains("isDirect"),
                "origin is only reported, never used to decide");

        System.out.println("PASS CPlusM04ObserveCommandSelfTest checks=" + checks + "; Brigadier parse only, nothing executed against a world");
    }
}
