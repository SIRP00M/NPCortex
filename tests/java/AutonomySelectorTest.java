import io.github.sirp00m.npcortex.AutonomySelector;
import java.util.List;
import java.util.UUID;

public final class AutonomySelectorTest {
    record Dummy(UUID uuid, String character) {}
    static int checks = 0;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("AutonomySelector test " + checks + " failed");
    }
    public static void main(String[] args) {
        var first = new Dummy(UUID.fromString("00000000-0000-0000-0000-000000000001"), "yui");
        var second = new Dummy(UUID.fromString("00000000-0000-0000-0000-000000000002"), "yui");
        var third = new Dummy(UUID.fromString("00000000-0000-0000-0000-000000000003"), "ai");
        var one = List.of(first, third);
        var all = List.of(first, second, third);
        check(AutonomySelector.resolve(one, "ai", Dummy::character, Dummy::uuid).value() == third);
        check(AutonomySelector.resolve(one, "YUI", Dummy::character, Dummy::uuid).value() == first);
        check(AutonomySelector.resolve(all, "yui", Dummy::character, Dummy::uuid).ambiguous());
        check(AutonomySelector.resolve(all, "yui", Dummy::character, Dummy::uuid).value() == null);
        check(AutonomySelector.resolve(all, first.uuid().toString(), Dummy::character, Dummy::uuid).value() == first);
        check(AutonomySelector.resolve(all, second.uuid().toString(), Dummy::character, Dummy::uuid).value() == second);
        check(AutonomySelector.resolve(one, second.uuid().toString(), Dummy::character, Dummy::uuid).value() == null);
        check(AutonomySelector.resolve(one, "missing", Dummy::character, Dummy::uuid).value() == null);
        check(AutonomySelector.resolve(one, "", Dummy::character, Dummy::uuid).value() == null);
        check(AutonomySelector.resolve(one, null, Dummy::character, Dummy::uuid).value() == null);
        System.out.println("AutonomySelector: " + checks + " checks passed.");
    }
}
