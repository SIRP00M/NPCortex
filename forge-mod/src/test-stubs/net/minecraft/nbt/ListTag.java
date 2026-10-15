package net.minecraft.nbt;
import java.util.ArrayList;
public class ListTag extends ArrayList<Tag> implements Tag {
    public byte getElementType() { return isEmpty() ? 0 : (byte) Tag.TAG_COMPOUND; }
    public CompoundTag getCompound(int i) { return (CompoundTag) get(i); }
    public ListTag copy() { ListTag out = new ListTag(); for (Tag tag : this) out.add(((CompoundTag)tag).copy()); return out; }
}
