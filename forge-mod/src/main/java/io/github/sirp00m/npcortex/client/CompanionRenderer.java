package io.github.sirp00m.npcortex.client;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

/** Phase 4 placeholder: vanilla default player skin, not a custom uploaded character skin. */
public final class CompanionRenderer extends MobRenderer<CompanionEntity, PlayerModel<CompanionEntity>> {
    public CompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.4F);
    }

    @Override
    public ResourceLocation getTextureLocation(CompanionEntity entity) {
        return DefaultPlayerSkin.getDefaultSkin(entity.getUUID());
    }
}
