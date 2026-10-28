package io.github.sirp00m.npcortex;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** G2 OFFLINE experiments on the real Forge 1.20.1 {@code CommandSourceStack} / Brigadier classes.
 *
 * They establish what the public API does when the vanilla runners (FunctionCommand, ServerFunctionManager, ExecuteCommand)
 * derive a stack - the derivations themselves were read from bytecode (javap, see docs/stage4/COMMAND_ORIGIN_INVESTIGATION.md).
 * A real ServerPlayer, chat packet, command block or data pack cannot exist offline, so "player-like" sources are FIXTURES.
 * Therefore this test proves the signal semantics and the classifier's logic, NOT that origin attestation works in a live game. */
public final class CommandOriginSelfTest {
    private static int checks;
    private static void ok(boolean pass, String message) { checks++; if (!pass) throw new AssertionError(message); }

    private static class Recording implements CommandSource {
        int delivered;
        @Override public void sendSystemMessage(Component message) { delivered++; }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
    private static final class PlayerLike extends Recording {}
    private static final class ServerLike extends Recording {}
    private static final class BlockLike extends Recording {}

    private static CommandSourceStack stack(CommandSource source, int permission) {
        return new CommandSourceStack(source, Vec3.ZERO, Vec2.ZERO, null, permission, "t", Component.literal("t"), null, null);
    }
    private static CommandOrigin.Kind classify(CommandSourceStack s) {
        return CommandOrigin.classify(s, x -> x instanceof PlayerLike, x -> x instanceof ServerLike);
    }
    private static final CommandSigningContext SIGNED = new CommandSigningContext.SignedArguments(Map.of());

    public static void main(String[] args) throws Exception {
        // ---- A. the public API surface that exists (reflection on the real class)
        Field source = CommandSourceStack.class.getField("source");
        ok(Modifier.isPublic(source.getModifiers()) && Modifier.isFinal(source.getModifiers()), "CommandSourceStack.source is a public final field");
        Field silent = CommandSourceStack.class.getDeclaredField("silent");
        ok(Modifier.isPrivate(silent.getModifiers()), "'silent' is private");
        ok(Arrays.stream(CommandSourceStack.class.getMethods()).noneMatch(m -> m.getName().toLowerCase().contains("silent")),
                "there is NO public accessor for 'silent' (so it must be probed by observation)");
        ok(Arrays.stream(CommandSourceStack.class.getMethods()).anyMatch(m -> m.getName().equals("getSigningContext")), "getSigningContext is public");

        // ---- B. what the vanilla derivations do to the signals
        Recording real = new PlayerLike();
        CommandSourceStack typed = stack(real, 2).withSigningContext(SIGNED);       // performChatCommand: SignedArguments
        ok(classify(typed) == CommandOrigin.Kind.DIRECT_PLAYER_CHAT, "player source + signed chat context + not suppressed => DIRECT_PLAYER_CHAT");
        ok(real.delivered == 0, "classifying never delivers anything to the real source (probe copy only)");

        CommandSourceStack fn = typed.withSuppressedOutput().withMaximumPermission(2);   // FunctionCommand.runFunction
        ok(fn != typed, "withSuppressedOutput/withMaximumPermission derive a NEW stack");
        ok(fn.source == real, "a function started by a player keeps the PLAYER as source");
        ok(fn.getSigningContext() == SIGNED, "...and keeps the signing context: signing alone cannot tell a function from typed chat");
        ok(fn.hasPermission(2), "...with permission raised to at least 2");
        ok(classify(fn) == CommandOrigin.Kind.PLAYER_SUPPRESSED_CHAIN, "silence is what separates the function from the typed command");
        ok(real.delivered == 0, "still nothing reached the real source");

        CommandSourceStack executeAs = typed.withEntity(null);                      // ExecuteCommand 'as'/'at' use withEntity
        ok(executeAs.source == real && executeAs.getSigningContext() == SIGNED && classify(executeAs) == CommandOrigin.Kind.DIRECT_PLAYER_CHAT,
                "execute-as changes the ENTITY only: source, signature and silence are unchanged (a human-typed /execute stays human-typed)");
        ok(classify(typed.withEntity(null).withPermission(4)) == CommandOrigin.Kind.DIRECT_PLAYER_CHAT, "permission derivations do not change the origin");

        CommandSourceStack injected = stack(real, 2);                                // code calling performPrefixedCommand(player stack)
        ok(injected.getSigningContext() == CommandSigningContext.ANONYMOUS, "a stack nobody signed has the ANONYMOUS context");
        ok(classify(injected) == CommandOrigin.Kind.PLAYER_NOT_FROM_CHAT_PACKET, "player source without a chat signature is NOT attested as typed");

        ServerLike server = new ServerLike();
        CommandSourceStack gameLoop = stack(server, 4).withPermission(2).withSuppressedOutput();   // ServerFunctionManager.getGameLoopSender
        ok(classify(gameLoop) == CommandOrigin.Kind.SERVER, "tick/load/scheduled functions run as the server");
        ok(classify(stack(server, 4)) == CommandOrigin.Kind.SERVER, "console / server source");
        ok(classify(stack(new BlockLike(), 2)) == CommandOrigin.Kind.OTHER, "command block / rcon / entity => OTHER");
        ok(classify(stack(new BlockLike(), 2).withSuppressedOutput()) == CommandOrigin.Kind.OTHER, "suppression never promotes a non-player source");

        // ---- C. control: the silence probe is meaningful (a normal stack really does deliver, a suppressed one does not)
        Recording control = new Recording();
        stack(control, 2).sendSuccess(() -> Component.literal("x"), false);
        ok(control.delivered == 1, "control: a normal stack delivers sendSuccess to its source");
        Recording quiet = new Recording();
        stack(quiet, 2).withSuppressedOutput().sendSuccess(() -> Component.literal("x"), false);
        ok(quiet.delivered == 0, "control: a suppressed stack swallows sendSuccess");
        ok(!CommandOrigin.isSilent(stack(new Recording(), 2)) && CommandOrigin.isSilent(stack(new Recording(), 2).withSuppressedOutput()), "isSilent");
        ok(CommandOrigin.isSilent(stack(CommandSource.NULL, 2).withSuppressedOutput()), "the probe works for a source that accepts nothing");

        // ---- D. Brigadier: which stack instance reaches a handler
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        AtomicReference<CommandSourceStack> seen = new AtomicReference<>();
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("probe").executes(c -> { seen.set(c.getSource()); return 1; }));
        LiteralArgumentBuilder<CommandSourceStack> executeNode = LiteralArgumentBuilder.<CommandSourceStack>literal("run")
                .redirect(dispatcher.getRoot(), c -> c.getSource().withPermission(3));   // any derivation (execute-as is withEntity; an offline fixture has no entity to swap)
        dispatcher.register(executeNode);
        dispatcher.execute("probe", typed);
        ok(seen.get() == typed, "a plain command receives the very stack instance it was dispatched with (identity can be tracked)");
        dispatcher.execute("run probe", typed);
        ok(seen.get() != typed && seen.get().source == real, "after an execute-style redirect the handler gets a DERIVED stack with the same source");

        // ---- E. NOT established offline (stated so the report cannot over-claim)
        // - that Commands.performCommand / Forge CommandEvent fire for a real client chat command (bytecode says yes: ServerGamePacketListenerImpl.
        //   performChatCommand -> Commands.performCommand -> CommandEvent) and do NOT fire for commands inside a function (CommandFunction$CommandEntry ->
        //   CommandDispatcher.execute);
        // - that a real ServerPlayer is the CommandSource of its own chat commands; that a command block, RCON or another mod behaves as modelled.
        System.out.println("PASS CommandOriginSelfTest checks=" + checks + "; real CommandSourceStack API, FIXTURE sources; origin provenance is still UNKNOWN until the gameplay experiment");
    }
}
