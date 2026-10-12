package io.github.sirp00m.npcortex;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import io.github.sirp00m.npcortex.entity.NpcEntities;
import io.github.sirp00m.npcortex.network.NpcNetwork;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Phase 4: generic persistent NPC entity, local dialogue and basic follow/stay. */
@Mod(NPCortex.MOD_ID)
public final class NPCortex {
    public static final String MOD_ID = "npcortex";

    public NPCortex() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, NPCortexConfig.SPEC);
        NpcEntities.register(FMLJavaModLoadingContext.get().getModEventBus());
        NpcNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(NPCortex::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(ActionPlanRunner::tick);
        MinecraftForge.EVENT_BUS.addListener(AutonomyController::tick);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("npcortex")
                // All commands are developer-only in alpha. Enable cheats in test worlds.
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    var source = context.getSource();
                    source.sendSuccess(() -> Component.literal("NPCortex: Checking backend...")
                            .withStyle(ChatFormatting.GRAY), false);
                    ChatBridge.health().whenComplete((result, error) -> source.getServer().execute(() -> {
                        if (error != null) {
                            source.sendFailure(Component.literal("NPCortex: Backend unreachable."));
                        } else {
                            source.sendSuccess(() -> Component.literal("NPCortex: " + result)
                                    .withStyle(ChatFormatting.GREEN), false);
                        }
                    }));
                    return 1;
                }))
                .then(Commands.literal("ask")
                        .then(Commands.argument("character", StringArgumentType.word())
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            var source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();
                                            String characterId = StringArgumentType.getString(context, "character");
                                            String message = StringArgumentType.getString(context, "message");
                                            source.sendSuccess(() -> Component.literal("NPCortex: Thinking...")
                                                    .withStyle(ChatFormatting.GRAY), false);
                                            CompletableFuture<String> future = ChatBridge.ask(
                                                    characterId, player.getUUID().toString(), message);
                                            future.whenComplete((reply, error) -> source.getServer().execute(() -> {
                                                if (error != null) {
                                                    source.sendFailure(Component.literal(
                                                            "NPCortex: Chat failed. Check backend and character ID."));
                                                } else {
                                                    source.sendSuccess(() -> Component.literal(characterId + ": " + reply), false);
                                                }
                                            }));
                                            return 1;
                                        }))))
                .then(Commands.literal("list").executes(context -> {
                    var source = context.getSource();
                    source.sendSuccess(() -> Component.literal("NPCortex: Loading character packs..."), false);
                    ChatBridge.characters().whenComplete((items, error) -> source.getServer().execute(() -> {
                        if (error != null) {
                            source.sendFailure(Component.literal("NPCortex: Cannot load character list from backend."));
                        } else {
                            source.sendSuccess(() -> Component.literal(
                                    "NPCortex characters: " + (items.isEmpty() ? "(none)" : String.join(", ", items))), false);
                        }
                    }));
                    return 1;
                }))
                .then(Commands.literal("sync").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    CompanionEntity npc = nearestCompanion(player);
                    if (npc == null) {
                        player.sendSystemMessage(Component.literal("NPCortex: No owned companion within 12 blocks."));
                        return 0;
                    }
                    refreshCharacter(player, npc);
                    return 1;
                }))
                .then(Commands.literal("inventory").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    CompanionEntity npc = nearestCompanion(player);
                    if (npc == null) {
                        player.sendSystemMessage(Component.literal("NPCortex: No owned companion within 12 blocks."));
                        return 0;
                    }
                    StringBuilder items = new StringBuilder();
                    for (int i = 0; i < npc.bag().getContainerSize(); ++i) {
                        var stack = npc.bag().getItem(i);
                        if (!stack.isEmpty()) items.append(" [").append(i).append("] ")
                                .append(stack.getHoverName().getString()).append(" x").append(stack.getCount());
                    }
                    var held = npc.getMainHandItem();
                    String hand = held.isEmpty() ? "empty" : held.getHoverName().getString() + " x" + held.getCount();
                    player.sendSystemMessage(Component.literal("NPCortex bag of " + npc.getName().getString()
                            + " [" + npc.getUUID().toString().substring(0, 8) + "]:"
                            + (items.length() == 0 ? " (empty)" : items.toString()) + " | hand: " + hand));
                    return 1;
                }))
                // Developer-only diagnostics: verify pickup / drop / give independent
                // of Qwen so a model selection error is not confused with inventory IO.
                .then(Commands.literal("item")
                        .then(Commands.literal("pickup")
                                .then(Commands.argument("item_id", StringArgumentType.greedyString())
                                        .executes(context -> debugItemAction(context.getSource().getPlayerOrException(),
                                                "pickup_nearby", StringArgumentType.getString(context, "item_id")))))
                        .then(Commands.literal("drop")
                                .then(Commands.argument("item_id", StringArgumentType.greedyString())
                                        .executes(context -> debugItemAction(context.getSource().getPlayerOrException(),
                                                "drop_stored_item", StringArgumentType.getString(context, "item_id")))))
                        .then(Commands.literal("give")
                                .then(Commands.argument("item_id", StringArgumentType.greedyString())
                                        .executes(context -> debugItemAction(context.getSource().getPlayerOrException(),
                                                "give_item_to_owner", StringArgumentType.getString(context, "item_id"))))))
                .then(Commands.literal("observe").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    CompanionEntity npc = nearestCompanion(player);
                    if (npc == null) {
                        player.sendSystemMessage(Component.literal("NPCortex: No owned companion within 12 blocks."));
                        return 0;
                    }
                    player.sendSystemMessage(Component.literal("NPCortex: "
                            + WorldObservation.describe(WorldObservation.capture(player, npc))));
                    return 1;
                }))
                .then(Commands.literal("actions").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "NPCortex actions: follow, stay, look_at_player, move_to_player, wander, jump, pickup_nearby, equip_item, drop_held_item, drop_stored_item, take_offered_item, give_item_to_owner. No attack/mining/crafting."), false);
                    return 1;
                }))
                .then(Commands.literal("auto")
                        .then(Commands.literal("list")
                                .executes(context -> listAutonomy(context.getSource().getPlayerOrException())))
                        .then(Commands.literal("on")
                                .executes(context -> setAutonomy(context.getSource().getPlayerOrException(), true))
                                .then(Commands.argument("npc", StringArgumentType.word())
                                        .executes(context -> setAutonomy(context.getSource().getPlayerOrException(),
                                                true, StringArgumentType.getString(context, "npc")))))
                        .then(Commands.literal("off")
                                .executes(context -> setAutonomy(context.getSource().getPlayerOrException(), false))
                                .then(Commands.argument("npc", StringArgumentType.word())
                                        .executes(context -> setAutonomy(context.getSource().getPlayerOrException(),
                                                false, StringArgumentType.getString(context, "npc")))))
                        .then(Commands.literal("status")
                                .executes(context -> statusAutonomy(context.getSource().getPlayerOrException(), null))
                                .then(Commands.argument("npc", StringArgumentType.word())
                                        .executes(context -> statusAutonomy(context.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(context, "npc"))))))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("character", StringArgumentType.word())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    ServerLevel level = player.serverLevel();
                                    String id = StringArgumentType.getString(context, "character");
                                    if (!id.matches("[a-zA-Z0-9_-]{1,64}")) {
                                        context.getSource().sendFailure(Component.literal("Invalid character ID."));
                                        return 0;
                                    }
                                    long count = level.getEntitiesOfClass(CompanionEntity.class,
                                            player.getBoundingBox().inflate(32.0D),
                                            npc -> player.getUUID().equals(npc.getOwnerUUID())).size();
                                    if (count >= 8) {
                                        context.getSource().sendFailure(Component.literal(
                                                "Too many companions nearby (8 max for this alpha)."));
                                        return 0;
                                    }
                                    CompanionEntity npc = NpcEntities.COMPANION.get().create(level);
                                    if (npc == null) return 0;
                                    npc.setCharacterId(id);
                                    npc.setOwnerUUID(player.getUUID());
                                    npc.moveTo(player.getX() + 1.5D, player.getY(), player.getZ(),
                                            player.getYRot(), 0.0F);
                                    if (!level.addFreshEntity(npc)) {
                                        context.getSource().sendFailure(Component.literal("Unable to spawn companion."));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "NPCortex: Spawned " + id + ". Right-click to chat."), false);
                                    refreshCharacter(player, npc);
                                    return 1;
                                })))
                .then(Commands.literal("follow").executes(context -> setFollow(context.getSource().getPlayerOrException(), true)))
                .then(Commands.literal("stay").executes(context -> setFollow(context.getSource().getPlayerOrException(), false))));
    }

    private static int setAutonomy(ServerPlayer player, boolean enabled) {
        CompanionEntity npc = nearestCompanion(player);
        if (npc == null) {
            player.sendSystemMessage(Component.literal("NPCortex: No owned companion within 12 blocks. "
                    + "Use /npcortex auto list to select one by ID or UUID."));
            return 0;
        }
        return applyAutonomy(player, npc, enabled);
    }

    private static int setAutonomy(ServerPlayer player, boolean enabled, String selector) {
        CompanionEntity npc = resolveAutonomyNpc(player, selector);
        return npc == null ? 0 : applyAutonomy(player, npc, enabled);
    }

    private static int applyAutonomy(ServerPlayer player, CompanionEntity npc, boolean enabled) {
        npc.setAutonomyEnabled(enabled);
        player.sendSystemMessage(Component.literal("NPCortex: " + npc.getName().getString()
                + " [" + npc.getUUID().toString().substring(0, 8) + "] autonomy "
                + (enabled ? "ON" : "OFF") + ". Awareness needs the owner nearby."));
        return 1;
    }

    private static CompanionEntity resolveAutonomyNpc(ServerPlayer player, String selector) {
        var match = AutonomySelector.resolve(AutonomyRoster.ownedLoaded(player), selector,
                CompanionEntity::getCharacterId, CompanionEntity::getUUID);
        if (match.ambiguous()) {
            player.sendSystemMessage(Component.literal("NPCortex: Multiple NPCs use ID '" + selector
                    + "'. Use /npcortex auto list and specify the full UUID."));
            return null;
        }
        if (match.value() == null) {
            player.sendSystemMessage(Component.literal("NPCortex: No LOADED owned NPC matches '" + selector
                    + "'. Unloaded NPCs cannot be changed; use /npcortex auto list."));
        }
        return match.value();
    }

    private static int statusAutonomy(ServerPlayer player, String selector) {
        CompanionEntity npc = selector == null ? nearestCompanion(player) : resolveAutonomyNpc(player, selector);
        if (npc == null) {
            if (selector == null) player.sendSystemMessage(Component.literal(
                    "NPCortex: No owned companion within 12 blocks. Use /npcortex auto list."));
            return 0;
        }
        player.sendSystemMessage(Component.literal("NPCortex: " + npc.getName().getString()
                + " [" + npc.getUUID() + "] autonomy=" + (npc.isAutonomyEnabled() ? "ON" : "OFF")
                + " | following=" + npc.isFollowing() + " | retreating=" + npc.isRetreating()));
        return 1;
    }

    private static int listAutonomy(ServerPlayer player) {
        List<CompanionEntity> owned = AutonomyRoster.ownedLoaded(player);
        player.sendSystemMessage(Component.literal("NPCortex: " + owned.size()
                + " loaded owned NPCs (unloaded chunks are not included):"));
        for (int i = 0; i < Math.min(owned.size(), 20); i++) {
            CompanionEntity npc = owned.get(i);
            player.sendSystemMessage(Component.literal(" - " + npc.getName().getString()
                    + " (" + npc.getCharacterId() + ") " + npc.getUUID()
                    + " | auto=" + (npc.isAutonomyEnabled() ? "ON" : "OFF")
                    + " | " + npc.level().dimension().location()));
        }
        if (owned.size() > 20) player.sendSystemMessage(Component.literal(
                "NPCortex: Showing first 20; use ESC > NPCortex Manager > Manage NPC Autonomy for more."));
        return 1;
    }

    private static int debugItemAction(ServerPlayer player, String action, String itemId) {
        CompanionEntity npc = nearestCompanion(player);
        if (npc == null) {
            player.sendSystemMessage(Component.literal("NPCortex: No owned companion within 12 blocks."));
            return 0;
        }
        String target = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        if (!target.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}")) {
            player.sendSystemMessage(Component.literal("NPCortex: Invalid item ID. Example: minecraft:emerald"));
            return 0;
        }
        String result = WorldActions.execute(player, npc, action, target);
        player.sendSystemMessage(Component.literal("NPCortex (" + npc.getName().getString() + "): " + result));
        return result.startsWith("[Action confirmed:") ? 1 : 0;
    }

    /** The backend is authoritative for display metadata. Never block the game tick waiting for it. */
    private static void refreshCharacter(ServerPlayer player, CompanionEntity npc) {
        String id = npc.getCharacterId();
        ChatBridge.character(id).whenComplete((profile, error) -> player.getServer().execute(() -> {
            if (npc.isRemoved()) return;
            if (error != null) {
                player.sendSystemMessage(Component.literal(
                        "NPCortex: Cannot load character '" + id + "'; keeping fallback appearance. Check backend pack."));
                return;
            }
            npc.applyProfile(profile.name(), profile.skin(), profile.model());
            player.sendSystemMessage(Component.literal(
                    "NPCortex: Loaded " + profile.name() + " | skin=" + profile.skin() + " | model=" + profile.model()));
        }));
    }

    private static CompanionEntity nearestCompanion(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(
                        CompanionEntity.class, player.getBoundingBox().inflate(12.0D),
                        npc -> player.getUUID().equals(npc.getOwnerUUID())).stream()
                .min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    private static int setFollow(ServerPlayer player, boolean following) {
        CompanionEntity npc = nearestCompanion(player);
        if (npc == null) {
            player.sendSystemMessage(Component.literal("NPCortex: No owned companion within 12 blocks."));
            return 0;
        }
        ActionPlanRunner.cancel(npc);
        npc.getPersistentData().putInt("NPCortexRequestRevision",
                npc.getPersistentData().getInt("NPCortexRequestRevision") + 1);
        npc.setFollowing(following);
        player.sendSystemMessage(Component.literal("NPCortex: " + npc.getCharacterId()
                + (following ? " is following you." : " will stay here.")));
        return 1;
    }
}
