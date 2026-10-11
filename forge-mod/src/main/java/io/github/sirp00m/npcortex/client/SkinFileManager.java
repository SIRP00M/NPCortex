package io.github.sirp00m.npcortex.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

/** Plain Java file operations, isolated from Minecraft so they can be tested. */
public final class SkinFileManager {
    public static final long MAX_SIZE = 1_048_576L;

    private SkinFileManager() {}

    public static boolean validId(String id) {
        return id != null && id.matches("[a-z][a-z0-9_-]{0,39}");
    }

    public static void validatePng(Path source) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Please select an existing PNG file (not a link).");
        long size = Files.size(source);
        if (size < 33 || size > MAX_SIZE) throw new IOException("PNG must be 1 MiB or smaller.");
        // Read the PNG signature and IHDR dimensions before decoding. This avoids
        // asking ImageIO to allocate a potentially huge malicious image.
        try (DataInputStream header = new DataInputStream(Files.newInputStream(source))) {
            byte[] signature = new byte[8];
            header.readFully(signature);
            byte[] expected = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
            if (!java.util.Arrays.equals(signature, expected)) throw new IOException("Not a PNG file.");
            if (header.readInt() != 13 || header.readInt() != 0x49484452) // IHDR
                throw new IOException("Malformed PNG header.");
            if (header.readInt() != 64 || header.readInt() != 64)
                throw new IOException("Expected a 64x64 Minecraft Java Edition PNG skin.");
        }
        try (InputStream stream = Files.newInputStream(source)) {
            BufferedImage image = ImageIO.read(stream);
            if (image == null || image.getWidth() != 64 || image.getHeight() != 64)
                throw new IOException("Unreadable 64x64 PNG skin.");
        }
    }

    /** Never uses the imported filename as a target path. No path traversal. */
    public static Path importPng(Path source, Path skinsDirectory, String skinId) throws IOException {
        if (!validId(skinId)) throw new IOException("Skin ID must be lowercase (a-z, 0-9, _ or -).");
        validatePng(source);
        Files.createDirectories(skinsDirectory);
        Path dest = skinsDirectory.resolve(skinId + ".png");
        if (Files.isSymbolicLink(dest)) throw new IOException("Destination cannot be a symbolic link.");
        Path temp = Files.createTempFile(skinsDirectory, "npcortex-", ".png.tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return dest;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static List<String> installed(Path skinsDirectory) {
        if (!Files.isDirectory(skinsDirectory)) return List.of();
        List<String> ids = new ArrayList<>();
        try (var stream = Files.list(skinsDirectory)) {
            stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".png"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .filter(SkinFileManager::validId)
                    .forEach(ids::add);
        } catch (IOException ignored) {
            return List.of();
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }
}
