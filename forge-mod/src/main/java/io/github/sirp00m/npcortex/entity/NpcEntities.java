package io.github.sirp00m.npcortex.entity;

import io.github.sirp00m.npcortex.NPCortex;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class NpcEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, NPCortex.MOD_ID);

    public static final RegistryObject<EntityType<CompanionEntity>> COMPANION = ENTITY_TYPES.register(
            "companion", () -> EntityType.Builder.of(CompanionEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F).clientTrackingRange(10)
                    .build(NPCortex.MOD_ID + ":companion"));

    private NpcEntities() {}

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(COMPANION.get(), CompanionEntity.createAttributes().build()));
    }
}
