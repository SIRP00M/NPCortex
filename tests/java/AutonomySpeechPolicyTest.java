import io.github.sirp00m.npcortex.AutonomySpeechPolicy;

public class AutonomySpeechPolicyTest {
    private static int checks = 0;
    private static void eq(AutonomySpeechPolicy.Output actual, AutonomySpeechPolicy.Output expected) {
        checks++;
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        var p = AutonomySpeechPolicy.Output.class;
        eq(AutonomySpeechPolicy.choose(true, true, true), AutonomySpeechPolicy.Output.AI);
        eq(AutonomySpeechPolicy.choose(true, true, false), AutonomySpeechPolicy.Output.AI);
        eq(AutonomySpeechPolicy.choose(true, false, true), AutonomySpeechPolicy.Output.FALLBACK);
        eq(AutonomySpeechPolicy.choose(true, false, false), AutonomySpeechPolicy.Output.SILENT);
        eq(AutonomySpeechPolicy.choose(false, false, true), AutonomySpeechPolicy.Output.FALLBACK);
        eq(AutonomySpeechPolicy.choose(false, false, false), AutonomySpeechPolicy.Output.SILENT);
        eq(AutonomySpeechPolicy.choose(false, true, true), AutonomySpeechPolicy.Output.FALLBACK);
        System.out.println("AutonomySpeechPolicy: " + checks + " assertions passed.");
    }
}
