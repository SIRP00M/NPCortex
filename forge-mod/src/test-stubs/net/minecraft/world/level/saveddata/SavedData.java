package net.minecraft.world.level.saveddata;
import net.minecraft.nbt.CompoundTag;
public abstract class SavedData {
    public boolean dirty;
    public void setDirty() { dirty = true; }
    public abstract CompoundTag save(CompoundTag root);
}
