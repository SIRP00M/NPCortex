package io.github.sirp00m.npcortex.entity;

import io.github.sirp00m.npcortex.network.NpcNetwork;
import io.github.sirp00m.npcortex.network.OpenDialoguePacket;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
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

    private static final EntityDataAccessor<String> SKIN_ID = SynchedEntityData.defineId(
            CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> SLIM_MODEL = SynchedEntityData.defineId(
            CompanionEntity.class, EntityDataSerializers.BOOLEAN);

    @Nullable private UUID ownerUUID;
    private int lookAtOwnerTicks;
    private final SimpleContainer bag = new SimpleContainer(9);

    public SimpleContainer bag() { return bag; }
    public int storedSlots() {
        int slots = 0;
        for (int i = 0; i < bag.getContainerSize(); i++) {
            if (!bag.getItem(i).isEmpty()) slots++;
        }
        return slots;
    }

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
        entityData.define(SKIN_ID, "guide");
        entityData.define(SLIM_MODEL, false);
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
        entityData.set(SKIN_ID, id);
        setCustomName(Component.literal(id.substring(0, 1).toUpperCase() + id.substring(1)));
        setCustomNameVisible(true);
    }

    public String getSkinId() { return entityData.get(SKIN_ID); }
    public boolean isSlimModel() { return entityData.get(SLIM_MODEL); }

    /** Profile comes from trusted backend metadata, never from a client packet. */
    public void applyProfile(String displayName, String skinId, String model) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 80
                || skinId == null || !skinId.matches("[a-z][a-z0-9_-]{0,39}")) {
            throw new IllegalArgumentException("Invalid NPC appearance metadata");
        }
        if (!model.equals("slim") && !model.equals("wide")) {
            throw new IllegalArgumentException("Invalid NPC model");
        }
        entityData.set(SKIN_ID, skinId);
        entityData.set(SLIM_MODEL, model.equals("slim"));
        setCustomName(Component.literal(displayName));
        setCustomNameVisible(true);
    }

    /** Server-authoritative local skin selection; persists in the world save. */
    public void setAppearanceOverride(String skinId, boolean slim) {
        if (skinId == null || !skinId.matches("[a-z][a-z0-9_-]{0,39}"))
            throw new IllegalArgumentException("Invalid skin ID");
        entityData.set(SKIN_ID, skinId);
        entityData.set(SLIM_MODEL, slim);
    }

    @Nullable public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(@Nullable UUID uuid) { ownerUUID = uuid; }
    public boolean isFollowing() { return entityData.get(FOLLOWING); }
    public void setFollowing(boolean following) {
        entityData.set(FOLLOWING, following);
        if (!following) getNavigation().stop();
    }

    /** Temporary look action, independent of any LLM state. */
    public void lookAtPlayerFor(int ticks) {
        lookAtOwnerTicks = Math.max(0, Math.min(ticks, 200));
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide && lookAtOwnerTicks > 0) {
            --lookAtOwnerTicks;
            if (ownerUUID != null && level() instanceof net.minecraft.server.level.ServerLevel world) {
                ServerPlayer owner = world.getServer().getPlayerList().getPlayer(ownerUUID);
                if (owner != null && owner.level() == level() && distanceToSqr(owner) < 256.0D) {
                    getLookControl().setLookAt(owner, 30.0F, getMaxHeadXRot());
                }
            }
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            NpcNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    new OpenDialoguePacket(getId(), getCharacterId(), getName().getString()));
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
        tag.putString("NPCortexSkinId", getSkinId());
        tag.putBoolean("NPCortexSlimModel", isSlimModel());
        if (ownerUUID != null) tag.putUUID("NPCortexOwner", ownerUUID);
        ListTag stored = new ListTag();
        for (int i = 0; i < bag.getContainerSize(); ++i) {
            if (bag.getItem(i).isEmpty()) continue;
            CompoundTag entry = bag.getItem(i).save(new CompoundTag());
            entry.putByte("Slot", (byte)i);
            stored.add(entry);
        }
        tag.put("NPCortexBag", stored);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("NPCortexCharacterId")) {
            String id = tag.getString("NPCortexCharacterId");
            if (id.matches("[a-zA-Z0-9_-]{1,64}")) setCharacterId(id);
        }
        if (tag.contains("NPCortexSkinId")) {
            String skin = tag.getString("NPCortexSkinId");
            if (skin.matches("[a-z][a-z0-9_-]{0,39}")) entityData.set(SKIN_ID, skin);
        }
        entityData.set(SLIM_MODEL, tag.getBoolean("NPCortexSlimModel"));
        setFollowing(!tag.contains("NPCortexFollowing") || tag.getBoolean("NPCortexFollowing"));
        ownerUUID = tag.hasUUID("NPCortexOwner") ? tag.getUUID("NPCortexOwner") : null;
        bag.clearContent();
        ListTag saved = tag.getList("NPCortexBag", 10);
        for (int i = 0; i < saved.size(); ++i) {
            CompoundTag entry = saved.getCompound(i);
            int slot = entry.getByte("Slot") & 255;
            if (slot < bag.getContainerSize()) bag.setItem(slot, ItemStack.of(entry));
        }
    }
}
