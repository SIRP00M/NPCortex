package io.github.sirp00m.npcortex;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An intentionally conservative English intent recognizer for immediate owner overrides.
 *
 * This is NOT the general LLM planner. A plain, unambiguous 'wait here' or 'follow me'
 * should interrupt a stale plan without waiting for the model (which can choose follow
 * for 'wait for me here'). Compound requests and indirect questions are left to Qwen.
 *
 * To add another language, create a language-specific recognizer instead of making this
 * grammar depend on one NPC name or an ever-growing list of exact sentences.
 */
public final class DirectIntent {
    private DirectIntent() {}

    private static final Pattern MULTI_COMMAND = Pattern.compile(
            "\\b(?:and|then|after that|before you|but|instead)\\b");
    private static final Pattern STAY_VERB = Pattern.compile(
            "^(?:stay|wait|remain|stand)" +
            "(?: (?:right|just|exactly|quietly|patiently|here|there|put|still|for me|where you are|" +
            "at (?:this|that) (?:spot|place)|in (?:this|that) (?:spot|place)))*" +
            "(?: until (?:i|we) (?:return|come back|get back|am back))?" +
            "(?: while i (?:am gone|explore|look around))?$"
    );
    private static final Pattern STAY_OTHER = Pattern.compile(
            "^(?:hold (?:your )?position(?: here| for now)?|" +
            "(?:stop|quit) (?:following|chasing)(?: me)?(?: for (?:now|a while|a bit))?|" +
            "(?:don't|do not|never) (?:follow|chase)(?: me)?(?: for (?:now|a while|a bit))?|" +
            "(?:don't|do not) move(?: from (?:here|this spot))?|" +
            "hang back(?: here)?|" +
            "i (?:don't|do not) want you to follow me)" +
            "$"
    );
    private static final Pattern LOOK = Pattern.compile(
            "^(?:look at me|look my way|look this way|face me|turn to face me|turn towards me|turn toward me)$");
    private static final Pattern APPROACH = Pattern.compile(
            "^(?:come here|come over here|come over to me|walk over here|walk over to me|move (?:closer|towards me|toward me)|get closer to me)$");
    private static final Pattern WANDER = Pattern.compile(
            "^(?:wander(?: around| nearby)?|walk around(?: a (?:little|bit))?|" +
            "take a (?:short |little )?walk|explore nearby|look around the area)$");
    private static final Pattern JUMP = Pattern.compile(
            "^(?:jump|jump once|hop|hop once|do a jump)$");
    private static final Pattern FOLLOW = Pattern.compile(
            "^(?:follow me|come follow me|start following me|keep following me|" +
            "come with me|come along(?: with me)?|walk with me)(?: now)?$"
    );

    public static String action(String message, String characterId, String displayName) {
        if (message == null || message.length() > 220) return null;
        String text = message.strip().toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replace('\u2018', '\'');
        // Never treat quoted dialogue, multi-sentence or multi-step text as direct orders.
        if (text.contains("\"") || text.contains("\n") || text.contains(";") || text.contains(". ")) return null;
        text = stripGreetingAndAddress(text, displayName, characterId);
        text = text.replaceAll("[.!?,]+$", "").strip().replaceAll("\\s+", " ");
        if (MULTI_COMMAND.matcher(text).find()) return null;

        // Polite imperative and request wrappers are stripped; the remaining grammar
        // must still match in full (so discussing 'wait here' does not issue an order).
        text = text.replaceFirst("^(?:please|just) ", "");
        text = text.replaceFirst("^(?:(?:can|could|would|will) you(?: please)?|" +
                "i (?:want|need) you to|i'd like you to) ", "");
        text = text.replaceFirst("^(?:please|just) ", "");
        text = text.replaceFirst(",? (?:please|thanks|thank you|for now)$", "")
                .replaceAll("[,.:]+$", "").strip();

        if (STAY_VERB.matcher(text).matches() || STAY_OTHER.matcher(text).matches()) return "stay";
        if (FOLLOW.matcher(text).matches()) return "follow";
        if (LOOK.matcher(text).matches()) return "look_at_player";
        if (APPROACH.matcher(text).matches()) return "move_to_player";
        if (WANDER.matcher(text).matches()) return "wander";
        if (JUMP.matcher(text).matches()) return "jump";
        return null;
    }

    private static String stripGreetingAndAddress(String text, String displayName, String characterId) {
        text = text.replaceFirst("^(?:hey|hi|hello),?\\s+", "");
        text = stripAddress(text, displayName);
        text = stripAddress(text, characterId);
        return text;
    }

    private static String stripAddress(String text, String name) {
        if (name == null || name.isBlank()) return text;
        String normalized = name.strip().toLowerCase(Locale.ROOT);
        if (text.startsWith(normalized + ",") || text.startsWith(normalized + ":"))
            return text.substring(normalized.length() + 1).strip();
        if (text.startsWith(normalized + " "))
            return text.substring(normalized.length()).strip();
        return text;
    }
}
