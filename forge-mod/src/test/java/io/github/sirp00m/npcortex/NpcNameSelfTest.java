package io.github.sirp00m.npcortex;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** G2: wire format and client-side lifecycle of the visible NPC name map (pure JVM; the rendering itself is Owner-visual). */
public final class NpcNameSelfTest {
    private static int n;

    private static void check(boolean ok, String what) { n++; if (!ok) throw new AssertionError(what); }

    private static NpcNameCodec.Sync sync(boolean replace, Object... pairs) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) names.put((UUID) pairs[i], (String) pairs[i + 1]);
        return new NpcNameCodec.Sync(replace, names);
    }

    private static void rejects(byte[] data, String what) {
        try { NpcNameCodec.decode(data); throw new AssertionError("accepted: " + what); } catch (IOException expected) { n++; }
    }

    public static void main(String[] args) throws Exception {
        UUID reika = UUID.randomUUID(), mika = UUID.randomUUID(), player = UUID.randomUUID();

        // ---- codec
        NpcNameCodec.Sync both = sync(true, reika, "Reika", mika, "g5a02");
        check(NpcNameCodec.decode(NpcNameCodec.encode(both)).equals(both), "round trip (replace)");
        NpcNameCodec.Sync one = sync(false, reika, "Reika_2");
        check(NpcNameCodec.decode(NpcNameCodec.encode(one)).equals(one), "round trip (add)");
        check(NpcNameCodec.decode(NpcNameCodec.encode(sync(true))).names().isEmpty(), "empty replace is legal (a world with no NPCs)");
        Map<UUID, String> many = new LinkedHashMap<>();
        for (int i = 0; i < NpcNameCodec.MAX_ENTRIES; i++) many.put(UUID.randomUUID(), "npc" + i);
        check(NpcNameCodec.decode(NpcNameCodec.encode(new NpcNameCodec.Sync(true, many))).names().size() == NpcNameCodec.MAX_ENTRIES, "the maximum number of NPCs fits the packet cap");
        many.put(UUID.randomUUID(), "extra");
        try { NpcNameCodec.encode(new NpcNameCodec.Sync(true, many)); throw new AssertionError("one more than the cap"); } catch (IOException e) { n++; }
        for (String bad : new String[]{"", "a b", "x".repeat(17), "né", "Re;ika", "a\nb"}) {
            try { NpcNameCodec.encode(sync(true, reika, bad)); throw new AssertionError("encoded " + bad); } catch (IOException e) { n++; }
        }
        byte[] good = NpcNameCodec.encode(both);
        rejects(null, "null");
        rejects(new byte[0], "empty");
        rejects(java.util.Arrays.copyOf(good, good.length - 1), "truncated");
        rejects(java.util.Arrays.copyOf(good, good.length + 1), "trailing byte");
        byte[] version = good.clone(); version[0] = 9; rejects(version, "bad version");
        byte[] nameLen = good.clone(); nameLen[4 + 16] = 0; rejects(nameLen, "zero name length");
        byte[] huge = good.clone(); huge[2] = (byte) 0xFF; huge[3] = (byte) 0xFF; rejects(huge, "entry count beyond the cap");
        byte[] badChar = good.clone(); badChar[4 + 16 + 1] = ' '; rejects(badChar, "space inside a name");
        byte[] nonAscii = good.clone(); nonAscii[4 + 16 + 1] = (byte) 0xC3; rejects(nonAscii, "non-ASCII byte");
        // the same id twice
        byte[] dup = new byte[4 + 2 * (16 + 1 + 1)];
        dup[0] = 1; dup[1] = 1; dup[3] = 2;
        for (int e = 0; e < 2; e++) { int base = 4 + e * 18; dup[base + 16] = 1; dup[base + 17] = 'A'; }
        rejects(dup, "duplicate id");

        // ---- map lifecycle
        NpcNameMap map = new NpcNameMap();
        check(map.nameFor(reika) == null && map.nameFor(null) == null, "unknown UUID is not renamed");
        Set<UUID> changed = map.apply(sync(true, reika, "Reika", mika, "Mika"));
        check(changed.equals(Set.of(reika, mika)) && map.nameFor(reika).equals("Reika") && map.nameFor(mika).equals("Mika"), "login: replace installs the names");
        check(map.nameFor(player) == null, "a real player's UUID is never mapped");
        check(map.apply(sync(true, reika, "Reika", mika, "Mika")).isEmpty(), "dimension change re-sends the same map: nothing to refresh");
        UUID sakura = UUID.randomUUID();
        check(map.apply(sync(false, sakura, "Sakura")).equals(Set.of(sakura)) && map.size() == 3, "spawn: add keeps the others");
        check(map.nameFor(reika).equals("Reika"), "add does not disturb existing NPCs");
        changed = map.apply(sync(true, reika, "Reika"));
        check(changed.equals(Set.of(mika, sakura)) && map.nameFor(mika) == null && map.nameFor(sakura) == null, "replace drops NPCs that no longer exist and reports them for refresh");
        check(map.apply(sync(false, reika, "Reiko")).equals(Set.of(reika)) && map.nameFor(reika).equals("Reiko"), "a changed name is reported");
        check(map.clear().equals(Set.of(reika)) && map.size() == 0 && map.nameFor(reika) == null, "logout/world change clears everything");
        check(map.clear().isEmpty(), "clearing twice is harmless");
        map.apply(sync(true, mika, "Mika"));
        check(map.nameFor(mika).equals("Mika") && map.nameFor(reika) == null, "rejoin starts from a clean map");
        System.out.println("PASS NpcNameSelfTest (" + n + " assertions)");
    }
}
