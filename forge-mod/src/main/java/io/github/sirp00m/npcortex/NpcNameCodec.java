package io.github.sirp00m.npcortex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Wire format of the clientbound NPC name channel (npcortex:names). Pure Java so every limit is testable.
 * A sync maps an NPC's GameProfile UUID to its logical C1 name; {@code replace} makes the receiver drop
 * every earlier mapping first (login/rejoin/dimension change), otherwise the entries are added.
 */
final class NpcNameCodec {
    static final int VERSION = 1;
    static final int MAX_ENTRIES = 240;   // 240 * 33 bytes + header < MAX_PACKET_BYTES
    static final int MAX_PACKET_BYTES = 8192;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private NpcNameCodec() {}

    record Sync(boolean replace, Map<UUID, String> names) {}

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    static byte[] encode(Sync sync) throws IOException {
        if (sync.names().size() > MAX_ENTRIES) throw new IOException("too many names");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(VERSION);
        out.writeBoolean(sync.replace());
        out.writeShort(sync.names().size());
        for (Map.Entry<UUID, String> entry : sync.names().entrySet()) {
            if (!validName(entry.getValue())) throw new IOException("invalid NPC name");
            out.writeLong(entry.getKey().getMostSignificantBits());
            out.writeLong(entry.getKey().getLeastSignificantBits());
            byte[] name = entry.getValue().getBytes(StandardCharsets.US_ASCII);
            out.writeByte(name.length);
            out.write(name);
        }
        if (bytes.size() > MAX_PACKET_BYTES) throw new IOException("message too large");
        return bytes.toByteArray();
    }

    /** Anything malformed is rejected whole: a half-applied name map would show wrong names. */
    static Sync decode(byte[] data) throws IOException {
        if (data == null || data.length < 4 || data.length > MAX_PACKET_BYTES) throw new IOException("bad size");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readUnsignedByte() != VERSION) throw new IOException("bad version");
        boolean replace = in.readBoolean();
        int count = in.readUnsignedShort();
        if (count > MAX_ENTRIES) throw new IOException("too many names");
        Map<UUID, String> names = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            UUID id = new UUID(in.readLong(), in.readLong());
            int length = in.readUnsignedByte();
            if (length < 1 || length > 16 || length > in.available()) throw new IOException("bad name length");
            byte[] raw = new byte[length];
            in.readFully(raw);
            String name = new String(raw, StandardCharsets.US_ASCII);
            if (!validName(name)) throw new IOException("invalid NPC name");
            if (names.put(id, name) != null) throw new IOException("duplicate id");
        }
        if (in.available() != 0) throw new IOException("trailing bytes");
        return new Sync(replace, names);
    }
}
