package io.github.sirp00m.npcortex;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PAP natural-language intent grammar, pure Java. It maps a short chat command to ONE typed verb and an optional block hint.
 *
 * Deliberately deterministic and bounded (no model call, so simple tasks never cost a Qwen request): the text can only select a
 * verb and name a block family ({@code minecraft:<word>}); it can never carry coordinates, ids of other kinds, or authority. The
 * target is grounded on the server (the block the requester looks at) and every mutation still needs the Owner allow-list and an
 * explicit "confirm". Anything that does not match exactly is {@link Kind#UNKNOWN} and is ordinary conversation.
 */
final class PlayerIntent {
    enum Kind { PLACE, USE, BREAK, PICKUP, CONFIRM, CANCEL, UNKNOWN }

    record Intent(Kind kind, String blockHint) {
        static final Intent UNKNOWN = new Intent(Kind.UNKNOWN, "");
    }

    private static final String FILLER = "(?:(?:please|now|just|can you|could you|would you|will you)\\s+)*";
    private static final String DET = "(?:(?:a|an|the|that|this|those|some|one)\\s+)?";
    private static final String WORDS = "([a-z][a-z_ ]{0,30}?)";
    private static final String TAIL = "(?:\\s+(?:here|there|please|now|for me|against that|on that|on it))*";
    private static final Pattern CONFIRM = Pattern.compile("^(?:confirm|yes|yes please|go ahead|do it|proceed|ok do it)$");
    private static final Pattern CANCEL = Pattern.compile("^(?:cancel|stop|abort|never ?mind|forget it|halt)$");
    private static final Pattern PLACE = Pattern.compile("^" + FILLER + "(?:place|put down|put|set)\\s+" + DET + "(?:block of\\s+)?" + WORDS + TAIL + "$");
    private static final Pattern PLACE_BARE = Pattern.compile("^" + FILLER + "(?:place|put down|put|set)(?:\\s+(?:a\\s+)?(?:block|one))?" + TAIL + "$");
    private static final Pattern USE = Pattern.compile("^" + FILLER + "(?:use|press|push|flip|toggle|pull|activate)\\s+" + DET + "(lever|button|switch)" + TAIL + "$");
    private static final Pattern BREAK = Pattern.compile("^" + FILLER + "(?:break|mine|chop|cut|dig)\\s+" + DET + "(?:block of\\s+)?" + WORDS + "?" + TAIL + "$");
    private static final Pattern BREAK_BARE = Pattern.compile("^" + FILLER + "(?:break|mine|chop|cut|dig)(?:\\s+(?:it|that|this|that block|this block))?" + TAIL + "$");
    private static final Pattern PICKUP = Pattern.compile("^" + FILLER + "(?:pick up|pickup|collect|grab|take|get)\\s+" + DET + "(?:items?|drops?|logs?|blocks?|stuff|it|them)?" + TAIL + "$");
    private static final Pattern HINT = Pattern.compile("^[a-z]+(?:_[a-z]+){0,3}$");

    private PlayerIntent() {}

    static Intent parse(String text) {
        if (text == null || text.length() > 120) return Intent.UNKNOWN;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[.!?,;]+$", "").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return Intent.UNKNOWN;
        if (CONFIRM.matcher(t).matches()) return new Intent(Kind.CONFIRM, "");
        if (CANCEL.matcher(t).matches()) return new Intent(Kind.CANCEL, "");
        Matcher m = USE.matcher(t);
        if (m.matches()) return new Intent(Kind.USE, "");
        if (PLACE_BARE.matcher(t).matches()) return new Intent(Kind.PLACE, "");
        m = PLACE.matcher(t);
        if (m.matches()) return new Intent(Kind.PLACE, hint(m.group(1)));
        if (BREAK_BARE.matcher(t).matches()) return new Intent(Kind.BREAK, "");
        m = BREAK.matcher(t);
        if (m.matches()) return new Intent(Kind.BREAK, m.group(1) == null ? "" : hint(m.group(1)));
        if (PICKUP.matcher(t).matches()) return new Intent(Kind.PICKUP, "");
        return Intent.UNKNOWN;
    }

    /** "oak log" -> "minecraft:oak_log"; anything that is not plain lowercase words is dropped (no hint, never an error). */
    private static String hint(String words) {
        if (words == null) return "";
        String w = words.trim().replace(' ', '_');
        if (w.endsWith("_block") && w.length() > 6) w = w.substring(0, w.length() - 6);
        return HINT.matcher(w).matches() && !w.equals("it") && !w.equals("block") ? "minecraft:" + w : "";
    }
}
