package io.github.sirp00m.npcortex;

/** Pure JVM check of the fail-closed branch only; the real thread comparison needs a Forge server (gameplay PENDING). */
public final class ServerThreadSelfTest {
    public static void main(String[] ignored) {
        try {
            ServerThread.require(null, "probe");
            throw new AssertionError("null server must be rejected");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().equals("NPCortex probe requires the Minecraft server thread"))
                throw new AssertionError("unexpected message: " + expected.getMessage());
        }
        System.out.println("PASS ServerThread fail-closed on null server (1 case; thread comparison NOT covered)");
    }
}
