package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Atomic bag-only crafting for the explicitly registered alpha recipes. */
public final class WorldCrafting {
    private WorldCrafting() {}

    public static String craft(CompanionEntity npc, String outputId) {
        WorldInteractionPolicy.Recipe recipe = WorldInteractionPolicy.recipe(outputId);
        if (recipe == null) return "[Action blocked: crafting recipe not supported.]";
        // Preflight in a copy. On failure, no NPC inventory changes occur.
        SimpleContainer staging = new SimpleContainer(npc.bag().getContainerSize());
        for (int i = 0; i < staging.getContainerSize(); i++)
            staging.setItem(i, npc.bag().getItem(i).copy());
        for (Map.Entry<String, Integer> ingredient : recipe.ingredients().entrySet()) {
            int needed = ingredient.getValue();
            for (int i = 0; i < staging.getContainerSize() && needed > 0; i++) {
                ItemStack stack = staging.getItem(i);
                if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
                        .equals(ingredient.getKey())) continue;
                int removed = Math.min(needed, stack.getCount());
                stack.shrink(removed);
                if (stack.isEmpty()) staging.setItem(i, ItemStack.EMPTY);
                needed -= removed;
            }
            if (needed > 0) return "[Action failed: not enough " + ingredient.getKey() + " in NPC bag.]";
        }
        ResourceLocation id = ResourceLocation.tryParse(outputId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id))
            return "[Action failed: unknown crafting output.]";
        ItemStack output = new ItemStack(BuiltInRegistries.ITEM.get(id), recipe.output());
        if (!staging.addItem(output).isEmpty())
            return "[Action failed: NPC bag lacks room for crafted output.]";
        for (int i = 0; i < staging.getContainerSize(); i++)
            npc.bag().setItem(i, staging.getItem(i).copy());
        return "[Action confirmed: crafted " + recipe.output() + " " + outputId + " into NPC bag.]";
    }
}
