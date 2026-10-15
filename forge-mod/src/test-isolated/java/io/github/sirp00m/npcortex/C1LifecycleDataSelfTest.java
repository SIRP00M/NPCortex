package io.github.sirp00m.npcortex;
import net.minecraft.nbt.CompoundTag;
import java.lang.reflect.Method;
import java.util.UUID;

/** Isolated data-boundary verification. NOT Forge build or crash-recovery proof. */
public final class C1LifecycleDataSelfTest {
    private static int n;
    private static void test(boolean value, String message) { n++; if (!value) throw new AssertionError(message); }
    private static C1LifecycleData reopen(CompoundTag tag) throws Exception {
        Method m = C1LifecycleData.class.getDeclaredMethod("load", CompoundTag.class);
        m.setAccessible(true);
        return (C1LifecycleData)m.invoke(null, tag);
    }
    public static void main(String[] args) throws Exception {
        UUID yui = UUID.randomUUID(), hina = UUID.randomUUID(), ai = UUID.randomUUID();
        C1LifecycleData data = new C1LifecycleData();
        test(data.state(yui).phase() == C1LifecyclePhase.ALIVE, "legacy defaults alive");
        test(data.state(yui).health() == 20, "legacy full health");
        test(C1LifecyclePhase.ALIVE.canRestoreAutomatically(), "alive auto restore");
        test(!C1LifecyclePhase.DEAD.canRestoreAutomatically(), "dead no auto restore");
        test(!C1LifecyclePhase.TRANSITION.canRestoreAutomatically(), "transition no auto restore");
        data.update(yui, C1LifecyclePhase.ALIVE, 14);
        test(data.dirty, "health dirty");
        test(data.state(yui).revision() == 1, "health revision");
        data.update(yui, C1LifecyclePhase.ALIVE, 14);
        test(data.state(yui).revision() == 1, "no redundant save");
        data.update(yui, C1LifecyclePhase.DEAD, 0);
        test(data.state(yui).revision() == 2, "death revision");
        test(data.state(yui).phase().canRespawn(), "only dead respawn");
        data.update(hina, C1LifecyclePhase.TRANSITION, 19);
        test(!data.state(hina).phase().canRespawn(), "unfinished dimension not respawned");
        C1LifecycleData next = reopen(data.save(new CompoundTag()));
        test(next.state(yui).phase() == C1LifecyclePhase.DEAD, "save death");
        test(next.state(yui).health() == 0, "save zero health");
        test(next.state(yui).revision() == 2, "save revision");
        test(next.state(hina).phase() == C1LifecyclePhase.TRANSITION, "save transition");
        test(next.state(ai).phase() == C1LifecyclePhase.ALIVE, "actor isolation");
        next.update(yui, C1LifecyclePhase.ALIVE, 20);
        test(next.state(yui).revision() == 3, "respawn revision");
        test(next.state(hina).phase() == C1LifecyclePhase.TRANSITION, "respawn isolated");
        CompoundTag corrupt = next.save(new CompoundTag());
        corrupt.getList("actors", 10).getCompound(0).putString("phase", "GIBBERISH");
        C1LifecycleData blocked = reopen(corrupt);
        boolean foundBlocked = false;
        for (UUID uid : new UUID[]{yui, hina}) foundBlocked |= blocked.blocked(uid);
        test(foundBlocked, "unknown phase fail-closed");
        test(blocked.save(new CompoundTag()).getList("actors", 10).size() == 2, "preserve corrupt raw record");
        test(C1LifecyclePhase.decode("GIBBERISH") == C1LifecyclePhase.TRANSITION, "unknown decoding fail-closed");
        boolean threw = false;
        try { new C1LifecycleData.State(C1LifecyclePhase.ALIVE, Float.NaN, 0); }
        catch (IllegalArgumentException ex) { threw = true; }
        test(threw, "reject NaN health");
        System.out.println("C1 P1-C lifecycle SavedData test doubles PASS: " + n + " assertions");
    }
}
