package io.github.sirp00m.npcortex;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** G3-1 read-only operator inspection of the *latest C2 journal row*.
 * This row is NOT a correlated C+ terminal witness. Never releases attempts,
 * never starts C2 and never reconciles pre-existing HOLD after restart.
 */
final class CPlusE3G3Diagnostic {
    private CPlusE3G3Diagnostic() {}
    static int inspect(CommandSourceStack source, String name) {
        var server = source.getServer();
        if (!server.isSameThread()) return 0;
        var actor = C1IdentityData.get(server).find(name);
        if (actor == null) {
            source.sendFailure(Component.literal("NPCortex G3-1: unknown actor"));
            return 0;
        }
        C2ActionJournal.Row last = C2ActionJournal.get(server).find(actor.actorId());
        String detail = last == null ? "C2 journal EMPTY" :
                "C2 last=" + last.state() + " goal=" + last.goal() +
                " action=" + last.action() + " uncertain=" + last.uncertain();
        source.sendSuccess(() -> Component.literal(
                "NPCortex G3-1 CONTRACT_INSPECTION " + name + " " + detail +
                "; NOT correlated with C+ attempt; NO completion proof/ACK/fence release"), false);
        return 1;
    }
}
