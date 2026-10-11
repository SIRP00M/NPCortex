package io.github.sirp00m.npcortex.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

/** Player-shaped mob renderer with switchable wide/slim arms and local PNG skins. */
public final class CompanionRenderer extends MobRenderer<CompanionEntity, PlayerModel<CompanionEntity>> {
    private final PlayerModel<CompanionEntity> wideModel;
    private final PlayerModel<CompanionEntity> slimModel;

    public CompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.4F);
        wideModel = this.model;
        slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public void render(CompanionEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model = entity.isSlimModel() ? slimModel : wideModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CompanionEntity entity) {
        return SkinTextureCache.resolve(entity.getSkinId(), DefaultPlayerSkin.getDefaultSkin(entity.getUUID()));
    }
}
