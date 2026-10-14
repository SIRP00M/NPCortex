package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.agent.SkillRegistry;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import java.util.LinkedHashSet;
import java.util.Set;

/** Authoritative skill manifest + affordances. Python only transports this v2 contract.
 * Existing task execution is an adapter; this registry owns dispatch and result hooks.
 */
public final class BrainSkillRegistry {
    public record Context(ServerPlayer owner, CompanionEntity npc, CompoundTag task,
            JsonObject world, BlockPos origin) {}
    public static final SkillRegistry<Context> REGISTRY=new SkillRegistry<>();
    static {
        register("explore",SkillRegistry.Target.WAYPOINT,"Travel toward an observed unexplored frontier, scan there for resources; no assumed unseen terrain. Choose direction; route rechecked.",ExplorationSkills::explore);
        register("return_to_work",SkillRegistry.Target.NONE,"Return toward the saved worksite after a supply trip using existing terrain; keep supports.",ExplorationSkills::return_to_work);
        register("recover_worksite",SkillRegistry.Target.NONE,"Walk by verified ground route segments toward the last committed tree root to search for task logs; never award missing items.",GoalRecoverySkills::recover_worksite);
        register("navigate_material",SkillRegistry.Target.ITEM,"Try a walk/pillar/bridge segment toward observed material, retaining supports; route search occurs after selection.",HarvestGoalSkills::navigate_material);
        register("mine_material",SkillRegistry.Target.ITEM,"Mine one observed safe natural soil source for reusable build material; physical drops must be picked up.",HarvestGoalSkills::mine_material);
        register("approach_material",SkillRegistry.Target.ITEM,"Walk to an observed safe soil source before acquiring building blocks.",HarvestGoalSkills::approach_material);
        register("mine",SkillRegistry.Target.BLOCK,"Mine an observed visible in-reach goal block.", HarvestGoalSkills::mine);
        register("approach",SkillRegistry.Target.BLOCK,"Walk to a collision-safe standing point; path rechecked at execution.", HarvestGoalSkills::approach);
        register("clear_leaf",SkillRegistry.Target.BLOCK,"Clear verified natural leaves obstructing this goal block.", HarvestGoalSkills::clear_leaf);
        register("clear_soft",SkillRegistry.Target.BLOCK,"Clear a bounded reversible soil obstruction.", HarvestGoalSkills::clear_soft);
        register("climb",SkillRegistry.Target.BLOCK,"Legacy guarded pillar beside a verified tree root, one level.", HarvestGoalSkills::climb);
        register("navigate_3d",SkillRegistry.Target.BLOCK,"Advance toward a block with walk/pillar/bridge segments. PARTIAL means replan, not arrival. Keep supports until work and drops are complete.", HarvestGoalSkills::navigate_3d);
        register("navigate_3d_drop",SkillRegistry.Target.DROP,
                "Use a reversible 3D access route to physically collect a stranded observed drop.",
                HarvestGoalSkills::navigate_3d_drop);
        register("approach_drop",SkillRegistry.Target.DROP,"Walk into physical pickup range of the observed item entity.", HarvestGoalSkills::approach_drop);
        register("pickup",SkillRegistry.Target.NONE,"Collect reachable task drops into the NPC bag.", HarvestGoalSkills::pickup);
        register("descend",SkillRegistry.Target.NONE,"Reverse the recorded access route and remove task-owned temporary supports.", HarvestGoalSkills::descend);
        register("restore",SkillRegistry.Target.NONE,"Restore task-owned terrain after safe descent.", HarvestGoalSkills::restore);
        register("finish",SkillRegistry.Target.NONE,"Ask the goal adapter to verify completion from the real ledger.", HarvestGoalSkills::finish);
        register("pause",SkillRegistry.Target.NONE,"Preserve goal and request owner assistance.", HarvestGoalSkills::pause);
        register("wait",SkillRegistry.Target.NONE,"Wait for a changed observation when no immediate task interaction exists.", HarvestGoalSkills::wait);
        REGISTRY.freeze();
    }
    private BrainSkillRegistry() {}
    private static void register(String id,SkillRegistry.Target target,String description,
            java.util.function.BiConsumer<Context,String> execute) {
        REGISTRY.register(new SkillRegistry.Skill<>(id,target,description,
            (c,ref)->BrainSkills.authorized(c.owner,c.npc) && offered(c.world,id,ref)
                    && (target!=SkillRegistry.Target.BLOCK || BrainSkills.validBlock(c.owner,c.npc,
                        BrainObservation.resolveBlock(c.world,ref,c.origin,c.task.getString("item")),c.task.getString("item"),c.task))
                    && (target!=SkillRegistry.Target.WAYPOINT || TaskWorldBounds.allows(c.owner,
                        ExplorationSkills.resolve(c.world,ref,c.origin)))
                    && (target!=SkillRegistry.Target.ITEM || MaterialSource.eligible(c.owner,c.npc,c.task,
                        BrainObservation.resolveMaterial(c.world,ref,c.origin))),
            execute,
            (c,ref)->verifyEffect(c,id,ref),
            (c,ref)->{ c.npc.getNavigation().stop(); FailureMemory.remember(c.task,c.task.getString("lastResult")); }));
    }
    public static boolean offered(JsonObject world,String skill,String ref) {
        if(!world.has("skills")) return false;
        String choice=skill+(ref.isEmpty()?"":":"+ref);
        for(var entry:world.getAsJsonObject("skills").getAsJsonArray("choices"))
            if(choice.equals(entry.getAsString())) return true;
        return false;
    }
    private static boolean flag(JsonObject b,String key) { return b.has(key) && b.get(key).getAsBoolean(); }
    public static void attach(Context c) {
        var w=c.world; var t=c.task;
        var tree=w.getAsJsonObject("tree");
        boolean active=flag(tree,"active");
        boolean access=Navigation3DAccess.needsCleanup(t);
        int remaining=tree.get("remaining").getAsInt();
        int collected=w.get("collected").getAsInt();
        int mined=t.getInt("mined");
        int borrowed=Navigation3DAccess.borrowed(t,t.getString("item"));
        int temporary=Navigation3DAccess.size(t)+TreeScaffold.size(t)+WorldTerrainEdits.size(t);
        var phase=io.github.sirp00m.npcortex.agent.GoalCompletionLedger.phase(
                active,remaining,t.getInt("completedTrees"),mined,collected,borrowed,t.getInt("goal"),temporary);
        // Only harvest/find-tree phases may modify resource terrain. During
        // recovery, mining soil and unrelated exploration cannot replace logs.
        boolean work=phase==io.github.sirp00m.npcortex.agent.GoalCompletionLedger.Phase.HARVEST
                || phase==io.github.sirp00m.npcortex.agent.GoalCompletionLedger.Phase.FIND_TREE;
        boolean needMaterials=phase==io.github.sirp00m.npcortex.agent.GoalCompletionLedger.Phase.HARVEST
                && BuildMaterials.available(c.npc,t)<8;
        Set<String> choices=new LinkedHashSet<>(); choices.add("pause");
        if(AgenticTaskPolicy.canContinue(t.getInt("calls"),t.getInt("failures"),t.getInt("sameFailures"),t.getInt("stalls"))) {
            if (needMaterials && w.has("material_targets")) for(var entry:w.getAsJsonArray("material_targets")) {
                var resource=entry.getAsJsonObject();
                String ref=resource.get("ref").getAsString();
                if(flag(resource,"reachable")) choices.add("mine_material:"+ref);
                else {
                    choices.add("navigate_material:"+ref);
                    if(!access && TreeScaffold.size(t)==0 && flag(resource,"ground_stand_possible")) choices.add("approach_material:"+ref);
                }
            }
            if((phase==io.github.sirp00m.npcortex.agent.GoalCompletionLedger.Phase.FIND_TREE
                    || (needMaterials && w.getAsJsonArray("targets").isEmpty())))
                for(var entry:w.getAsJsonArray("frontiers")) choices.add("explore:"+entry.getAsJsonObject().get("ref").getAsString());
            if(work && t.contains("workAnchor") && c.npc.blockPosition().distSqr(ExplorationSkills.workAnchor(t))>16
                    && Navigation3DAccess.safeStand(c.owner,t,ExplorationSkills.workAnchor(t)))
                choices.add("return_to_work");
            for(var value:w.getAsJsonArray("targets")) {
                var b=value.getAsJsonObject(); String ref=b.get("ref").getAsString();
                boolean reachable=flag(b,"reachable")&&flag(b,"visible");
                if(!work) continue;
                if(reachable) choices.add("mine:"+ref);
                else {
                    if(!access && TreeScaffold.size(t)==0 && flag(b,"ground_stand_possible")) choices.add("approach:"+ref);
                    if(flag(b,"leaf_clearable")) choices.add("clear_leaf:"+ref);
                    if(active && flag(b,"soft_clearable") && WorldTerrainEdits.size(t)<2) choices.add("clear_soft:"+ref);
                    if(!access && active && flag(w.getAsJsonObject("equipment"),"can_scaffold")
                            && (TreeScaffold.size(t)==0 || (t.getInt("treeX")==t.getInt("wholeTreeRootX")
                                && t.getInt("treeZ")==t.getInt("wholeTreeRootZ")))) choices.add("climb:"+ref);
                    // Search is an executable capability, not evidence of arrival.
                    // Offer every observed target; spend one bounded search only on
                    // Qwen's selected pair. Failure memory suppresses failed repeats.
                    choices.add("navigate_3d:"+ref);
                }
            }
            boolean pickup=false;
            if(collected<mined) for(var value:w.getAsJsonArray("drops")) {
                var d=value.getAsJsonObject();
                // This NPC's own mined drops are managed by its physical
                // locomotion reflex; do not burn Qwen budget on approach_drop.
                boolean own = d.has("npc_mined") && d.get("npc_mined").getAsBoolean();
                if (own && !flag(d,"pickup_reachable")) choices.add("navigate_3d_drop:"+d.get("ref").getAsString());
                if(flag(d,"pickup_reachable")) pickup=true;
                if (own) continue;
                else if(!access && TreeScaffold.size(t)==0) choices.add("approach_drop:"+d.get("ref").getAsString());
            }
            if(pickup) choices.add("pickup");
            // A fully harvested tree must be allowed to recover borrowed support
            // even when that small tree alone did not meet the minimum goal.
            // This is physical cleanup, NOT permission to finish the task.
            boolean treeCleared=remaining==0 && mined>0;
            // A blocked bounded 3D route is NOT a reason to keep retrying from
            // an unchanged tower pose. Let Qwen elect a guarded inverse-route
            // retreat during HARVEST and plan a new approach from below.
            // Only task-owned access is reversible, and two retreats without a
            // verified mined log exhaust this recovery hypothesis.
            boolean recoverHarvestAccess=io.github.sirp00m.npcortex.agent.SafeRetreatPolicy.offerHarvestRetreat(
                    active,remaining,access || TreeScaffold.size(t)>0,
                    t.getBoolean("harvestNavBlocked"),t.getString("accessSearch"),t.getInt("retreatWithoutMine"));
            if((treeCleared || recoverHarvestAccess) && (access || TreeScaffold.size(t)>0)) choices.add("descend");
            if(treeCleared && WorldTerrainEdits.size(t)>0) choices.add("restore");
            if(phase==io.github.sirp00m.npcortex.agent.GoalCompletionLedger.Phase.RECOVER_DROPS
                    && GoalRecoverySkills.eligible(c.owner,c.npc,t)) choices.add("recover_worksite");
            // Waiting on a temporary tower cannot reveal a route by itself.
            // Prefer safe descent/replanning to burning a Qwen request on wait.
            if(phase!=io.github.sirp00m.npcortex.agent.GoalCompletionLedger.Phase.RECOVER_DROPS
                    && !(active && !w.getAsJsonArray("targets").isEmpty()) && !pickup
                    && !access && TreeScaffold.size(t)==0) choices.add("wait");
            if(!active && t.getInt("completedTrees")>0 && WholeTreePolicy.complete(
                    w.get("collected").getAsInt(),t.getInt("mined"),t.getInt("goal"),
                    tree.get("remaining").getAsInt(),TreeScaffold.size(t)+Navigation3DAccess.size(t)+WorldTerrainEdits.size(t))
                    && !access) choices.add("finish");
        }
        choices.removeIf(choice->{
            if(choice.equals("pause")) return false;
            String[] pair=choice.split(":",2); String ref=pair.length==2?pair[1]:"";
            BlockPos target=pair[0].equals("explore") ? ExplorationSkills.resolve(w,ref,c.origin) : pair[0].endsWith("_material")
                    ? BrainObservation.resolveMaterial(w,ref,c.origin)
                    : BrainObservation.resolveBlock(w,ref,c.origin,t.getString("item"));
            return FailureMemory.blocked(t,FailureMemory.identity(pair[0],ref,w,c.origin,t.getString("item")),
                    FailureMemory.fingerprint(c.npc,t,target));
        });
        JsonObject manifest=new JsonObject(); manifest.addProperty("version",3);
        JsonArray catalog=new JsonArray();
        for(String id:REGISTRY.ids().stream().sorted().toList()) {
            var skill=REGISTRY.get(id); JsonObject row=new JsonObject();
            row.addProperty("id",id); row.addProperty("target",skill.target().name().toLowerCase(java.util.Locale.ROOT));
            row.addProperty("description",skill.description()); catalog.add(row);
        }
        JsonArray options=new JsonArray(); choices.forEach(options::add);
        manifest.add("catalog",catalog); manifest.add("choices",options);
        w.add("skills",manifest); w.add("failure_memory",FailureMemory.observation(t));
    }
    /** Outcomes are verified from executor evidence, never a model reason. */
    private static boolean verifyEffect(Context c,String id,String ref) {
        if(id.equals("mine_material")) {
            BlockPos p=c.task.contains("activeBlock") ? BlockPos.of(c.task.getLong("activeBlock")) : null;
            return c.task.getBoolean("verifiedEffect") && p!=null && c.owner!=null
                    && c.owner.serverLevel().isLoaded(p)
                    && !c.owner.serverLevel().getBlockState(p).is(net.minecraft.world.level.block.Blocks.DIRT)
                    && !c.owner.serverLevel().getBlockState(p).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
        }
        if(id.equals("mine")) {
            BlockPos p=c.task.contains("activeBlock") ? BlockPos.of(c.task.getLong("activeBlock")) : null;
            return p!=null && c.owner!=null && c.owner.serverLevel().isLoaded(p)
                && !c.task.getString("item").equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                    c.owner.serverLevel().getBlockState(p).getBlock()).toString());
        }
        // Specialized effects are measured by their existing guarded executors and
        // deferred movement handlers, then passed to this common verification hook.
        return c.task.getBoolean("verifiedEffect");
    }
}
