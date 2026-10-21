package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/** World-space goal -> existing C1 turn/move, never a second physics owner. */
final class C2MoveExecutor implements C2SkillRegistry.Executor {
    @Override public String id() { return "move_to"; }

    @Override public String start(C2AgentRuntime.Run run, JsonObject args) {
        if (args.size() != 1 || !args.has("position") || !args.get("position").isJsonArray())
            return "Invalid move_to args";
        JsonArray target = args.getAsJsonArray("position");
        if (target.size() != 3) return "Invalid target coordinates";
        final double tx,ty,tz;
        try {
            tx = target.get(0).getAsDouble(); ty = target.get(1).getAsDouble(); tz = target.get(2).getAsDouble();
        } catch (RuntimeException ex) { return "Invalid target coordinates"; }
        // The player's explicit coordinate is the ONLY destination authorized this P0.
        if (!C2MoveGeometry.near(tx, ty, tz, run.tx, run.ty, run.tz, 0.05))
            return "Qwen attempted an unauthorized destination";
        if (run.avatar() == null || !run.isAlive()) return "Actor no longer alive";
        if (!run.avatar().serverLevel().hasChunkAt(BlockPos.containing(tx,ty,tz)))
            return "Destination chunk is not loaded";
        if (run.route!=null) return C2WaypointBridge.beginSegment(run);
        C2MoveGeometry.Plan plan;
        try {
            plan = C2MoveGeometry.plan(run.avatar().getX(), run.avatar().getY(),run.avatar().getZ(),tx,ty,tz);
        } catch (IllegalArgumentException ex) { return ex.getMessage(); }
        String turn = C1P1MotionRuntime.turn(run.server, run.name, plan.yaw());
        if (!turn.startsWith("Turned ")) return "C1 refused rotation: " + turn;
        String move = C1P1MotionRuntime.moveC2Precision(run.server,run.name,"forward",plan.distance());
        if (!move.startsWith("Started B+ motion:")) return "C1 refused move: " + move;
        return null; // Accepted; completion must come from a future C1 receipt + new sensor snapshot.
    }
}
