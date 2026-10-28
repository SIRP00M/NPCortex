package io.github.sirp00m.npcortex;

import java.util.function.Predicate;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** DIAGNOSTIC classification of how a command reached the server, from PUBLIC CommandSourceStack API only
 * (see docs/stage4/COMMAND_ORIGIN_INVESTIGATION.md). It is NOT an authorization decision: provenance is still UNKNOWN
 * until the Owner runs the gameplay experiment, and nothing in the mod grants anything based on this value.
 *
 * Signals: {@code stack.source} (the CommandSource the command was started by; execute-as only changes the entity),
 * the signing context (SignedArguments only when a client chat-command packet created the stack), and "silence"
 * (data-pack functions run with suppressed output; the field is private, so it is probed by observation). */
final class CommandOrigin {
    enum Kind {
        /** Started by a player's own client chat command, output not suppressed. */
        DIRECT_PLAYER_CHAT,
        /** Player source but output suppressed: a data-pack function (or other suppressed chain) the player started. */
        PLAYER_SUPPRESSED_CHAIN,
        /** Player source, not suppressed, but no client signing context: injected by code, not a chat packet. */
        PLAYER_NOT_FROM_CHAT_PACKET,
        /** MinecraftServer: console, tick/load/scheduled functions. */
        SERVER,
        /** Command block, RCON, entity or anything else. */
        OTHER
    }

    private CommandOrigin() {}

    static Kind classify(CommandSourceStack stack, Predicate<CommandSource> isPlayerSource, Predicate<CommandSource> isServerSource) {
        CommandSource source = stack.source;
        if (isPlayerSource.test(source)) {
            if (isSilent(stack)) return Kind.PLAYER_SUPPRESSED_CHAIN;
            return stack.getSigningContext() instanceof CommandSigningContext.SignedArguments
                    ? Kind.DIRECT_PLAYER_CHAT : Kind.PLAYER_NOT_FROM_CHAT_PACKET;
        }
        return isServerSource.test(source) ? Kind.SERVER : Kind.OTHER;
    }

    static Kind classify(CommandSourceStack stack) {
        return classify(stack, s -> s instanceof net.minecraft.server.level.ServerPlayer,
                s -> s instanceof net.minecraft.server.MinecraftServer);
    }

    /** True when sendSuccess would be swallowed. A copy of the stack carries a probe source, so the real source never
     * receives anything; allowLogging=false avoids the admin broadcast. */
    static boolean isSilent(CommandSourceStack stack) {
        boolean[] delivered = {false};
        CommandSource probe = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { delivered[0] = true; }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return false; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        stack.withSource(probe).sendSuccess(Component::empty, false);
        return !delivered[0];
    }
}
