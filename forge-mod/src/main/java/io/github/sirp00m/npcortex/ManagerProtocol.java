package io.github.sirp00m.npcortex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * NPCortex Manager wire format, independent of Minecraft classes so every limit is unit-testable.
 * The network layer only moves one opaque byte[] per message (npcortex:manager channel).
 *
 * Nothing in here can carry a backend URL, a token, or a world/actor UUID chosen by the client:
 * the client names an NPC, and the server derives the identities from its own saved state.
 */
final class ManagerProtocol {
    static final int VERSION = 1;
    /** Under the 32767-byte serverbound custom-payload limit, with room for the channel header. */
    static final int MAX_PACKET_BYTES = 30000;

    static final int NAME_MAX = 16;
    static final int DISPLAY_NAME_MAX = 80;
    static final int PERSONALITY_MAX = 1500;
    static final int SPEECH_STYLE_MAX = 500;
    static final int BACKSTORY_MAX = 1500;
    static final int PROMPT_MAX = 5000;
    static final int MESSAGE_MAX = 300;
    static final int DIMENSION_MAX = 128;
    static final int SOURCE_MAX = 64;
    static final int MAX_ROWS = 200;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0b-\\x1f\\x7f-\\x9f]");

    private ManagerProtocol() {}

    static final class ProtocolException extends IOException {
        ProtocolException(String message) { super(message); }
    }

    enum Status { OK, NOT_REGISTERED, CONFLICT, OFFLINE, REJECTED, ERROR, DENIED, BUSY, UNKNOWN }
    enum RowState { OK, NOT_REGISTERED, LEGACY, OFFLINE }
    /** NOOP is appended: ordinals are on the wire. */
    enum Op { LIST, CREATE, REGISTER, LOAD, SAVE, NOOP, NATIVE }

    /** Stage 4 native action requests. Ordinals are on the wire. The client names an NPC and, for CONFIRM, echoes an opaque nonce
     * the server issued: it never sends a coordinate, a block, an item or an identity. */
    static final int NATIVE_PREVIEW_BREAK = 0, NATIVE_PREVIEW_PICKUP = 1, NATIVE_CONFIRM = 2, NATIVE_CANCEL = 3;
    static final int NONCE_LENGTH = 32;
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");

    /** The editable part of a Character Profile. Exactly one of the two text shapes is used. */
    record Draft(boolean structured, String displayName, String personality, String speechStyle,
                 String backstory, String systemPrompt) {
        static Draft structured(String displayName, String personality, String speechStyle, String backstory) {
            return new Draft(true, displayName, personality, speechStyle, backstory, "");
        }
        static Draft raw(String displayName, String systemPrompt) {
            return new Draft(false, displayName, "", "", "", systemPrompt);
        }
    }

    record Row(String name, String dimension, boolean active, String mode, RowState state, String detail,
               int revision, String source, String displayName, String composition) {}

    sealed interface ToServer permits ListReq, CreateReq, RegisterReq, LoadReq, SaveReq, NoopReq, NativeReq { long opId(); }
    record ListReq(long opId) implements ToServer {}
    record CreateReq(long opId, String name, Draft draft) implements ToServer {}
    record RegisterReq(long opId, String name, Draft draft) implements ToServer {}
    record LoadReq(long opId, String name) implements ToServer {}
    record SaveReq(long opId, String name, int expectedRevision, Draft draft) implements ToServer {}
    /** Stage 4 no-op world action (or reconcile of an unresolved one). Carries ONLY an NPC name: the requester is the
     * authenticated packet sender and every other identity is derived by the server. */
    record NoopReq(long opId, String name, boolean reconcile) implements ToServer {}
    /** Stage 4 native break/pickup: preview (server picks the target), confirm (nonce only) or cancel. */
    record NativeReq(long opId, String name, int action, String nonce) implements ToServer {}

    sealed interface ToClient permits Denied, ListRep, OpRep, NativeRep { long opId(); }
    /** Answer to a NativeReq. {@code nonce} is non-empty only for an accepted preview (the server keeps the target). */
    record NativeRep(long opId, Status status, String message, String nonce) implements ToClient {}
    record Denied(long opId, String message) implements ToClient {}
    record ListRep(long opId, Status status, String message, boolean truncated, List<Row> rows) implements ToClient {}
    /** {@code row} non-null means the NPC exists locally (even when {@code status} reports a failure). */
    record OpRep(long opId, Op op, Status status, String message, int revision, int currentRevision,
                 Row row, Draft draft) implements ToClient {}

    // ---- validation shared by the client form and the server (the backend stays authoritative) ----

    static String normalise(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** @return an English error message, or null when the name is acceptable. */
    static String validateName(String name) {
        if (name == null || !NAME.matcher(name).matches())
            return "Name must be 1-16 letters, digits or underscores.";
        return null;
    }

    private static String text(String label, String value, int min, int max, boolean multiline) {
        String text = normalise(value);
        int length = length(text);
        if (length < min || length > max) return label + " must contain " + min + "-" + max + " characters.";
        if (CONTROL.matcher(text).find() || (!multiline && (text.indexOf('\n') >= 0 || text.indexOf('\t') >= 0)))
            return label + " contains control characters.";
        for (int i = 0; i < text.length(); i++) {
            if (Character.isSurrogate(text.charAt(i))) {
                int cp = text.codePointAt(i);
                if (Character.charCount(cp) == 1) return label + " contains invalid Unicode.";
                i++;
            }
        }
        return null;
    }

    /** @return an English error message, or null when the draft is acceptable. */
    static String validateDraft(Draft draft) {
        if (draft == null) return "Profile is missing.";
        String error = text("Display name", draft.displayName(), 1, DISPLAY_NAME_MAX, false);
        if (error != null) return error;
        if (draft.structured()) {
            if (!draft.systemPrompt().isEmpty()) return "A structured profile cannot carry a raw prompt.";
            error = text("Personality", draft.personality(), 1, PERSONALITY_MAX, true);
            if (error == null) error = text("Speech style", draft.speechStyle(), 0, SPEECH_STYLE_MAX, true);
            if (error == null) error = text("Backstory", draft.backstory(), 0, BACKSTORY_MAX, true);
            return error;
        }
        if (!draft.personality().isEmpty() || !draft.speechStyle().isEmpty() || !draft.backstory().isEmpty())
            return "A raw profile cannot carry structured sections.";
        return text("Prompt", draft.systemPrompt(), 1, PROMPT_MAX, true);
    }

    static Draft normalised(Draft draft) {
        return new Draft(draft.structured(), normalise(draft.displayName()), normalise(draft.personality()),
                normalise(draft.speechStyle()), normalise(draft.backstory()), normalise(draft.systemPrompt()));
    }

    // ---- codec ----

    private static final int T_LIST = 1, T_CREATE = 2, T_REGISTER = 3, T_LOAD = 4, T_SAVE = 5, T_NOOP = 6, T_NATIVE = 7;
    private static final int T_DENIED = 101, T_LIST_REP = 102, T_OP_REP = 103, T_NATIVE_REP = 104;

    /** @return an English error message, or null when the native request is well-formed. */
    static String validateNative(NativeReq m) {
        if (m.action() < NATIVE_PREVIEW_BREAK || m.action() > NATIVE_CANCEL) return "Unknown native action.";
        String nonce = m.nonce() == null ? "" : m.nonce();
        if (m.action() == NATIVE_CONFIRM ? !NONCE.matcher(nonce).matches() : !nonce.isEmpty()) return "Malformed native request.";
        return null;
    }

    private static void writeText(DataOutputStream out, String value, int maxChars) throws IOException {
        String text = value == null ? "" : value;
        if (length(text) > maxChars) throw new ProtocolException("text too long");
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        } catch (CharacterCodingException e) {
            throw new ProtocolException("invalid Unicode");
        }
        if (encoded.remaining() > maxChars * 4) throw new ProtocolException("text too large");
        out.writeShort(encoded.remaining());
        out.write(encoded.array(), 0, encoded.remaining());
    }

    private static String readText(DataInputStream in, int maxChars) throws IOException {
        int size = in.readUnsignedShort();
        if (size > maxChars * 4 || size > in.available()) throw new ProtocolException("bad text length");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("malformed UTF-8");
        }
        if (length(text) > maxChars) throw new ProtocolException("text too long");
        return text;
    }

    private static void writeDraft(DataOutputStream out, Draft draft) throws IOException {
        if (draft == null) throw new ProtocolException("draft missing");
        if (draft.structured() ? !draft.systemPrompt().isEmpty()
                : (!draft.personality().isEmpty() || !draft.speechStyle().isEmpty() || !draft.backstory().isEmpty()))
            throw new ProtocolException("draft mixes structured and raw text");
        out.writeBoolean(draft.structured());
        writeText(out, draft.displayName(), DISPLAY_NAME_MAX);
        writeText(out, draft.personality(), PERSONALITY_MAX);
        writeText(out, draft.speechStyle(), SPEECH_STYLE_MAX);
        writeText(out, draft.backstory(), BACKSTORY_MAX);
        writeText(out, draft.systemPrompt(), PROMPT_MAX);
    }

    private static Draft readDraft(DataInputStream in) throws IOException {
        boolean structured = in.readBoolean();
        Draft draft = new Draft(structured, readText(in, DISPLAY_NAME_MAX), readText(in, PERSONALITY_MAX),
                readText(in, SPEECH_STYLE_MAX), readText(in, BACKSTORY_MAX), readText(in, PROMPT_MAX));
        if (structured ? !draft.systemPrompt().isEmpty()
                : (!draft.personality().isEmpty() || !draft.speechStyle().isEmpty() || !draft.backstory().isEmpty()))
            throw new ProtocolException("draft mixes structured and raw text");
        return draft;
    }

    private static void writeRow(DataOutputStream out, Row row) throws IOException {
        writeText(out, row.name(), NAME_MAX);
        writeText(out, row.dimension(), DIMENSION_MAX);
        out.writeBoolean(row.active());
        writeText(out, row.mode(), SOURCE_MAX);
        out.writeByte(row.state().ordinal());
        writeText(out, row.detail(), MESSAGE_MAX);
        out.writeInt(row.revision());
        writeText(out, row.source(), SOURCE_MAX);
        writeText(out, row.displayName(), DISPLAY_NAME_MAX);
        writeText(out, row.composition(), 16);
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream in, E[] values) throws IOException {
        int ordinal = in.readUnsignedByte();
        if (ordinal >= values.length) throw new ProtocolException("bad enum");
        return values[ordinal];
    }

    private static Row readRow(DataInputStream in) throws IOException {
        return new Row(readText(in, NAME_MAX), readText(in, DIMENSION_MAX), in.readBoolean(),
                readText(in, SOURCE_MAX), readEnum(in, RowState.values()), readText(in, MESSAGE_MAX),
                in.readInt(), readText(in, SOURCE_MAX), readText(in, DISPLAY_NAME_MAX), readText(in, 16));
    }

    private static byte[] finish(ByteArrayOutputStream bytes) throws IOException {
        if (bytes.size() > MAX_PACKET_BYTES) throw new ProtocolException("message too large");
        return bytes.toByteArray();
    }

    static byte[] encode(ToServer message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(VERSION);
        if (message instanceof ListReq m) {
            out.writeByte(T_LIST); out.writeLong(m.opId());
        } else if (message instanceof CreateReq m) {
            out.writeByte(T_CREATE); out.writeLong(m.opId()); writeText(out, m.name(), NAME_MAX); writeDraft(out, m.draft());
        } else if (message instanceof RegisterReq m) {
            out.writeByte(T_REGISTER); out.writeLong(m.opId()); writeText(out, m.name(), NAME_MAX); writeDraft(out, m.draft());
        } else if (message instanceof LoadReq m) {
            out.writeByte(T_LOAD); out.writeLong(m.opId()); writeText(out, m.name(), NAME_MAX);
        } else if (message instanceof SaveReq m) {
            out.writeByte(T_SAVE); out.writeLong(m.opId()); writeText(out, m.name(), NAME_MAX);
            out.writeInt(m.expectedRevision()); writeDraft(out, m.draft());
        } else if (message instanceof NoopReq m) {
            out.writeByte(T_NOOP); out.writeLong(m.opId()); writeText(out, m.name(), NAME_MAX); out.writeBoolean(m.reconcile());
        } else if (message instanceof NativeReq m) {
            if (validateNative(m) != null) throw new ProtocolException("malformed native request");
            out.writeByte(T_NATIVE); out.writeLong(m.opId()); writeText(out, m.name(), NAME_MAX);
            out.writeByte(m.action()); writeText(out, m.nonce() == null ? "" : m.nonce(), NONCE_LENGTH);
        } else {
            throw new ProtocolException("unknown message");
        }
        return finish(bytes);
    }

    static ToServer decodeToServer(byte[] data) throws IOException {
        if (data == null || data.length < 2 || data.length > MAX_PACKET_BYTES) throw new ProtocolException("bad size");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readUnsignedByte() != VERSION) throw new ProtocolException("bad version");
        int type = in.readUnsignedByte();
        long op = in.readLong();
        ToServer message = switch (type) {
            case T_LIST -> new ListReq(op);
            case T_CREATE -> new CreateReq(op, readText(in, NAME_MAX), readDraft(in));
            case T_REGISTER -> new RegisterReq(op, readText(in, NAME_MAX), readDraft(in));
            case T_LOAD -> new LoadReq(op, readText(in, NAME_MAX));
            case T_SAVE -> new SaveReq(op, readText(in, NAME_MAX), in.readInt(), readDraft(in));
            case T_NOOP -> new NoopReq(op, readText(in, NAME_MAX), in.readBoolean());
            case T_NATIVE -> {
                NativeReq request = new NativeReq(op, readText(in, NAME_MAX), in.readUnsignedByte(), readText(in, NONCE_LENGTH));
                if (validateNative(request) != null) throw new ProtocolException("malformed native request");
                yield request;
            }
            default -> throw new ProtocolException("unknown type");
        };
        if (in.available() != 0) throw new ProtocolException("trailing bytes");
        return message;
    }

    static byte[] encode(ToClient message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(VERSION);
        if (message instanceof Denied m) {
            out.writeByte(T_DENIED); out.writeLong(m.opId()); writeText(out, m.message(), MESSAGE_MAX);
        } else if (message instanceof ListRep m) {
            out.writeByte(T_LIST_REP); out.writeLong(m.opId()); out.writeByte(m.status().ordinal());
            writeText(out, m.message(), MESSAGE_MAX); out.writeBoolean(m.truncated());
            if (m.rows().size() > MAX_ROWS) throw new ProtocolException("too many rows");
            out.writeShort(m.rows().size());
            for (Row row : m.rows()) writeRow(out, row);
        } else if (message instanceof OpRep m) {
            out.writeByte(T_OP_REP); out.writeLong(m.opId()); out.writeByte(m.op().ordinal());
            out.writeByte(m.status().ordinal()); writeText(out, m.message(), MESSAGE_MAX);
            out.writeInt(m.revision()); out.writeInt(m.currentRevision());
            out.writeBoolean(m.row() != null); if (m.row() != null) writeRow(out, m.row());
            out.writeBoolean(m.draft() != null); if (m.draft() != null) writeDraft(out, m.draft());
        } else if (message instanceof NativeRep m) {
            out.writeByte(T_NATIVE_REP); out.writeLong(m.opId()); out.writeByte(m.status().ordinal());
            writeText(out, m.message(), MESSAGE_MAX); writeText(out, m.nonce() == null ? "" : m.nonce(), NONCE_LENGTH);
        } else {
            throw new ProtocolException("unknown message");
        }
        return finish(bytes);
    }

    static ToClient decodeToClient(byte[] data) throws IOException {
        if (data == null || data.length < 2 || data.length > MAX_PACKET_BYTES) throw new ProtocolException("bad size");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readUnsignedByte() != VERSION) throw new ProtocolException("bad version");
        int type = in.readUnsignedByte();
        long op = in.readLong();
        ToClient message;
        switch (type) {
            case T_DENIED -> message = new Denied(op, readText(in, MESSAGE_MAX));
            case T_LIST_REP -> {
                Status status = readEnum(in, Status.values());
                String text = readText(in, MESSAGE_MAX);
                boolean truncated = in.readBoolean();
                int count = in.readUnsignedShort();
                if (count > MAX_ROWS) throw new ProtocolException("too many rows");
                List<Row> rows = new ArrayList<>();
                for (int i = 0; i < count; i++) rows.add(readRow(in));
                message = new ListRep(op, status, text, truncated, List.copyOf(rows));
            }
            case T_OP_REP -> {
                Op operation = readEnum(in, Op.values());
                Status status = readEnum(in, Status.values());
                String text = readText(in, MESSAGE_MAX);
                int revision = in.readInt(), current = in.readInt();
                Row row = in.readBoolean() ? readRow(in) : null;
                Draft draft = in.readBoolean() ? readDraft(in) : null;
                message = new OpRep(op, operation, status, text, revision, current, row, draft);
            }
            case T_NATIVE_REP -> {
                Status status = readEnum(in, Status.values());
                String text = readText(in, MESSAGE_MAX);
                String nonce = readText(in, NONCE_LENGTH);
                if (!nonce.isEmpty() && !NONCE.matcher(nonce).matches()) throw new ProtocolException("malformed nonce");
                message = new NativeRep(op, status, text, nonce);
            }
            default -> throw new ProtocolException("unknown type");
        }
        if (in.available() != 0) throw new ProtocolException("trailing bytes");
        return message;
    }
}
