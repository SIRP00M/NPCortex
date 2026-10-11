package io.github.sirp00m.npcortex.entity;

import io.github.sirp00m.npcortex.network.NpcNetwork;
import io.github.sirp00m.npcortex.network.OpenDialoguePacket;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

/** Server-owned, persistent, generic character. AI is not run on entity ticks. */
public class CompanionEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> CHARACTER_ID = SynchedEntityData.defineId(
            CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> FOLLOWING = SynchedEntityData.defineId(
            CompanionEntity.class, EntityDataSerializers.BOOLEAN);

    @Nullable private UUID ownerUUID;

    public CompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(CHARACTER_ID, "guide");
        entityData.define(FOLLOWING, true);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new FollowOwnerGoal(this));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    public String getCharacterId() { return entityData.get(CHARACTER_ID); }
    public void setCharacterId(String id) {
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid character ID");
        entityData.set(CHARACTER_ID, id);
        setCustomName(Component.literal(id.substring(0, 1).toUpperCase() + id.substring(1)));
        setCustomNameVisible(true);
    }

    @Nullable public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(@Nullable UUID uuid) { ownerUUID = uuid; }
    public boolean isFollowing() { return entityData.get(FOLLOWING); }
    public void setFollowing(boolean following) {
        entityData.set(FOLLOWING, following);
        if (!following) getNavigation().stop();
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            NpcNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    new OpenDialoguePacket(getId(), getCharacterId()));
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    @Override
    public boolean canBeLeashed(Player player) { return false; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("NPCortexCharacterId", getCharacterId());
        tag.putBoolean("NPCortexFollowing", isFollowing());
        if (ownerUUID != null) tag.putUUID("NPCortexOwner", ownerUUID);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("NPCortexCharacterId")) {
            String id = tag.getString("NPCortexCharacterId");
            if (id.matches("[a-zA-Z0-9_-]{1,64}")) setCharacterId(id);
        }
        setFollowing(!tag.contains("NPCortexFollowing") || tag.getBoolean("NPCortexFollowing"));
        ownerUUID = tag.hasUUID("NPCortexOwner") ? tag.getUUID("NPCortexOwner") : null;
    }
}
