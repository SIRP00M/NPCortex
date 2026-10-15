package net.minecraft.world.entity.player;
import net.minecraft.nbt.ListTag;
public class Inventory {
    public int selected;
    private ListTag inventory = new ListTag();
    public void load(ListTag tag) { inventory = tag.copy(); }
    public ListTag save(ListTag list) { list.addAll(inventory.copy()); return list; }
}
