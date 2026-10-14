package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.agent.Navigation3D;
import io.github.sirp00m.npcortex.agent.Navigation3D.Cell;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Forge body adapter: rechecks every primitive, keeps an inverse route in NBT,
 * and never teleports, force-loads, or accepts coordinates from the language model.
 * Experimental bounded access, not full Survival navigation (no swimming/ladders).
 */
public final class Navigation3DAccess {
    private static final String ROUTE="accessRoute", HISTORY="accessHistory";
    private static final int TIMEOUT=160;
    private Navigation3DAccess() {}
    private static Cell cell(BlockPos p) { return new Cell(p.getX(),p.getY(),p.getZ()); }
    private static BlockPos pos(Cell p) { return new BlockPos(p.x(),p.y(),p.z()); }
    public static int size(CompoundTag task) {
        int n=0;
        for (var tag:task.getList(HISTORY,Tag.TAG_COMPOUND))
            if (((CompoundTag)tag).getBoolean("placed")) n++;
        return n;
    }
    public static boolean needsCleanup(CompoundTag task) { return !task.getList(HISTORY,Tag.TAG_COMPOUND).isEmpty(); }
    /** Ownership comes from the task's mutation journal, never matching block type alone. */
    public static boolean ownsPlacedBlock(CompoundTag task,BlockPos p) {
        if(p==null) return false;
        for(var entry:task.getList(HISTORY,Tag.TAG_COMPOUND)) {
            CompoundTag row=(CompoundTag)entry;
            if(row.getBoolean("placed") && row.getLong("blockPos")==p.asLong()) return true;
        }
        return false;
    }
    public static int borrowed(CompoundTag task, String id) {
        int n=0;
        for(var entry:task.getList(HISTORY,Tag.TAG_COMPOUND)) {
            CompoundTag row=(CompoundTag)entry;
            if(row.getBoolean("placed") && id.equals(row.getString("block"))) n++;
        }
        for(var entry:task.getList("npcortexScaffold",Tag.TAG_COMPOUND))
            if(id.equals(((CompoundTag)entry).getString("block"))) n++;
        return n;
    }
    public static boolean protectsSupport(CompoundTag task, BlockPos p) {
        for(var entry:task.getList(HISTORY,Tag.TAG_COMPOUND)) {
            CompoundTag row=(CompoundTag)entry;
            if(p.equals(BlockPos.of(row.getLong("from")).below())
                    || p.equals(BlockPos.of(row.getLong("to")).below())) return true;
        }
        return false;
    }
    private static Navigation3D.Plan search(CompoundTag task, Navigation3D.World world, Cell start, int materials) {
        var result=Navigation3D.search(world,start,materials,true);
        task.putString("accessSearch",result.status().name());
        task.putInt("accessExpanded",result.expanded());
        return result.route();
    }
    public static boolean safeStand(ServerPlayer owner, CompoundTag task, BlockPos p) {
        return body(owner,task,p) && support(owner,task,p.below());
    }
    public static Navigation3D.Plan planToStand(ServerPlayer owner, CompanionEntity npc, CompoundTag task,
            BlockPos target, int supplies) {
        if(!BrainSkills.authorized(owner,npc) || !safeStand(owner,task,target) || !npc.onGround()) return null;
        Cell start=cell(npc.blockPosition());
        return search(task,new Navigation3D.World() {
            public boolean allowed(Cell c) { return Navigation3DAccess.allowed(owner,task,pos(c))
                    && Math.abs(c.x()-start.x())<=24 && Math.abs(c.z()-start.z())<=24
                    && Math.abs(c.y()-start.y())<=32; }
            public boolean clear(Cell c) { return Navigation3DAccess.clear(owner,task,pos(c)); }
            public boolean placeable(Cell c) { return Navigation3DAccess.placeable(owner,task,pos(c)); }
            public boolean support(Cell c) { return Navigation3DAccess.support(owner,task,pos(c)); }
            public boolean goal(Cell c) { return pos(c).equals(target); }
            public double estimate(Cell c) { return Math.abs(c.x()-target.getX())
                    +Math.abs(c.y()-target.getY())+Math.abs(c.z()-target.getZ()); }
        },start,supplies);
    }
    /** A walk-only connected-component search. Never builds for exploration.
     * The 12-block lookahead is a per-observation planning horizon, not a
     * lifetime travel leash or a new limit on scaffolding elsewhere.
     */
    public static Navigation3D.TerrainRoutes terrainRoutes(ServerPlayer owner,
            CompanionEntity npc, CompoundTag task) {
        Cell start=cell(npc.blockPosition());
        return Navigation3D.terrainRoutes(new Navigation3D.World() {
            public boolean allowed(Cell c) { return Navigation3DAccess.allowed(owner,task,pos(c)); }
            public boolean clear(Cell c) { return Navigation3DAccess.clear(owner,task,pos(c)); }
            public boolean placeable(Cell c) { return Navigation3DAccess.placeable(owner,task,pos(c)); }
            public boolean support(Cell c) { return Navigation3DAccess.support(owner,task,pos(c)); }
            public boolean goal(Cell c) { return false; }
            public double estimate(Cell c) { return 0; }
        },start,12,32,1400);
    }
    public static String startWaypoint(ServerPlayer owner, CompanionEntity npc, CompoundTag task, BlockPos target) {
        // Exploration uses existing terrain, so looking for supplies cannot spend them in a loop.
        // Explore is terrain-only: reuse the SAME bounded walk geometry used
        // when the frontier was offered, rather than an unrelated A* search.
        if(target==null || !npc.onGround())
            return "[Action failed: frontier unavailable; NPC must be grounded.]";
        var routes=terrainRoutes(owner,npc,task);
        var route=routes.routeTo(cell(target));
        if(route==null || route.steps().isEmpty())
            return "[Action failed: frontier route changed since observation or current pose is invalid.]";
        abortRoute(npc,task);
        installPlan(npc,task,route,target); task.putBoolean("accessWaypoint",true);
        return "[Action started: travelling a verified waypoint segment; structures retained.]";
    }
    private static boolean allowed(ServerPlayer owner, CompoundTag task, BlockPos p) {
        return TaskWorldBounds.allows(owner,p);
    }
    private static boolean clear(ServerPlayer owner, CompoundTag task, BlockPos p) {
        if (!allowed(owner,task,p)) return false;
        var state=owner.serverLevel().getBlockState(p);
        // Walking through a non-colliding short grass/flower is legal. Air-only
        // validation falsely declares many plains NPC start positions invalid.
        // Still reject non-colliding hazards and all fluids/portals.
        return state.getFluidState().isEmpty()
            && !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE)
            && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.COBWEB)
            && !state.is(Blocks.WITHER_ROSE) && !state.is(Blocks.POWDER_SNOW)
            && !state.is(Blocks.CACTUS) && !state.is(Blocks.POINTED_DRIPSTONE)
            && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL)
            && state.getCollisionShape(owner.serverLevel(),p).isEmpty();
    }
    private static boolean placeable(ServerPlayer owner, CompoundTag task, BlockPos p) {
        return allowed(owner,task,p) && owner.serverLevel().getBlockState(p).isAir();
    }
    private static boolean support(ServerPlayer owner, CompoundTag task, BlockPos p) {
        if (!allowed(owner,task,p)) return false;
        var state=owner.serverLevel().getBlockState(p);
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return state.getFluidState().isEmpty() && state.isFaceSturdy(owner.serverLevel(),p,Direction.UP)
            && !id.equals("minecraft:magma_block") && !id.equals("minecraft:cactus")
            && !id.endsWith("campfire") && !id.equals("minecraft:powder_snow");
    }
    private static boolean body(ServerPlayer owner, CompoundTag task, BlockPos p) {
        return clear(owner,task,p) && clear(owner,task,p.above());
    }
    public static int materials(CompanionEntity npc, CompoundTag task) {
        int n=0;
        for(int i=0;i<npc.bag().getContainerSize();i++) {
            var s=npc.bag().getItem(i);
            if (BuildMaterials.usable(task,s)) n+=s.getCount();
        }
        return n;
    }
    public static Navigation3D.Plan plan(ServerPlayer owner, CompanionEntity npc, CompoundTag task, BlockPos target) {
        return planBlock(owner,npc,task,target,false);
    }
    private static Navigation3D.Plan planBlock(ServerPlayer owner, CompanionEntity npc, CompoundTag task,
            BlockPos target, boolean material) {
        if (!BrainSkills.authorized(owner,npc) || target==null || !allowed(owner,task,target)
                || !npc.onGround()) return null;
        Cell start=cell(npc.blockPosition());
        return search(task,new Navigation3D.World() {
            public boolean allowed(Cell c) { return Navigation3DAccess.allowed(owner,task,pos(c))
                && Math.abs(c.x()-start.x())<=24 && Math.abs(c.z()-start.z())<=24
                && Math.abs(c.y()-start.y())<=32; }
            public boolean clear(Cell c) { return Navigation3DAccess.clear(owner,task,pos(c)); }
            public boolean placeable(Cell c) { return Navigation3DAccess.placeable(owner,task,pos(c)); }
            public boolean support(Cell c) {
                // Do not put the resource under a footstep or a retained scaffold.
                return (!material || !pos(c).equals(target)) && Navigation3DAccess.support(owner,task,pos(c));
            }
            public boolean goal(Cell c) {
                Vec3 eye=new Vec3(c.x()+0.5,c.y()+npc.getEyeHeight(),c.z()+0.5);
                if(material) return MaterialSource.reachableFrom(owner,npc,eye,target);
                return !pos(c).below().equals(target)
                    && eye.distanceToSqr(Vec3.atCenterOf(target))<=InteractionReach.squared(owner.isCreative())
                    && WorldBlockActions.visibleFrom(npc,eye,target);
            }
            public double estimate(Cell c) {
                return Math.max(0,Math.sqrt(pos(c).distSqr(target))-(material?1.0:3.0));
            }
        },start,materials(npc,task));
    }
    /** A reusable 3D objective type: physically approach an item caught above
     * ground level rather than requiring an imagined grounded stand. */
    public static Navigation3D.Plan planToDrop(ServerPlayer owner, CompanionEntity npc,
            CompoundTag task, net.minecraft.world.entity.item.ItemEntity drop) {
        if (!BrainSkills.authorized(owner,npc) || drop==null || !drop.isAlive()
                || !allowed(owner,task,drop.blockPosition()) || !npc.onGround()) {
            task.putString("accessSearch","INVALID_START_CONDITION");
            task.putInt("accessExpanded",0);
            return null;
        }
        Cell start=cell(npc.blockPosition());
        Vec3 item=drop.getBoundingBox().getCenter();
        return search(task,new Navigation3D.World() {
            public boolean allowed(Cell c) { return Navigation3DAccess.allowed(owner,task,pos(c))
                && Math.abs(c.x()-start.x())<=24 && Math.abs(c.z()-start.z())<=24
                && Math.abs(c.y()-start.y())<=32; }
            public boolean clear(Cell c) { return Navigation3DAccess.clear(owner,task,pos(c)); }
            public boolean placeable(Cell c) { return Navigation3DAccess.placeable(owner,task,pos(c)); }
            public boolean support(Cell c) { return Navigation3DAccess.support(owner,task,pos(c)); }
            public boolean goal(Cell c) {
                Vec3 chest=new Vec3(c.x()+0.5,c.y()+npc.getBbHeight()*0.45,c.z()+0.5);
                Vec3 eye=new Vec3(c.x()+0.5,c.y()+npc.getEyeHeight(),c.z()+0.5);
                return chest.distanceToSqr(item)<=4.0 && owner.serverLevel().clip(
                        new net.minecraft.world.level.ClipContext(eye,item,
                                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                net.minecraft.world.level.ClipContext.Fluid.NONE,npc))
                        .getType()==net.minecraft.world.phys.HitResult.Type.MISS;
            }
            public double estimate(Cell c) { return Math.max(0,Math.sqrt(pos(c).distSqr(drop.blockPosition()))-2.0); }
        },start,materials(npc,task));
    }
    private static void installPlan(CompanionEntity npc, CompoundTag task, Navigation3D.Plan route,
            BlockPos target) {
        npc.getNavigation().stop();
        task.remove("accessWaypoint"); task.remove("accessMaterial");
        ListTag steps=new ListTag();
        for(var step:route.steps()) steps.add(encode(step));
        task.putBoolean("accessPartial",!route.reached());
        task.put(ROUTE,steps); task.putInt("accessIndex",0);
        task.putLong("accessTarget",target.asLong()); task.putLong("accessStepAt",npc.level().getGameTime());
        task.remove("accessJump"); task.remove("accessPlaced");
    }
    public static String startDrop(ServerPlayer owner,CompanionEntity npc,CompoundTag task,
            net.minecraft.world.entity.item.ItemEntity drop) {
        var route=planToDrop(owner,npc,task,drop);
        if(route==null) return "[Action failed: no safe 3D pickup route found within limits; search="
                + task.getString("accessSearch") + "; expanded=" + task.getInt("accessExpanded") + ".]";
        if(route.steps().isEmpty()) return "[Action unchanged: already near drop; waiting for physical pickup.]";
        installPlan(npc,task,route,drop.blockPosition());
        task.putUUID("accessDrop",drop.getUUID());
        task.putInt("accessDropBefore",AgenticTaskController.collected(npc,task));
        task.putInt("accessDropStackBefore",drop.getItem().getCount());
        task.putLong("accessDropWaitAt",0L);
        return "[Action started: following safe 3D route into physical drop pickup range.]";
    }
    private static CompoundTag encode(Navigation3D.Step step) {
        CompoundTag row=new CompoundTag();
        row.putString("move",step.move().name());
        row.putLong("from",pos(step.from()).asLong()); row.putLong("to",pos(step.to()).asLong());
        if(step.placed()!=null) row.putLong("blockPos",pos(step.placed()).asLong());
        return row;
    }
    public static String start(ServerPlayer owner, CompanionEntity npc, CompoundTag task, BlockPos target) {
        var route=plan(owner,npc,task,target);
        if(route==null) return "[Action failed: bounded 3D search found no safe supported route with current materials; search="
                + task.getString("accessSearch") + "; expanded=" + task.getInt("accessExpanded")
                + "; materials=" + materials(npc,task) + ".]";
        if(route.steps().isEmpty()) return "[Action unchanged: target already accessible.]";
        task.remove("accessDrop");
        installPlan(npc,task,route,target);
        return "[Action started: executing verified walk/pillar/bridge access route.]";
    }
    public static String startMaterial(ServerPlayer owner, CompanionEntity npc, CompoundTag task, BlockPos target) {
        if(!MaterialSource.eligible(owner,npc,task,target)) return "[Action blocked: soil source changed.]";
        if(MaterialSource.reachable(owner,npc,target)) return "[Action unchanged: material already reachable; inspect mine_material in fresh choices.]";
        var route=planBlock(owner,npc,task,target,true);
        if(route==null || route.steps().isEmpty()) return "[Action failed: material route search: "+task.getString("accessSearch")+".]";
        abortRoute(npc,task); installPlan(npc,task,route,target);
        task.putBoolean("accessMaterial",true);
        return "[Action started: navigate toward observed material; structures retained.]";
    }
    private static boolean at(CompanionEntity npc, BlockPos p) {
        double dx=npc.getX()-p.getX()-0.5, dz=npc.getZ()-p.getZ()-0.5;
        return npc.onGround() && npc.blockPosition().getY()==p.getY() && dx*dx+dz*dz<=0.09;
    }
    private static void move(CompanionEntity npc, BlockPos p) {
        if(npc.getNavigation().isDone()) npc.getNavigation().moveTo(p.getX()+0.5,p.getY(),p.getZ()+0.5,0.8);
        if(npc.distanceToSqr(Vec3.atBottomCenterOf(p))<1.5)
            npc.getMoveControl().setWantedPosition(p.getX()+0.5,p.getY(),p.getZ()+0.5,0.6);
    }
    private static String place(ServerPlayer owner, CompanionEntity npc, CompoundTag task,
            CompoundTag row, BlockPos p) {
        if(!placeable(owner,task,p) || owner.serverLevel().getBlockEntity(p)!=null
                || !owner.serverLevel().getEntities(npc,new net.minecraft.world.phys.AABB(p)).isEmpty()
                || npc.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p)))
            return "BLOCKED: placement occupied or changed";
        int slot=BuildMaterials.preferredSlot(npc,task);
        if(slot<0) return "BLOCKED: safe building material unavailable";
        ItemStack s=npc.bag().getItem(slot);
        if(!(s.getItem() instanceof BlockItem block)) return "BLOCKED: material item changed";
        String id=BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        var state=block.getBlock().defaultBlockState();
        if(!state.isSolidRender(owner.serverLevel(),p) || !state.canSurvive(owner.serverLevel(),p))
            return "BLOCKED: material cannot safely support a path";
        if(!owner.serverLevel().setBlock(p,state,3)) return "BLOCKED: placement rejected";
        s.shrink(1); row.putBoolean("placed",true); row.putString("block",id);
        row.putString("state",state.toString());
        AgentRuntime.event(npc,task,"access_placed",row.getString("move") + " " + p + " " + id);
        ListTag history=task.getList(HISTORY,Tag.TAG_COMPOUND);
        history.add(row.copy()); task.put(HISTORY,history); task.putBoolean("accessPlaced",true);
        return "WAIT";
    }
    public static void abortRoute(CompanionEntity npc, CompoundTag task) {
        npc.getNavigation().stop(); task.remove(ROUTE); task.remove("accessJump");
        task.remove("accessPlaced"); task.remove("accessIndex"); task.remove("accessDropWaitAt");
        task.remove("accessDrop"); task.remove("accessDropBefore"); task.remove("accessDropStackBefore");
        task.remove("accessPartial"); task.remove("accessWaypoint"); task.remove("accessMaterial");
    }
    public static String tick(ServerPlayer owner, CompanionEntity npc, CompoundTag task) {
        if(!BrainSkills.authorized(owner,npc)) return "BLOCKED: permission changed";
        ListTag route=task.getList(ROUTE,Tag.TAG_COMPOUND);
        int index=task.getInt("accessIndex");
        if(index>=route.size()) {
            if (task.getBoolean("accessPartial")) {
                BlockPos end=route.isEmpty() ? npc.blockPosition() : BlockPos.of(route.getCompound(route.size()-1).getLong("to"));
                if (!at(npc,end)) return "BLOCKED: segment endpoint not physically reached";
                AgentRuntime.event(npc,task,"access_segment","Verified segment; objective still pending; Qwen replans");
                abortRoute(npc,task); return "SEGMENT";
            }
            if (task.getBoolean("accessWaypoint")) {
                BlockPos target=BlockPos.of(task.getLong("accessTarget"));
                boolean reached=at(npc,target);
                abortRoute(npc,task);
                return reached ? "DONE" : "BLOCKED: waypoint not reached";
            }
            if(task.hasUUID("accessDrop")) {
                var entity=owner.serverLevel().getEntity(task.getUUID("accessDrop"));
                boolean reduced=!(entity instanceof net.minecraft.world.entity.item.ItemEntity item
                        && item.isAlive() && item.getItem().getCount()>=task.getInt("accessDropStackBefore"));
                boolean collected=AgenticTaskController.collected(npc,task)>task.getInt("accessDropBefore") && reduced;
                if(collected) {
                    abortRoute(npc,task); task.remove("accessDrop"); return "DONE";
                }
                if(!task.contains("accessDropWaitAt") || task.getLong("accessDropWaitAt")==0)
                    task.putLong("accessDropWaitAt",npc.level().getGameTime());
                if(npc.level().getGameTime()-task.getLong("accessDropWaitAt")<35) return "WAIT";
                abortRoute(npc,task); task.remove("accessDrop");
                return "BLOCKED: physical 3D drop pickup not verified";
            }
            BlockPos target=BlockPos.of(task.getLong("accessTarget"));
            boolean reached=task.getBoolean("accessMaterial")
                ? MaterialSource.eligible(owner,npc,task,target) && MaterialSource.reachable(owner,npc,target)
                : BrainSkills.validBlock(owner,npc,target,task.getString("item"),task)
                    && npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))<=InteractionReach.squared(owner.isCreative())
                    && WorldBlockActions.visible(npc,target);
            abortRoute(npc,task);
            return reached ? "DONE" : "BLOCKED: target not reachable after route";
        }
        if(npc.level().getGameTime()-task.getLong("accessStepAt")>TIMEOUT) return "BLOCKED: 3D primitive timed out";
        CompoundTag row=route.getCompound(index);
        BlockPos from=BlockPos.of(row.getLong("from")), to=BlockPos.of(row.getLong("to"));
        String kind=row.getString("move");
        if(task.hasUUID("accessDrop")) {
            var entity=owner.serverLevel().getEntity(task.getUUID("accessDrop"));
            boolean reduced=!(entity instanceof net.minecraft.world.entity.item.ItemEntity drop
                    && drop.isAlive() && drop.getItem().getCount()>=task.getInt("accessDropStackBefore"));
            if(reduced && AgenticTaskController.collected(npc,task)>task.getInt("accessDropBefore")) {
                abortRoute(npc,task); task.remove("accessDrop"); return "DONE";
            }
        }
        if(!body(owner,task,to)) return "BLOCKED: route body space changed";
        boolean building=!kind.equals("WALK");
        if(!building || task.getBoolean("accessPlaced")) {
            if(!support(owner,task,to.below())) return "BLOCKED: destination support changed";
            if(at(npc,to)) {
                if(!building && (needsCleanup(task) || TreeScaffold.size(task)>0)) {
                    ListTag history=task.getList(HISTORY,Tag.TAG_COMPOUND); history.add(row.copy()); task.put(HISTORY,history);
                }
                npc.getNavigation().stop(); task.putInt("accessIndex",index+1);
                task.putLong("accessStepAt",npc.level().getGameTime());
                task.remove("accessJump"); task.remove("accessPlaced");
                return "WAIT";
            }
            if(!kind.equals("PILLAR")) move(npc,to);
            return "WAIT";
        }
        if(kind.equals("PILLAR") && task.getBoolean("accessJump")) {
            if(!support(owner,task,from.below())) return "BLOCKED: pillar support changed during jump";
            double dx=npc.getX()-from.getX()-0.5,dz=npc.getZ()-from.getZ()-0.5;
            if(dx*dx+dz*dz>0.16) return "BLOCKED: drift during pillar jump";
            if(npc.getBoundingBox().minY>=from.getY()+1.02) return place(owner,npc,task,row,from);
            return "WAIT";
        }
        if(!body(owner,task,from) || !support(owner,task,from.below())) return "BLOCKED: departure changed";
        if(!at(npc,from)) { move(npc,from); return "WAIT"; }
        npc.getNavigation().stop();
        if(kind.equals("PILLAR")) {
            if(!clear(owner,task,from.above(2))) return "BLOCKED: jump headroom changed";
            task.putBoolean("accessJump",true); npc.getJumpControl().jump(); return "WAIT";
        }
        // A bridge extends the support face immediately beside the current floor.
        BlockPos p=to.below();
        if(from.getY()!=to.getY() || Math.abs(from.getX()-to.getX())+Math.abs(from.getZ()-to.getZ())!=1
                || !support(owner,task,from.below())) return "BLOCKED: bridge lacks an adjacent support";
        return place(owner,npc,task,row,p);
    }
    public static String cleanup(ServerPlayer owner, CompanionEntity npc, CompoundTag task) {
        if(!BrainSkills.authorized(owner,npc)) return "BLOCKED: cleanup permission changed";
        ListTag history=task.getList(HISTORY,Tag.TAG_COMPOUND);
        if(history.isEmpty()) return npc.onGround() ? "DONE" : "WAIT";
        CompoundTag row=history.getCompound(history.size()-1);
        BlockPos from=BlockPos.of(row.getLong("from")), to=BlockPos.of(row.getLong("to"));
        if(!npc.onGround()) return "WAIT";
        boolean pillar=row.getString("move").equals("PILLAR");
        BlockPos stand=pillar ? to : from;
        if(!body(owner,task,stand) || !support(owner,task,stand.below())) return "BLOCKED: return route changed";
        if(!at(npc,stand)) { move(npc,stand); return "WAIT"; }
        npc.getNavigation().stop();
        if(row.getBoolean("placed")) {
            BlockPos p=BlockPos.of(row.getLong("blockPos"));
            if(!allowed(owner,task,p) || !row.getString("block").equals(BuiltInRegistries.BLOCK.getKey(
                    owner.serverLevel().getBlockState(p).getBlock()).toString()))
                return "BLOCKED: recorded temporary block changed externally";
            if(row.contains("state") && !row.getString("state").equals(owner.serverLevel().getBlockState(p).toString()))
                return "BLOCKED: temporary block properties changed externally";
            if(pillar && !support(owner,task,p.below())) return "BLOCKED: descent support missing";
            if(npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(p))>InteractionReach.squared(owner.isCreative())
                    || !WorldBlockActions.visible(npc,p)) return "BLOCKED: cleanup block not reachable";
            if(!owner.serverLevel().getEntities(npc,new net.minecraft.world.phys.AABB(p).move(0,1,0)).isEmpty())
                return "BLOCKED: another entity is on the temporary block";
            if(!owner.serverLevel().setBlock(p,Blocks.AIR.defaultBlockState(),3)) return "BLOCKED: cleanup rejected";
            var item=BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(row.getString("block")));
            var leftover=npc.bag().addItem(new ItemStack(item));
            if(!leftover.isEmpty()) Block.popResource(owner.serverLevel(),p,leftover);
        }
        AgentRuntime.event(npc,task,"access_reversed",row.getString("move") + " " + from);
        history.remove(history.size()-1); task.put(HISTORY,history);
        task.putLong("cleanupStarted",npc.level().getGameTime());
        return "WAIT"; // allow real one-block descent before reporting cleanup complete
    }
}
