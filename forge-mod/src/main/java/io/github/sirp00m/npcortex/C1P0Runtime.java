package io.github.sirp00m.npcortex;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opt-in C1-P0 ServerPlayer feasibility rig; it intentionally does not use
 * PlayerList.placeNewPlayer, FakePlayer or any PathfinderMob. No AI/actions.
 * P0 is a candidate until the client/server tracking gates are measured.
 */
final class C1P0Runtime {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<UUID, C1PlayerAvatar> LIVE = new HashMap<>();
    // Viewer-scoped sessions; never persisted and never used as authority for world state.
    private static final Map<UUID, Map<UUID, C1P0SyncState>> TRACKED = new HashMap<>();

    private C1P0Runtime() {}

    static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (server.overworld() == null) return;
        C1IdentityData identities = C1IdentityData.get(server);
        for (C1IdentityData.Entry entry : identities.all()) {
            if (!entry.active()) continue;
            if (!C1P1CLifecycleRuntime.mayRestore(server, entry.profileId())) {
                LOG.warn("NPCortex C1-P1-C restore held (DEAD/TRANSITION/corrupt) npc={}", entry.character());
                continue;
            }
            try {
                spawnExisting(server, entry);
            } catch (RuntimeException ex) {
                // Leave saved state intact for explicit retry/debugging.
                LOG.error("NPCortex C1-P0 restore failed for {} (not deleting saved identity)", entry.character(), ex);
            }
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        C1P1MotionRuntime.beforeServerStop(event.getServer());
        // Persistent desired state stays saved. No logout simulation or world mutations.
        LIVE.clear();
        TRACKED.clear();
    }

    static String spawn(MinecraftServer server, ServerPlayer commander, String name) {
        return spawn(server, commander, name, C1IdentityData.MODE_DEFAULT, "");
    }

    /** Local-only: never contacts the backend. {@code profileMode}/{@code preset} apply to NEW NPCs only. */
    static String spawn(MinecraftServer server, ServerPlayer commander, String name, String profileMode, String preset) {
        ServerThread.require(server, "C1 spawn");
        if (!C1IdentityData.validProfileSelection(profileMode, preset))
            return "Invalid profile selection: use default, custom or preset <character pack id>.";
        if (!C1IdentityData.validName(name)) return "Invalid name: use 1-16 ASCII letters, digits or underscore.";
        C1IdentityData store = C1IdentityData.get(server);
        C1IdentityData.Entry prior = store.find(name);
        if (prior != null && !C1P1CLifecycleRuntime.mayRestore(server, prior.profileId())) {
            return "Lifecycle is DEAD/TRANSITION or corrupt: use c1 lifecycle / respawn; do not force spawn";
        }
        if (prior != null && LIVE.containsKey(prior.profileId())) {
            C1PlayerAvatar existing = LIVE.get(prior.profileId());
            if (!existing.isRemoved()) return "Already spawned: " + prior.character() + " / " + prior.profileId();
            LIVE.remove(prior.profileId());
        }
        // Restrict P0 to a single nearby, already-loaded test position.
        double x = commander.getX() + 1.5;
        double y = commander.getY();
        double z = commander.getZ() + 1.5;
        String dimension = commander.serverLevel().dimension().location().toString();
        C1IdentityData.Entry candidate = prior == null ? null :
                prior.withLocation(dimension, x, y, z,
                        C1RotationState.legacy(commander.getYRot()), true);
        // Add the avatar first, then persist the ACTIVE intent. If spawn fails,
        // the durable record cannot become a phantom "active" actor.
        UUID spawnedProfile = prior == null ? null : prior.profileId();
        try {
            if (prior == null) {
                // Persisted profile name is generated exactly once by ensure().
                C1IdentityData.Entry saved = store.ensure(name, dimension, x, y, z, commander.getYRot(), profileMode, preset);
                spawnedProfile = saved.profileId();
                spawnExisting(server, saved);
                return "Spawn attempted: " + saved.character() + " / " + saved.profileId()
                        + " (verify rendering on client) | " + profileSummary(store, saved, profileMode);
            }
            spawnExisting(server, candidate);
            store.ensure(name, dimension, x, y, z, commander.getYRot(), profileMode, preset);
            return "Spawn attempted: " + prior.character() + " / " + prior.profileId()
                    + " (verify rendering on client) | " + profileSummary(store, prior, profileMode);
        } catch (RuntimeException ex) {
            LOG.error("NPCortex C1-P0 spawn failed for {}", name, ex);
            // For new records, the identity remains stable for retry but should
            // NOT be restored without manual intervention after failure.
            // Deactivate only the record whose profile this attempt spawned.
            C1IdentityData.Entry current = store.find(name);
            if (current != null && current.profileId().equals(spawnedProfile)) store.deactivate(name);
            return "Spawn FAILED: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    /**
     * What the operator needs to know right after a local spawn. C1 creation never implies that the
     * Character Profile exists or that the backend (C2) is usable.
     */
    static String profileSummary(C1IdentityData store, C1IdentityData.Entry entry, String requestedMode) {
        String ids = "world_id=" + store.worldId() + " actor_id=" + entry.actorId() + " dimension=" + entry.dimension();
        String note = switch (entry.profileMode()) {
            case C1IdentityData.MODE_DEFAULT -> "Character Profile: NOT created yet (mode=default); the backend creates the generic default profile at the first C2 registration (the NPC name does NOT select a Character Pack; use 'spawn <name> preset <pack>' for that). Backend not contacted.";
            case C1IdentityData.MODE_CUSTOM -> "Character Profile: NOT created yet (mode=custom); register it in the NPCortex Manager (Pause menu) or scripts/register-npc-profile.ps1 BEFORE using C2. Backend not contacted.";
            case C1IdentityData.MODE_PRESET -> "Character Profile: NOT created yet (mode=preset:" + entry.preset() + "); copied from that Character Pack at the first C2 registration. Backend not contacted.";
            case C1IdentityData.MODE_INVALID -> "Character Profile: saved mode is unreadable; C2 registration will refuse until this NPC is recreated.";
            default -> "Character Profile: legacy pack '" + entry.character().toLowerCase(java.util.Locale.ROOT) + "' (name-based, not verified with the backend).";
        };
        boolean differs = !entry.profileMode().equals(requestedMode) && !entry.legacyProfile();
        return note + (differs ? " (existing NPC: requested mode ignored)" : "") + " " + ids;
    }

    static void spawnExisting(MinecraftServer server, C1IdentityData.Entry entry) {
        if (LIVE.containsKey(entry.profileId()) && !LIVE.get(entry.profileId()).isRemoved()) return;
        ResourceLocation id = ResourceLocation.tryParse(entry.dimension());
        if (id == null) throw new IllegalArgumentException("Invalid dimension " + entry.dimension());
        ResourceKey<Level> target = ResourceKey.create(Registries.DIMENSION, id);
        ServerLevel level = server.getLevel(target);
        if (level == null) throw new IllegalStateException("Dimension is not loaded: " + entry.dimension());
        if (level.getEntity(entry.profileId()) != null) {
            throw new IllegalStateException("World already contains profile UUID " + entry.profileId());
        }
        // Name first: clients must know the logical name before the player-info/spawn packets create the entity.
        NpcNameNetwork.announce(server, entry);
        C1PlayerAvatar avatar = new C1PlayerAvatar(server, level,
                new GameProfile(entry.profileId(), entry.profileName()));
        // Restore full Vanilla ItemStacks before ChunkMap can track this avatar.
        C1VanillaLifecycleCoordinator.beforeSpawn(server, avatar);
        C1RotationState rotation = entry.rotation();
        avatar.moveTo(entry.x(), entry.y(), entry.z(), rotation.yaw(), rotation.pitch());
        avatar.setYHeadRot(rotation.headYaw());
        avatar.setYBodyRot(rotation.bodyYaw());
        List<ServerPlayer> viewers = realViewers(server, level);
        ClientboundPlayerInfoUpdatePacket info = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(avatar));
        // Player profile must be delivered before ChunkMap's vanilla spawn packet.
        for (ServerPlayer viewer : viewers) viewer.connection.send(info);
        try {
            level.addNewPlayer(avatar);
            LIVE.put(entry.profileId(), avatar);
            C1VanillaLifecycleCoordinator.afterSpawn(avatar);
            LOG.info("NPCortex C1-P0 ServerLevel.addNewPlayer returned {} profile={} actor={} dim={} pos=({},{},{}) (client unverified)",
                    entry.character(), entry.profileId(), entry.actorId(), entry.dimension(),
                    entry.x(), entry.y(), entry.z());
        } catch (RuntimeException ex) {
            for (ServerPlayer viewer : viewers) {
                viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(entry.profileId())));
            }
            if (!avatar.isRemoved()) avatar.discard();
            // afterSpawn may fail after LIVE.put; drop only this attempt's own entry, never a replacement.
            if (LIVE.get(entry.profileId()) == avatar) LIVE.remove(entry.profileId());
            throw ex;
        }
    }

    static String despawn(MinecraftServer server, String name) {
        ServerThread.require(server, "C1 despawn");
        C1IdentityData identities = C1IdentityData.get(server);
        C1IdentityData.Entry entry = identities.find(name);
        if (entry == null) return "No saved C1-P0 actor named " + name;
        C1PlayerAvatar avatar = LIVE.remove(entry.profileId());
        C1VanillaLifecycleCoordinator.beforeDetach(server, avatar);
        for (Map<UUID, C1P0SyncState> viewers : TRACKED.values()) viewers.remove(entry.profileId());
        if (avatar != null && !avatar.isRemoved()) avatar.discard();
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(entry.profileId())));
        }
        identities.deactivate(name);
        return "Deactivated " + entry.character() + "; identity retained for later respawn.";
    }

    /** Remove the current avatar without deactivating persistent identity (death/dimension). */
    static void detachForLifecycle(MinecraftServer server, UUID profileId) {
        ServerThread.require(server, "C1 lifecycle detach");
        C1PlayerAvatar avatar = LIVE.remove(profileId);
        C1VanillaLifecycleCoordinator.beforeDetach(server, avatar);
        for (Map<UUID, C1P0SyncState> viewers : TRACKED.values()) viewers.remove(profileId);
        if (avatar != null && !avatar.isRemoved()) avatar.discard();
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(profileId)));
        }
    }

    /** Server-only lookup; never accept a client-supplied entity ID as authority. */
    static C1PlayerAvatar activeAvatar(MinecraftServer server, String name) {
        C1IdentityData.Entry entry = C1IdentityData.get(server).find(name);
        if (entry == null || !entry.active()) return null;
        C1PlayerAvatar avatar = LIVE.get(entry.profileId());
        return avatar != null && !avatar.isRemoved()
                && C1P1CLifecycleRuntime.mayRestore(server, entry.profileId()) ? avatar : null;
    }

    static C1PlayerAvatar liveAvatar(UUID profileId) {
        return LIVE.get(profileId);
    }

    /** Persist observed server position; never infer success from a client packet. */
    static void persistPosition(MinecraftServer server, C1PlayerAvatar avatar) {
        if (avatar == null || avatar.isRemoved()) return;
        C1IdentityData.get(server).updatePosition(avatar.getUUID(),
                avatar.serverLevel().dimension().location().toString(),
                avatar.getX(), avatar.getY(), avatar.getZ(), currentRotation(avatar));
    }

    private static C1RotationState currentRotation(C1PlayerAvatar avatar) {
        return new C1RotationState(avatar.getYRot(), avatar.getXRot(),
                avatar.getYHeadRot(), avatar.yBodyRot);
    }

    static String rotationInfo(MinecraftServer server, String name) {
        C1IdentityData.Entry record = C1IdentityData.get(server).find(name);
        if (record == null) return "No saved C1-P0 actor named " + name;
        C1PlayerAvatar live = activeAvatar(server, name);
        return name + " savedRotation=" + record.rotation()
                + " liveRotation=" + (live == null ? "NOT_LIVE" : currentRotation(live));
    }

    /** Event-driven angle update for verified viewers only (never per tick). */
    static void syncRotationForActiveViewers(C1PlayerAvatar npc) {
        if (npc == null || npc.isRemoved()) return;
        MinecraftServer server = npc.getServer();
        if (server == null) return;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            C1P0SyncState state = TRACKED.getOrDefault(viewer.getUUID(), Map.of()).get(npc.getUUID());
            if (state != null && state.phase == C1P0SyncState.Phase.PRESENT
                    && validatedSession(viewer, npc.getUUID(), npc.getId(), state.token) == state) {
                sendRotation(viewer, npc, state);
            }
        }
    }

    /** Only clients with a confirmed RemotePlayer receive event-driven equipment changes. */
    static void forEachPresentViewer(C1PlayerAvatar npc, java.util.function.Consumer<ServerPlayer> consumer) {
        if (npc == null || npc.isRemoved()) return;
        MinecraftServer server = npc.getServer();
        if (server == null) return;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            C1P0SyncState state = TRACKED.getOrDefault(viewer.getUUID(), Map.of()).get(npc.getUUID());
            if (state != null && state.phase == C1P0SyncState.Phase.PRESENT
                    && validatedSession(viewer, npc.getUUID(), npc.getId(), state.token) == state) {
                consumer.accept(viewer);
            }
        }
    }

    /** R1: send Vanilla health metadata + viewer-token-bound client death state.
     * A late viewer receives it after its RemotePlayer presence was verified.
     */
    static void syncDeathPresentation(C1PlayerAvatar npc) {
        if (npc == null || npc.isRemoved()) return;
        MinecraftServer server = npc.getServer();
        if (server == null || !C1P1CLifecycleRuntime.isPresenting(npc.getUUID())) return;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            C1P0SyncState state = TRACKED.getOrDefault(viewer.getUUID(), Map.of()).get(npc.getUUID());
            if (state != null && state.phase == C1P0SyncState.Phase.PRESENT
                    && validatedSession(viewer, npc.getUUID(), npc.getId(), state.token) == state) {
                sendDeathPresentation(viewer, npc, state);
            }
        }
    }

    private static void sendDeathPresentation(ServerPlayer viewer, C1PlayerAvatar npc, C1P0SyncState state) {
        if (!C1P1CLifecycleRuntime.isPresenting(npc.getUUID())) return;
        // Player health data is synced normally by ChunkMap; explicit snapshot
        // covers clients whose tracking started during the death window.
        var data = npc.getEntityData().getNonDefaultValues();
        if (data != null && !data.isEmpty()) {
            viewer.connection.send(new ClientboundSetEntityDataPacket(npc.getId(), data));
        }
        C1P0Network.send(viewer, new C1P0Network.DeathPresentation(
                npc.getUUID(), npc.getId(), state.token));
        LOG.info("NPCortex C1-R1 DEATH_PRESENTATION_SENT viewer={} npc={}",
                viewer.getGameProfile().getName(), npc.getUUID());
    }

    static void onDeathReady(ServerPlayer viewer, C1P0Network.DeathReady msg) {
        ServerThread.require(viewer.getServer(), "C1 death-ready packet");
        C1P0SyncState state = validatedSession(viewer, msg.npcId(), msg.entityId(), msg.token());
        if (state == null || state.phase != C1P0SyncState.Phase.PRESENT
                || !C1P1CLifecycleRuntime.isPresenting(msg.npcId())) return;
        LOG.info("NPCortex C1-R1 CLIENT_DEATH_ANIMATION_ACK viewer={} npc={} entityId={}",
                viewer.getGameProfile().getName(), msg.npcId(), msg.entityId());
        // Only confirms remote entity entered dead state, NOT visual proof.
    }

    private static void sendRotation(ServerPlayer viewer, C1PlayerAvatar npc, C1P0SyncState state) {
        C1RotationState rotation = currentRotation(npc);
        if (!rotation.valid()) return;
        int revision = state.nextRotationRevision();
        if (revision <= 0) return;
        C1P0Network.send(viewer, new C1P0Network.RotationSnapshot(
                npc.getUUID(), npc.getId(), state.token, revision, rotation));
        LOG.debug("NPCortex C1-P1 ROTATION_SYNC viewer={} npc={} revision={} yaw={} pitch={} head={} body={}",
                viewer.getGameProfile().getName(), npc.getUUID(), revision,
                rotation.yaw(), rotation.pitch(), rotation.headYaw(), rotation.bodyYaw());
    }

    static void onRotationApplied(ServerPlayer viewer, C1P0Network.RotationApplied message) {
        ServerThread.require(viewer.getServer(), "C1 rotation-ack packet");
        C1P0SyncState state = validatedSession(viewer, message.npcId(), message.entityId(), message.token());
        if (state != null && state.rotationApplied(message.revision())) {
            LOG.debug("NPCortex C1-P1 ROTATION_APPLIED viewer={} npc={} revision={}",
                    viewer.getGameProfile().getName(), message.npcId(), message.revision());
        }
    }

    static String syncInfo(MinecraftServer server, String name) {
        C1IdentityData.Entry entry = C1IdentityData.get(server).find(name);
        if (entry == null) return "No saved C1-P0 actor named " + name;
        List<String> results = new ArrayList<>();
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            C1P0SyncState state = TRACKED.getOrDefault(viewer.getUUID(), Map.of()).get(entry.profileId());
            results.add(viewer.getGameProfile().getName() + "="
                    + (state == null ? "NOT_TRACKING" : state.phase + "/repairs=" + state.repairs
                            + "/rotAck=" + state.rotationAckRevision + "/" + state.rotationRevision));
        }
        return entry.character() + " network: " + (results.isEmpty() ? "no viewers" : String.join(", ", results))
                + " (CLIENT_ENTITY_PRESENT is a client report, not visual screenshot proof)";
    }

    static String info(MinecraftServer server, String name) {
        C1IdentityData store = C1IdentityData.get(server);
        C1IdentityData.Entry record = store.find(name);
        if (record == null) return "No saved C1-P0 actor named " + name;
        C1PlayerAvatar current = LIVE.get(record.profileId());
        boolean live = current != null && !current.isRemoved();
        return record.character() + " | savedActive=" + record.active() + " | live=" + live
                + " | world=" + store.worldId() + " | actor=" + record.actorId()
                + " | profile=" + record.profileId() + " | gameName=" + record.profileName()
                + " | dim=" + record.dimension() + " | pos=" + record.x() + "," + record.y() + "," + record.z();
    }

    static String list(MinecraftServer server) {
        Collection<C1IdentityData.Entry> entries = C1IdentityData.get(server).all();
        if (entries.isEmpty()) return "C1-P0: no saved avatars";
        List<String> summaries = new ArrayList<>();
        for (C1IdentityData.Entry entry : entries) {
            C1PlayerAvatar avatar = LIVE.get(entry.profileId());
            summaries.add(entry.character() + "[saved=" + entry.active()
                    + ",live=" + (avatar != null && !avatar.isRemoved()) + "]");
        }
        return "C1-P0: " + String.join(", ", summaries);
    }

    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof C1PlayerAvatar) return;
        sendProfilesForDimension(player);
        // TRACK_START may fire before PlayerLoggedInEvent; re-issue with a new token.
        for (UUID npcId : new ArrayList<>(TRACKED.getOrDefault(player.getUUID(), Map.of()).keySet())) {
            C1PlayerAvatar avatar = LIVE.get(npcId);
            if (avatar != null && !avatar.isRemoved() && avatar.serverLevel() == player.serverLevel()) {
                beginHandshake(player, avatar);
            }
        }
    }

    private static void sendProfilesForDimension(ServerPlayer player) {
        if (player.connection == null) return;
        for (C1PlayerAvatar avatar : LIVE.values()) {
            if (!avatar.isRemoved() && avatar.serverLevel() == player.serverLevel()) {
                player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(avatar)));
                LOG.info("NPCortex C1-P0 PROFILE_SYNC viewer={} npc={}", player.getGameProfile().getName(), avatar.getUUID());
            }
        }
    }

    static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer viewer) || viewer instanceof C1PlayerAvatar) return;
        if (!(event.getTarget() instanceof C1PlayerAvatar avatar)) return;
        ServerThread.require(viewer.getServer(), "C1 track start");
        if (viewer.connection == null) return;
        viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(avatar)));
        beginHandshake(viewer, avatar);
        LOG.info("NPCortex C1-P0 TRACK_START viewer={} npc={} entityId={}",
                viewer.getGameProfile().getName(), avatar.getUUID(), avatar.getId());
    }

    private static void beginHandshake(ServerPlayer viewer, C1PlayerAvatar avatar) {
        if (viewer.connection == null || avatar.isRemoved() || viewer.serverLevel() != avatar.serverLevel()) return;
        C1P0SyncState state = new C1P0SyncState(avatar.getUUID(), avatar.getId(), UUID.randomUUID());
        state.lastActivityTick = viewer.getServer().getTickCount();
        TRACKED.computeIfAbsent(viewer.getUUID(), ignored -> new HashMap<>()).put(avatar.getUUID(), state);
        C1P0Network.send(viewer, new C1P0Network.Prepare(state.npcId, state.entityId, state.token));
        LOG.info("NPCortex C1-P0 HANDSHAKE_BEGIN viewer={} npc={} id={}",
                viewer.getGameProfile().getName(), state.npcId, state.entityId);
    }

    private static C1P0SyncState validatedSession(ServerPlayer viewer, UUID npcId, int entityId, UUID token) {
        C1P0SyncState state = TRACKED.getOrDefault(viewer.getUUID(), Map.of()).get(npcId);
        C1PlayerAvatar npc = LIVE.get(npcId);
        if (state == null || !state.matches(npcId, entityId, token)
                || npc == null || npc.isRemoved() || npc.getId() != entityId
                || viewer.connection == null || viewer.serverLevel() != npc.serverLevel()
                || viewer.getServer().getPlayerList().getPlayer(viewer.getUUID()) != viewer) return null;
        return state;
    }

    static void onProfileReady(ServerPlayer viewer, C1P0Network.ProfileReady packet) {
        ServerThread.require(viewer.getServer(), "C1 profile-ready packet");
        C1P0SyncState state = validatedSession(viewer, packet.npcId(), packet.entityId(), packet.token());
        if (state == null || !state.profileReady()) return;
        state.lastActivityTick = viewer.getServer().getTickCount();
        C1PlayerAvatar npc = LIVE.get(state.npcId);
        viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(npc)));
        state.beginVerification();
        C1P0Network.send(viewer, new C1P0Network.Verify(state.npcId, state.entityId, state.token));
        LOG.info("NPCortex C1-P0 PROFILE_READY viewer={} npc={}", viewer.getGameProfile().getName(), state.npcId);
    }

    static void onClientObservation(ServerPlayer viewer, C1P0Network.Observation packet) {
        ServerThread.require(viewer.getServer(), "C1 observation packet");
        C1P0SyncState state = validatedSession(viewer, packet.npcId(), packet.entityId(), packet.token());
        if (state == null || state.phase != C1P0SyncState.Phase.VERIFYING) return;
        state.lastActivityTick = viewer.getServer().getTickCount();
        if (packet.present()) {
            if (!state.reportPresent()) return;
            LOG.info("NPCortex C1-P0 CLIENT_ENTITY_PRESENT viewer={} npc={} repairs={}",
                    viewer.getGameProfile().getName(), state.npcId, state.repairs);
            // Client has created RemotePlayer: apply all independent angles after
            // vanilla spawn/repair (which can otherwise reset head yaw to zero).
            sendRotation(viewer, LIVE.get(state.npcId), state);
            C1P1InventoryRuntime.syncForViewer(viewer, LIVE.get(state.npcId));
            C1P1MotionRuntime.syncCombatVelocityForViewer(viewer, LIVE.get(state.npcId));
            if (C1P1CLifecycleRuntime.isPresenting(state.npcId)) {
                sendDeathPresentation(viewer, LIVE.get(state.npcId), state);
            }
            return;
        }
        // Forge StartTracking recorded this viewer for this particular profile. Only
        // repair that viewer, never spawn a second server entity or update SavedData.
        if (!state.requestRepair()) {
            LOG.error("NPCortex C1-P0 CLIENT_ENTITY_FAILED viewer={} npc={} attempts={}",
                    viewer.getGameProfile().getName(), state.npcId, state.repairs);
            return;
        }
        C1PlayerAvatar npc = LIVE.get(state.npcId);
        viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(npc)));
        viewer.connection.send(new ClientboundAddPlayerPacket(npc));
        var values = npc.getEntityData().getNonDefaultValues();
        if (values != null && !values.isEmpty()) {
            viewer.connection.send(new ClientboundSetEntityDataPacket(npc.getId(), values));
        }
        state.beginVerification();
        C1P0Network.send(viewer, new C1P0Network.Verify(state.npcId, state.entityId, state.token));
        LOG.warn("NPCortex C1-P0 CLIENT_ENTITY_REPAIR viewer={} npc={} attempt={}/{}",
                viewer.getGameProfile().getName(), state.npcId, state.repairs, C1P0SyncState.MAX_REPAIRS);
    }

    static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 40 != 0) return;
        ServerThread.require(event.getServer(), "C1 sync tick");
        long tick = event.getServer().getTickCount();
        for (Map.Entry<UUID, Map<UUID, C1P0SyncState>> viewer : TRACKED.entrySet()) {
            for (C1P0SyncState session : viewer.getValue().values()) {
                if (session.timedOut(tick, 200)) {
                    LOG.warn("NPCortex C1-P0 CLIENT_HANDSHAKE_TIMEOUT viewer={} npc={} phase=FAILED",
                            viewer.getKey(), session.npcId);
                }
            }
        }
    }

    static void onStopTracking(PlayerEvent.StopTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer viewer) || viewer instanceof C1PlayerAvatar) return;
        if (!(event.getTarget() instanceof C1PlayerAvatar avatar)) return;
        ServerThread.require(viewer.getServer(), "C1 track stop");
        Map<UUID, C1P0SyncState> viewerSessions = TRACKED.get(viewer.getUUID());
        if (viewerSessions != null) viewerSessions.remove(avatar.getUUID());
        LOG.info("NPCortex C1-P0 TRACK_STOP viewer={} npc={}", viewer.getGameProfile().getName(), avatar.getUUID());
    }

    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer viewer && !(viewer instanceof C1PlayerAvatar)) {
            ServerThread.require(viewer.getServer(), "C1 viewer logout");
            TRACKED.remove(viewer.getUUID());
        }
    }

    // Do NOT remove the player profile on StopTracking: ChunkMap's next ADD_PLAYER
    // entity packet can be sent before StartTracking fires. Removal would make
    // re-entering view distance fail to create a client-side RemotePlayer.
    static void onViewerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer viewer && !(viewer instanceof C1PlayerAvatar)) {
            ServerThread.require(viewer.getServer(), "C1 viewer dimension/respawn");
            TRACKED.remove(viewer.getUUID());
            sendProfilesForDimension(viewer);
        }
    }

    static void onViewerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer viewer && !(viewer instanceof C1PlayerAvatar)) {
            ServerThread.require(viewer.getServer(), "C1 viewer dimension/respawn");
            TRACKED.remove(viewer.getUUID());
            sendProfilesForDimension(viewer);
        }
    }

    private static List<ServerPlayer> realViewers(MinecraftServer server, ServerLevel level) {
        return server.getPlayerList().getPlayers().stream().filter(player -> player.serverLevel() == level).toList();
    }
}
