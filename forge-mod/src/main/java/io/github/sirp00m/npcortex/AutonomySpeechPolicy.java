package io.github.sirp00m.npcortex;

/** Decide which SINGLE visible warning to show for a sensed threat. */
public final class AutonomySpeechPolicy {
    public enum Output { AI, FALLBACK, SILENT }

    private AutonomySpeechPolicy() {}

    public static Output choose(boolean aiEnabled, boolean validAiReply, boolean fallbackEnabled) {
        if (aiEnabled && validAiReply) return Output.AI;
        return fallbackEnabled ? Output.FALLBACK : Output.SILENT;
    }
}
