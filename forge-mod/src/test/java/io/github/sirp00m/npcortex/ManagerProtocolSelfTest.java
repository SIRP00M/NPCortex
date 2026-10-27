package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.ManagerProtocol.*;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** G9: wire format, limits and client/server separation of the NPCortex Manager (pure JVM, no Minecraft). */
public final class ManagerProtocolSelfTest {
    private static int n;

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError(what);
    }

    private static void rejects(byte[] data, String what) {
        try {
            ManagerProtocol.decodeToServer(data);
            throw new AssertionError("should reject: " + what);
        } catch (IOException expected) {
            n++;
        }
    }

    private static void rejectsClient(byte[] data, String what) {
        try {
            ManagerProtocol.decodeToClient(data);
            throw new AssertionError("should reject: " + what);
        } catch (IOException expected) {
            n++;
        }
    }

    private static String repeat(String s, int count) { return s.repeat(count); }

    public static void main(String[] args) throws Exception {
        Draft structured = Draft.structured("Reika", "Calm and precise.", "Formal.", "Born in a snowy village.");
        Draft raw = Draft.raw("Mika", "You are Mika the baker.");

        // ---- round trips
        for (ManagerProtocol.ToServer message : List.<ManagerProtocol.ToServer>of(
                new ListReq(1), new CreateReq(2, "Reika", structured), new RegisterReq(3, "Reika", raw),
                new LoadReq(4, "Reika"), new SaveReq(5, "Reika", 7, structured))) {
            check(ManagerProtocol.decodeToServer(ManagerProtocol.encode(message)).equals(message), "round trip " + message);
        }
        Row row = new Row("Reika", "minecraft:overworld", true, "custom", RowState.OK, "Profile revision 2.", 2, "inline", "Reika", "structured");
        for (ManagerProtocol.ToClient message : List.<ManagerProtocol.ToClient>of(
                new Denied(1, "no"), new ListRep(2, Status.OK, "", false, List.of(row, row)),
                new OpRep(3, Op.SAVE, Status.CONFLICT, "stale", 0, 4, row, null),
                new OpRep(4, Op.LOAD, Status.OK, "ok", 2, 0, row, structured),
                new OpRep(5, Op.CREATE, Status.OFFLINE, "down", 0, 0, null, null))) {
            check(ManagerProtocol.decodeToClient(ManagerProtocol.encode(message)).equals(message), "round trip " + message);
        }

        // ---- UTF-8: Thai, CJK, emoji (surrogate pair), newlines and tabs survive
        String thai = "สวัสดี ฉันคือผู้ช่วย 😀 日本語\nline two\ttab";
        Draft unicode = Draft.structured("Sakura", thai, "", "");
        check(ManagerProtocol.decodeToServer(ManagerProtocol.encode(new SaveReq(9, "Sakura", 1, unicode))).equals(new SaveReq(9, "Sakura", 1, unicode)), "unicode round trip");
        check(ManagerProtocol.validateDraft(unicode) == null, "unicode draft is valid");

        // ---- worst-case sizes stay inside the packet cap (4-byte code points everywhere)
        String emoji = "😀";
        Draft worstRaw = Draft.raw(repeat(emoji, 80), repeat(emoji, ManagerProtocol.PROMPT_MAX));
        byte[] worstRawBytes = ManagerProtocol.encode(new SaveReq(1, "Reika", 1, worstRaw));
        check(worstRawBytes.length < ManagerProtocol.MAX_PACKET_BYTES, "max raw prompt fits: " + worstRawBytes.length);
        Draft worstStructured = Draft.structured(repeat(emoji, 80), repeat(emoji, 1500), repeat(emoji, 500), repeat(emoji, 1500));
        check(ManagerProtocol.encode(new CreateReq(1, "Reika", worstStructured)).length < ManagerProtocol.MAX_PACKET_BYTES, "max structured fits");
        check(ManagerProtocol.MAX_PACKET_BYTES < 32767, "below the vanilla serverbound payload limit");

        // ---- encoder refuses oversize / invalid content
        String[] tooLong = {repeat("a", 17)};
        try { ManagerProtocol.encode(new LoadReq(1, tooLong[0])); throw new AssertionError("name"); } catch (IOException e) { n++; }
        try { ManagerProtocol.encode(new CreateReq(1, "A", Draft.structured("x", repeat("p", 1501), "", ""))); throw new AssertionError("personality"); } catch (IOException e) { n++; }
        try { ManagerProtocol.encode(new CreateReq(1, "A", Draft.raw("x", repeat("p", 5001)))); throw new AssertionError("prompt"); } catch (IOException e) { n++; }
        try { ManagerProtocol.encode(new CreateReq(1, "A", Draft.raw("x\ud800", "p"))); throw new AssertionError("lone surrogate"); } catch (IOException e) { n++; }
        try { ManagerProtocol.encode(new CreateReq(1, "A", new Draft(true, "x", "p", "", "", "mixed"))); throw new AssertionError("mixed"); } catch (IOException e) { n++; }
        try { ManagerProtocol.encode(new CreateReq(1, "A", new Draft(false, "x", "p", "", "", "mixed"))); throw new AssertionError("mixed raw"); } catch (IOException e) { n++; }
        List<Row> many = new ArrayList<>();
        for (int i = 0; i <= ManagerProtocol.MAX_ROWS; i++) many.add(row);
        try { ManagerProtocol.encode(new ListRep(1, Status.OK, "", true, many)); throw new AssertionError("rows"); } catch (IOException e) { n++; }

        // ---- decoder refuses malformed input without trusting it
        byte[] good = ManagerProtocol.encode(new CreateReq(2, "Reika", structured));
        rejects(null, "null");
        rejects(new byte[0], "empty");
        rejects(new byte[]{1}, "one byte");
        rejects(new byte[ManagerProtocol.MAX_PACKET_BYTES + 1], "oversize");
        byte[] wrongVersion = good.clone(); wrongVersion[0] = 99; rejects(wrongVersion, "bad version");
        byte[] wrongType = good.clone(); wrongType[1] = 77; rejects(wrongType, "bad type");
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 3); rejects(truncated, "truncated");
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1); rejects(trailing, "trailing byte");
        // text length field larger than the remaining data
        byte[] lying = ManagerProtocol.encode(new LoadReq(1, "Reika")); lying[10] = (byte) 0xFF; lying[11] = (byte) 0xFF; rejects(lying, "lying length");
        // invalid UTF-8 inside a name
        byte[] badUtf = ManagerProtocol.encode(new LoadReq(1, "Reika")); badUtf[12] = (byte) 0xC3; badUtf[13] = (byte) 0x28; rejects(badUtf, "malformed UTF-8");
        byte[] badEnum = ManagerProtocol.encode(new ListRep(1, Status.OK, "", false, List.of())); badEnum[10] = 99; rejectsClient(badEnum, "bad enum");
        rejectsClient(good, "server message decoded as client message must not succeed silently");

        // ---- validation rules (the backend stays authoritative, these protect the server early)
        check(ManagerProtocol.validateName("Reika") == null, "valid name");
        for (String bad : new String[]{"", "a b", "x".repeat(17), "né", "Re;ika", "a\nb", null})
            check(ManagerProtocol.validateName(bad) != null, "bad name " + bad);
        check(ManagerProtocol.validateDraft(Draft.structured("", "p", "", "")) != null, "empty display name");
        check(ManagerProtocol.validateDraft(Draft.structured("N", "   ", "", "")) != null, "blank personality");
        check(ManagerProtocol.validateDraft(Draft.structured("N", "a\u0000b", "", "")) != null, "NUL rejected");
        check(ManagerProtocol.validateDraft(Draft.structured("N", "a\u001bb", "", "")) != null, "ESC rejected");
        check(ManagerProtocol.validateDraft(Draft.structured("N\nX", "p", "", "")) != null, "newline in display name");
        check(ManagerProtocol.validateDraft(Draft.structured("N", "ok\nmulti\tline", "", "")) == null, "newline/tab allowed in text");
        check(ManagerProtocol.validateDraft(Draft.raw("N", "x".repeat(5000))) == null, "5000 chars ok");
        check(ManagerProtocol.validateDraft(Draft.raw("N", "x".repeat(5001))) != null, "5001 chars rejected");
        check(ManagerProtocol.validateDraft(Draft.structured("N", "\ud800", "", "")) != null, "lone surrogate rejected");
        check(ManagerProtocol.validateDraft(Draft.structured("N", "p", "s".repeat(501), "")) != null, "speech style cap");
        check(ManagerProtocol.normalise("a\r\nb\rc ").equals("a\nb\nc"), "CRLF normalised and trimmed");

        // ---- client/server separation: no URL, token or client-chosen UUID can travel in any message
        List<Class<?>> records = new ArrayList<>();
        for (Class<?> nested : ManagerProtocol.class.getDeclaredClasses()) if (nested.isRecord()) records.add(nested);
        check(records.size() >= 9, "inspected the message records");
        for (Class<?> record : records) {
            for (RecordComponent component : record.getRecordComponents()) {
                String name = component.getName().toLowerCase();
                check(!name.contains("token") && !name.contains("url") && !name.contains("secret") && !name.contains("credential"),
                        record.getSimpleName() + "." + name + " must not carry credentials");
                check(component.getType() != UUID.class, record.getSimpleName() + "." + name + " must not carry client-chosen UUIDs");
                check(!component.getName().equalsIgnoreCase("actorId") && !component.getName().equalsIgnoreCase("worldId"),
                        record.getSimpleName() + " must not name actor/world ids");
            }
        }
        System.out.println("PASS ManagerProtocolSelfTest (" + n + " assertions)");
    }
}
