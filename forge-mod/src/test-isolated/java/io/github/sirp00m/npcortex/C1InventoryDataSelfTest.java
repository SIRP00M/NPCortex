package io.github.sirp00m.npcortex;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Inventory;
import java.lang.reflect.Method;
import java.util.UUID;

/** Mocked NBT SaveData boundary ONLY, not Forge runtime or real-world crash tests. */
public final class C1InventoryDataSelfTest {
    private static int n;
    private static void check(boolean okay, String message) { n++; if (!okay) throw new AssertionError(message); }
    private static C1InventoryData load(CompoundTag root) throws Exception {
        Method loader = C1InventoryData.class.getDeclaredMethod("load", CompoundTag.class);
        loader.setAccessible(true);
        return (C1InventoryData) loader.invoke(null, root);
    }
    public static void main(String[] args) throws Exception {
        C1InventoryData data = new C1InventoryData();
        UUID yui = UUID.randomUUID(), hina = UUID.randomUUID(), t = UUID.randomUUID();
        Inventory inv = new Inventory();
        inv.selected = 3;
        ListTag slot = new ListTag();
        CompoundTag sword = new CompoundTag();
        sword.putString("item", "minecraft:diamond_sword");
        sword.putInt("damage", 45);
        sword.putString("tag", "enchanted");
        slot.add(sword);
        inv.load(slot);
        data.commit(yui, inv, t, "copyhand|sword", "fixture");
        check(data.dirty, "dirty on commit");
        check(data.find(yui).journal.revision() == 1, "commit revision");
        check(data.find(hina) == null, "other NPC unaffected");
        CompoundTag disk = data.save(new CompoundTag());
        C1InventoryData reopened = load(disk);
        check(reopened.blockage(yui) == null, "loaded without blockage");
        check(reopened.find(yui).journal.size() == 1, "receipt survives");
        check(reopened.find(yui).journal.lookup(t, "copyhand|sword") == C1InventoryJournal.Lookup.REPLAY, "replay after restart");
        Inventory loaded = new Inventory();
        reopened.restore(yui, loaded);
        check(loaded.selected == 3, "selected slot restored");
        check(loaded.save(new ListTag()).getCompound(0).getInt("damage") == 45, "damage retained");
        check(loaded.save(new ListTag()).getCompound(0).getString("tag").equals("enchanted"), "NBT retained");
        Inventory absent = new Inventory();
        reopened.restore(hina, absent);
        check(absent.save(new ListTag()).isEmpty(), "legacy absence empty inventory");
        CompoundTag corrupt = disk.copy();
        corrupt.getList("actors", 10).getCompound(0).putInt("schema", 200);
        C1InventoryData blocked = load(corrupt);
        check(blocked.blockage(yui) != null, "corrupt blocked");
        boolean failedClosed = false;
        try { blocked.restore(yui, new Inventory()); } catch (IllegalStateException expected) { failedClosed = true; }
        check(failedClosed, "corrupt cannot restore");
        // Save triggered by ANOTHER NPC cannot silently erase invalid item data.
        Inventory other = new Inventory();
        other.selected = 5;
        blocked.commit(hina, other, UUID.randomUUID(), "give|minecraft:bread|1", "ok");
        check(blocked.save(new CompoundTag()).getList("actors", 10).size() == 2, "corrupt record preserved on other actor save");
        check(blocked.save(new CompoundTag()).getList("actors", 10).getCompound(1).getInt("schema") == 200,
                "corrupt record NBT not overwritten");
        System.out.println("C1 Inventory SavedData mock PASS: " + n + " assertions");
    }
}
