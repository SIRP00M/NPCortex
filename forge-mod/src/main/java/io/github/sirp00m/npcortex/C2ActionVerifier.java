package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;

/** Local C1 receipt check and remote C0 result check must BOTH pass.
 * No user-facing success until the independent trusted C0 verification responds.
 */
final class C2ActionVerifier {
    private C2ActionVerifier() {}
    static boolean localMovement(C2AgentRuntime.Run run, C1P1MotionRuntime.MotionReceipt receipt) {
        C1PlayerAvatar avatar = run.avatar();
        return !receipt.active() && "COMPLETE".equals(receipt.phase()) && avatar != null
                && C2MoveGeometry.near(avatar.getX(),avatar.getY(),avatar.getZ(),
                        run.tx,run.ty,run.tz,run.tolerance);
    }
    static boolean backendVerified(JsonObject reply) {
        if (!reply.has("verified") || !reply.get("verified").isJsonPrimitive()
                || !reply.get("verified").getAsBoolean()) return false;
        JsonObject goal=reply.getAsJsonObject("goal");
        return goal!=null && goal.has("status")
                && "completed".equals(goal.get("status").getAsString());
    }
}
