package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/** General reversible-construction material check, independent of the current goal adapter.
 * A newly mined goal log may be borrowed, but the task must return every borrowed
 * block during scaffold cleanup before final inventory verification.
 */
public final class BuildMaterials {
    private BuildMaterials() {}
    public static boolean usable(CompoundTag task, ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) return false;
        String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!ScaffoldPolicy.validMaterial(id)) return false;
        // Permit self-supplied logs only after the task has actually mined wood.
        // Never silently use starting goal inventory before any verified mining.
        return !id.equals(task.getString("item")) || task.getInt("mined")>0;
    }
    /** Prefer expendable non-goal resources; borrow goal logs only when necessary. */
    public static int preferredSlot(CompanionEntity npc,CompoundTag task) {
        int fallback=-1;
        for(int i=0;i<npc.bag().getContainerSize();i++) {
            ItemStack stack=npc.bag().getItem(i);
            if(!usable(task,stack)) continue;
            String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if(!id.equals(task.getString("item"))) return i;
            if(fallback<0) fallback=i;
        }
        return fallback;
    }
    public static int available(CompanionEntity npc, CompoundTag task) {
        int count=0;
        for (int i=0;i<npc.bag().getContainerSize();i++) {
            ItemStack stack=npc.bag().getItem(i);
            if (usable(task,stack)) count+=stack.getCount();
        }
        return count;
    }
}
