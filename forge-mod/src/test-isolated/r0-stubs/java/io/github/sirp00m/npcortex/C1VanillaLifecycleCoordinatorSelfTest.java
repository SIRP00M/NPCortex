package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/** Compiles the real coordinator against isolated Java-17 fakes, not Forge. */
public final class C1VanillaLifecycleCoordinatorSelfTest {
    static final List<String> EVENTS = new ArrayList<>();
    private static int assertions;
    private static void eq(String expected, String actual) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " got=" + actual);
    }
    private static void yes(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("unexpected false");
    }
    private static String events() { return String.join(",", EVENTS); }
    private static void reset() { EVENTS.clear(); }

    public static void main(String[] unused) {
        MinecraftServer server = new MinecraftServer();
        C1PlayerAvatar avatar = new C1PlayerAvatar(server);
        C1P0Runtime.current = avatar;
        C1P1CLifecycleRuntime.restorable = true;
        yes(C1VanillaLifecycleCoordinator.mayRunWorldTick(avatar));
        C1P1CLifecycleRuntime.restorable = false;
        yes(!C1VanillaLifecycleCoordinator.mayRunWorldTick(avatar));
        C1P1CLifecycleRuntime.restorable = true;
        avatar.removed = true;
        yes(!C1VanillaLifecycleCoordinator.mayRunWorldTick(avatar));
        avatar.removed = false;
        C1P0Runtime.current = new C1PlayerAvatar(server);
        yes(!C1VanillaLifecycleCoordinator.mayRunWorldTick(avatar));
        C1P0Runtime.current = avatar;

        reset(); C1VanillaLifecycleCoordinator.beforeSpawn(server, avatar);
        eq("inventoryRestore,healthRestore", events());
        reset(); C1VanillaLifecycleCoordinator.afterSpawn(avatar);
        eq("motionSpawn", events());
        reset(); C1VanillaLifecycleCoordinator.afterWorldTick(avatar);
        eq("idleSupport", events());
        avatar.removed = true;
        reset(); C1VanillaLifecycleCoordinator.afterWorldTick(avatar);
        eq("", events());
        avatar.removed = false;
        reset(); C1VanillaLifecycleCoordinator.onHealthChanged(avatar);
        C1VanillaLifecycleCoordinator.onFatalDamage(avatar, new DamageSource());
        eq("healthChanged,fatalDamage", events());
        reset(); C1VanillaLifecycleCoordinator.onVanillaKnockback(avatar, new Vec3(1, 0, 0));
        eq("vanillaKnockback", events());
        reset(); C1VanillaLifecycleCoordinator.beforeDetach(server, avatar);
        eq("motionDetach", events());
        reset(); C1VanillaLifecycleCoordinator.onServerTick(new TickEvent.ServerTickEvent());
        eq("networkTick,motionTick,deathTick,c2Tick,followTick", events());
        reset(); C1VanillaLifecycleCoordinator.onServerStopping(new ServerStoppingEvent());
        eq("followStop,networkStop,deathStop,c2Stop,g3Stop", events());
        System.out.println("PASS R0 Coordinator Test Doubles: " + assertions + " assertions");
    }
}

class C1PlayerAvatar {
    final MinecraftServer server;
    final UUID uuid = UUID.randomUUID();
    boolean removed;
    C1PlayerAvatar(MinecraftServer server) { this.server = server; }
    MinecraftServer getServer() { return server; }
    UUID getUUID() { return uuid; }
    boolean isRemoved() { return removed; }
}
class C1P0Runtime {
    static C1PlayerAvatar current;
    static C1PlayerAvatar liveAvatar(UUID id) { return current != null && current.getUUID().equals(id) ? current : null; }
    static void onServerTick(TickEvent.ServerTickEvent e) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("networkTick"); }
    static void onServerStopping(ServerStoppingEvent e) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("networkStop"); }
}
class C1P1MotionRuntime {
    static void onVanillaKnockback(C1PlayerAvatar a, Vec3 v) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("vanillaKnockback"); }
    static void onVanillaWorldTick(C1PlayerAvatar a) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("idleSupport"); }
    static void afterSpawn(C1PlayerAvatar a) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("motionSpawn"); }
    static void beforeDespawn(MinecraftServer s, C1PlayerAvatar a) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("motionDetach"); }
    static void onTick(TickEvent.ServerTickEvent e) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("motionTick"); }
}
class C1P1CLifecycleRuntime {
    static boolean restorable = true;
    static boolean mayRestore(MinecraftServer s, UUID id) { return restorable; }
    static void onAvatarConstructed(MinecraftServer s, C1PlayerAvatar a) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("healthRestore"); }
    static void onHealthChanged(C1PlayerAvatar a) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("healthChanged"); }
    static void onFatalDamage(C1PlayerAvatar a, DamageSource d) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("fatalDamage"); }
    static void onTick(TickEvent.ServerTickEvent e) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("deathTick"); }
    static void onServerStopping(ServerStoppingEvent e) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("deathStop"); }
}
class C1P1InventoryRuntime {
    static void restore(MinecraftServer s, C1PlayerAvatar a) { C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("inventoryRestore"); }
}
class C2AgentRuntime {
    static void onServerTick(TickEvent.ServerTickEvent e){C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("c2Tick");}
    static void beforeServerStop(MinecraftServer s){C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("c2Stop");}
}
class C2FollowRuntime {
    static void onServerTick(TickEvent.ServerTickEvent e){C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("followTick");}
    static void beforeServerStop(MinecraftServer s){C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("followStop");}
}
class CPlusE3G3Runtime {
    static void onServerStopping(ServerStoppingEvent e){C1VanillaLifecycleCoordinatorSelfTest.EVENTS.add("g3Stop");}
}
