package io.github.sirp00m.npcortex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** The world-folder part of the native binding. File-system access lives here (not in the pure {@link NativeEffectFlow}). */
final class NativeWorldKey {
    private NativeWorldKey() {}

    /** Readable basename + 16 hex of SHA-256 over the full real path, so two worlds that only share a folder name (different
     * parents) get different keys. Limit: a copy at the SAME path, or a link/junction/case change the OS does not resolve, is not
     * detected; it is a guard against accidents, not proof of identity. Max 117 chars (backend limit 128). */
    static String folderKey(Path root) {
        Path full;
        try { full = root.toRealPath(); } catch (IOException | RuntimeException e) { full = root.toAbsolutePath().normalize(); }
        String name = full.getFileName() == null ? "world" : full.getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9 ._()\\[\\]+,=@#!-]", "_");
        if (name.length() > 100) name = name.substring(0, 100);
        if (name.isEmpty()) name = "world";
        return name + "-" + WorldActionPolicy.sha(full.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
