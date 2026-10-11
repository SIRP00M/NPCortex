package io.github.sirp00m.npcortex;

import java.util.Map;
import java.util.Set;

/** Server-side action registry. The LLM may only propose names listed here.
 *  To add a new action: declare its target policy here, add an executor in
 *  WorldActions, and describe it in backend agent.py. The plan runner does not
 *  need any special-case combination/ordering logic for immediate actions.
 */
public final class ActionCatalog {
    public enum TargetPolicy { NONE, OPTIONAL_ITEM }

    private static final Map<String, TargetPolicy> REGISTERED = Map.ofEntries(
            Map.entry("follow", TargetPolicy.NONE),
            Map.entry("stay", TargetPolicy.NONE),
            Map.entry("look_at_player", TargetPolicy.NONE),
            Map.entry("move_to_player", TargetPolicy.NONE),
            Map.entry("wander", TargetPolicy.NONE),
            Map.entry("jump", TargetPolicy.NONE),
            Map.entry("drop_held_item", TargetPolicy.NONE),
            Map.entry("pickup_nearby", TargetPolicy.OPTIONAL_ITEM),
            Map.entry("equip_item", TargetPolicy.OPTIONAL_ITEM),
            Map.entry("take_offered_item", TargetPolicy.OPTIONAL_ITEM),
            Map.entry("give_item_to_owner", TargetPolicy.OPTIONAL_ITEM),
            Map.entry("drop_stored_item", TargetPolicy.OPTIONAL_ITEM)
    );

    private ActionCatalog() {}

    public static boolean allowed(String action) {
        return REGISTERED.containsKey(action);
    }

    public static boolean itemAction(String action) {
        return REGISTERED.get(action) == TargetPolicy.OPTIONAL_ITEM;
    }

    public static boolean validTarget(String action, String target) {
        if (!allowed(action) || target == null || (!target.isEmpty()
                && !target.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"))) return false;
        return target.isEmpty() || itemAction(action);
    }

    public static Set<String> supported() {
        return REGISTERED.keySet();
    }
}
