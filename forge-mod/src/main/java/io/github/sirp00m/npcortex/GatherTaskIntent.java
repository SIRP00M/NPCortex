package io.github.sirp00m.npcortex;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server-authoritative quantity gathering. Never translate a quantity request to
 * one mine_nearby action (which mines exactly one block).
 */
public final class GatherTaskIntent {
    public record Request(String itemId, int count) {}
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("oak log", "minecraft:oak_log"),
            Map.entry("oak logs", "minecraft:oak_log"),
            Map.entry("dirt", "minecraft:dirt"),
            Map.entry("stone", "minecraft:stone"),
            Map.entry("cobblestone", "minecraft:cobblestone"),
            Map.entry("sand", "minecraft:sand"),
            Map.entry("gravel", "minecraft:gravel"),
            Map.entry("oak plank", "minecraft:oak_planks"),
            Map.entry("oak planks", "minecraft:oak_planks"));
    private static final Pattern GATHER = Pattern.compile(
            "^(?:(?:please|just)\\s+)?(?:(?:(?:can|could|would|will) you(?: please)?|"
            + "i (?:want|need) you to|i'd like you to)\\s+)?(?:please\\s+)?"
            + "(?:collect|gather|mine|dig|harvest|fetch|chop(?: down)?|cut(?: down)?|get me|bring me)\\s+"
            + "(\\d{1,3})\\s+(?:blocks? of\\s+)?"
            + "(minecraft:[a-z0-9_]+|oak logs?|oak planks?|cobblestone|gravel|stone|dirt|sand)"
            + "(?:\\s+(?:blocks?|pieces?|items?))?"
            + "(?:\\s+(?:for me|and (?:bring|give|hand) (?:them|it) (?:back )?to me|"
            + "and (?:bring|give|hand) (?:them|it) back|and (?:bring|give|hand) me (?:them|it)))?"
            + "(?:\\s+please)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANCEL = Pattern.compile(
            "\\b(?:cancel|stop|abort)\\s+(?:the\\s+|my\\s+|your\\s+|this\\s+)?(?:gather(?:ing)?\\s+)?(?:task|job|collecting)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATE = Pattern.compile(
            "\\b(?:don't|do not|dont|never|not to|can't|cannot)\\b", Pattern.CASE_INSENSITIVE);

    private GatherTaskIntent() {}
    public static boolean isCancel(String text) {
        return text != null && !NEGATE.matcher(text).find() && CANCEL.matcher(text).find();
    }
    private static Matcher match(String text) {
        if (text == null || text.length() > 400 || NEGATE.matcher(text).find()
                || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf(';') >= 0) return null;
        String clean = text.toLowerCase(Locale.ROOT).strip()
                .replace('\u2019', '\'').replace('\u2018', '\'');
        // An optional NPC salutation; anchored order prevents matching hypothetical text.
        clean = clean.replaceFirst("^(?:hey|hi|hello)[,!]?\\s+", "");
        clean = clean.replaceFirst("^[a-z][a-z0-9_ -]{0,32}[,:]\\s+", "");
        clean = clean.replaceAll("[.!?]+$", "").strip().replaceAll("\\s+", " ");
        Matcher matcher = GATHER.matcher(clean);
        return matcher.matches() ? matcher : null;
    }
    public static Request parse(String text) {
        Matcher matcher = match(text);
        if (matcher == null) return null;
        int count;
        try { count = Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException ignored) { return null; }
        String raw = matcher.group(2).toLowerCase(Locale.ROOT);
        String item = ALIASES.getOrDefault(raw, raw);
        return GatherTaskPolicy.valid(item, count) ? new Request(item, count) : null;
    }
    /** Recognized but unsafe count/resource: do NOT fall back to the LLM and
     * accidentally report one Minecraft action as all requested blocks.
     */
    public static String rejection(String text) {
        Matcher matcher = match(text);
        if (matcher == null) return null;
        int count = Integer.parseInt(matcher.group(1));
        String raw = matcher.group(2).toLowerCase(Locale.ROOT);
        String item = ALIASES.getOrDefault(raw, raw);
        if (GatherTaskPolicy.valid(item, count)) return null;
        return "[Task blocked: resource unsupported or quantity must be 1–"
                + GatherTaskPolicy.MAX_ITEMS + ". No blocks were mined.]";
    }
}
