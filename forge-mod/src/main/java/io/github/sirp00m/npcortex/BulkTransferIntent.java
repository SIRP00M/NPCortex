package io.github.sirp00m.npcortex;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, count-aware dialogue commands; avoids one-item LLM actions. */
public final class BulkTransferIntent {
    public static final int MAX_ITEMS = 640; // NPC nine-slot bag + held stack
    public record Request(boolean give, boolean all, int count, String itemId) {}
    private static final Pattern COMMAND = Pattern.compile(
            "^(?:(?:please|just)\\s+)?(?:can you\\s+|could you\\s+|would you\\s+)?"
            + "(?:please\\s+)?(drop|toss|throw|give(?: me)?|hand me|pass me|return to me)\\s+"
            + "(all|everything|\\d{1,3})(?:\\s+(.+))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_ID = Pattern.compile(
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");

    private BulkTransferIntent() {}

    public static Request parse(String text) {
        if (text == null || text.length() > 400 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf(';') >= 0) return null;
        String normalized = text.toLowerCase(Locale.ROOT).strip().replace('\u2019', '\'');
        if (Pattern.compile("\\b(?:don't|do not|dont|never|except|without|other than|but not|not to)\\b")
                .matcher(normalized).find()) return null;
        normalized = normalized.replaceFirst("^(?:hey|hi|hello)[,!]?\\s+", "");
        normalized = normalized.replaceFirst("^[a-z][a-z0-9_ -]{0,32}[,:]\\s+", "");
        normalized = normalized.replaceAll("[.!?]+$", "").strip().replaceAll("\\s+", " ");
        Matcher matcher = COMMAND.matcher(normalized);
        if (!matcher.matches()) return null;
        String verb = matcher.group(1);
        boolean give = verb.startsWith("give") || verb.startsWith("hand")
                || verb.startsWith("pass") || verb.startsWith("return");
        String quantity = matcher.group(2);
        boolean all = quantity.equals("all") || quantity.equals("everything");
        int count = all ? MAX_ITEMS : Integer.parseInt(quantity);
        if (count < 1 || count > MAX_ITEMS) return null;
        String target = matcher.group(3) == null ? "" : matcher.group(3).strip();
        // No compound commands or exclusions: fail closed rather than dropping all.
        if (target.matches(".*\\b(?:and|then|except|but|without|unless)\\b.*")) return null;
        target = target.replaceFirst("^(?:of\\s+)?(?:(?:all\\s+)?(?:your|the|my|these)\\s+)+", "");
        target = target.replaceFirst("^of\\s+", "");
        target = target.replaceFirst("(?:\\s+(?:to me|for me|back to me|please))+$", "").strip();
        if (target.matches("(?:items?|everything|stuff|inventory|all items?|all of it|them)")) target = "";
        if (!target.isEmpty()) {
            target = target.replaceFirst("\\s+(?:blocks?|items?|pieces?)$", "");
            // Do not guess complicated or unknown item names: only literal item IDs
            // or plain English IDs that match the NPC inventory exactly at execution.
            if (!target.matches("[a-z0-9_]+(?: [a-z0-9_]+){0,4}|[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"))
                return null;
            if (!ITEM_ID.matcher(target).matches()) {
                if (target.endsWith("s") && !target.endsWith("ss") && !target.endsWith("glass"))
                    target = target.substring(0, target.length() - 1);
                target = "minecraft:" + target.replace(' ', '_');
            }
        }
        // Unqualified 'drop 6' / 'give 6' are ambiguous; never arbitrarily
        // pick which item to hand over. 'all/everything' means full inventory.
        if (!all && target.isEmpty()) return null;
        return new Request(give, all, count, target);
    }
}
