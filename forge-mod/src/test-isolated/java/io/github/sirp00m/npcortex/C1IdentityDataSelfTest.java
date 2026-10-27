package io.github.sirp00m.npcortex;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.lang.reflect.Method;
import java.util.UUID;

/** G8: C1 identity NBT with the Character Profile mode fields. Mocked NBT only; not Forge runtime evidence. */
public final class C1IdentityDataSelfTest {
    private static int n;
    private static void check(boolean ok, String what) { n++; if (!ok) throw new AssertionError(what); }

    private static C1IdentityData load(CompoundTag root) throws Exception {
        Method loader = C1IdentityData.class.getDeclaredMethod("load", CompoundTag.class);
        loader.setAccessible(true);
        return (C1IdentityData) loader.invoke(null, root);
    }

    /** A save written by the PREVIOUS code: no profile_mode / preset keys. */
    private static CompoundTag legacyRoot(UUID world, UUID actor, UUID profile, String name) {
        CompoundTag root = new CompoundTag();
        root.putUUID("world_uuid", world);
        ListTag list = new ListTag();
        CompoundTag tag = new CompoundTag();
        tag.putString("character", name);
        tag.putUUID("actor_uuid", actor);
        tag.putUUID("profile_uuid", profile);
        tag.putString("profile_name", "N" + name + "_abc123");
        tag.putString("dimension", "minecraft:overworld");
        tag.putDouble("x", 1.5); tag.putDouble("y", 64.0); tag.putDouble("z", -3.25);
        tag.putFloat("yaw", 90f); tag.putFloat("pitch", 1f); tag.putFloat("head_yaw", 91f); tag.putFloat("body_yaw", 92f);
        tag.putBoolean("active", true);
        list.add(tag);
        root.put("actors", list);
        return root;
    }

    private static CompoundTag first(CompoundTag root) { return root.getList("actors", 10).getCompound(0); }

    public static void main(String[] args) throws Exception {
        UUID world = UUID.randomUUID(), actor = UUID.randomUUID(), profile = UUID.randomUUID();

        // ---- legacy save: loads as legacy, is not dirtied, keeps all three UUIDs, and stays legacy on save
        C1IdentityData old = load(legacyRoot(world, actor, profile, "Yui"));
        C1IdentityData.Entry yui = old.find("yui");
        check(yui != null && yui.legacyProfile() && yui.profileMode().isEmpty() && yui.preset().isEmpty(), "legacy entry has no mode");
        check(!old.dirty, "loading a legacy save does not mark it dirty");
        check(yui.actorId().equals(actor) && yui.profileId().equals(profile) && old.worldId().equals(world), "world, actor and GameProfile ids preserved");
        check(yui.profileName().equals("NYui_abc123"), "GameProfile name preserved");
        CompoundTag again = old.save(new CompoundTag());
        check(first(again).getString("profile_mode").isEmpty() && first(again).getString("preset").isEmpty(), "save never upgrades a legacy NPC");
        C1IdentityData reloaded = load(again);
        check(reloaded.find("Yui").legacyProfile() && reloaded.find("Yui").actorId().equals(actor), "legacy round trip");

        // ---- new NPCs: each mode persists and round-trips
        C1IdentityData store = new C1IdentityData();
        C1IdentityData.Entry custom = store.ensure("Reika", "minecraft:overworld", 0, 64, 0, 0f, "custom", "");
        C1IdentityData.Entry plain = store.ensure("Guide", "minecraft:overworld", 1, 64, 1, 0f, "default", "");
        C1IdentityData.Entry preset = store.ensure("Mika", "minecraft:overworld", 2, 64, 2, 0f, "preset", "yui");
        check(custom.profileMode().equals("custom") && !custom.legacyProfile(), "custom mode");
        check(plain.profileMode().equals("default") && !plain.legacyProfile(), "a NEW NPC called Guide is NOT legacy and cannot adopt the Guide pack");
        check(preset.profileMode().equals("preset") && preset.preset().equals("yui"), "preset mode keeps the pack id");
        check(!custom.actorId().equals(plain.actorId()) && !custom.profileId().equals(custom.actorId()), "actor id and GameProfile id are distinct");
        C1IdentityData round = load(store.save(new CompoundTag()));
        check(round.find("reika").profileMode().equals("custom") && round.find("guide").profileMode().equals("default"), "modes round trip");
        check(round.find("mika").preset().equals("yui"), "preset round trip");
        check(round.find("reika").actorId().equals(custom.actorId()) && round.find("reika").profileId().equals(custom.profileId()), "ids round trip");
        check(round.worldId().equals(store.worldId()), "world id round trip");

        // ---- an existing NPC never changes its mode or ids
        C1IdentityData.Entry same = store.ensure("REIKA", "minecraft:the_nether", 9, 70, 9, 45f, "default", "");
        check(same.profileMode().equals("custom") && same.actorId().equals(custom.actorId()) && same.profileId().equals(custom.profileId()), "re-ensure keeps mode and identity");
        check(same.dimension().equals("minecraft:the_nether"), "location still updates");
        C1IdentityData.Entry moved = same.withLocation("minecraft:overworld", 1, 2, 3, C1RotationState.legacy(0), false);
        check(moved.profileMode().equals("custom") && !moved.active(), "withLocation carries the mode");
        check(moved.withActive(true).profileMode().equals("custom"), "withActive carries the mode");

        // ---- invalid selections are refused, unreadable saved values are flagged (never guessed)
        String[][] bads = {{"bogus", ""}, {"custom", "yui"}, {"default", "yui"}, {"preset", ""}, {"preset", "../yui"},
                {"preset", "Yui"}, {"preset", "x".repeat(41)}, {"", ""}};
        int i = 0;
        for (String[] bad : bads) {
            try {
                store.ensure("Bad" + (i++), "minecraft:overworld", 0, 0, 0, 0f, bad[0], bad[1]);
                throw new AssertionError("accepted " + String.join("/", bad));
            } catch (IllegalArgumentException expected) { n++; }
        }
        CompoundTag broken = legacyRoot(world, actor, profile, "Rei");
        first(broken).putString("profile_mode", "weird");
        check(load(broken).find("Rei").profileMode().equals(C1IdentityData.MODE_INVALID), "unreadable mode is INVALID, not legacy");
        CompoundTag badPreset = legacyRoot(world, actor, profile, "Rei2");
        first(badPreset).putString("profile_mode", "preset");
        first(badPreset).putString("preset", "../etc");
        check(load(badPreset).find("Rei2").profileMode().equals(C1IdentityData.MODE_INVALID), "unsafe preset id is INVALID");
        CompoundTag presetOnly = legacyRoot(world, actor, profile, "Rei3");
        first(presetOnly).putString("preset", "yui");
        check(load(presetOnly).find("Rei3").profileMode().equals(C1IdentityData.MODE_INVALID), "preset without a mode is INVALID");

        // ---- names / ids
        check(C1IdentityData.validName("g5n02") && C1IdentityData.validName("Reika_2") && !C1IdentityData.validName("a b") && !C1IdentityData.validName(""), "name rules unchanged");
        check(C1IdentityData.backendWorldName(world).equals("NPCortex-C1-" + world), "backend world name contract");
        check(new C1IdentityData().all().isEmpty() && load(new CompoundTag()).dirty, "missing world uuid still forces a new id");
        System.out.println("PASS C1IdentityDataSelfTest (" + n + " assertions)");
    }
}
