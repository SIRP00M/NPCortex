package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Trusted C1 server-world observations for C0. No client/game-model facts. */
final class C2WorldSensor {
    private C2WorldSensor() {}

    static JsonObject snapshot(C2AgentRuntime.Run run) {
        C1PlayerAvatar avatar = run.avatar();
        if (avatar == null || !run.isAlive()) throw new IllegalStateException("NPC is not live for observation");
        JsonObject s = new JsonObject();
        s.addProperty("world_id",run.world.toString());
        s.addProperty("actor_id",run.actor.toString());
        s.addProperty("dimension_id",run.dimension);
        s.add("position",position(avatar.getX(),avatar.getY(),avatar.getZ()));
        JsonObject inventory = new JsonObject();
        for (int i=0;i<avatar.getInventory().getContainerSize();i++) {
            ItemStack stack = avatar.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int count = inventory.has(item) ? inventory.get(item).getAsInt() : 0;
            inventory.addProperty(item,count + stack.getCount());
        }
        if (inventory.size() > 64) throw new IllegalStateException("C2 sensor inventory capacity exceeded");
        s.add("inventory",inventory);
        s.add("blocks",new JsonObject()); // Not scanned: unknown, NOT observed air.
        JsonArray skills = new JsonArray();
        for (String skill : C2SkillRegistry.advertised()) skills.add(skill);
        s.add("available_skills",skills);
        return s;
    }
    private static JsonArray position(double x,double y,double z) {
        JsonArray out=new JsonArray(); out.add(x);out.add(y);out.add(z);return out;
    }
}
