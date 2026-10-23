package io.github.sirp00m.npcortex.cplus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.regex.Pattern;

/** Forge reads the backend-generated local bridge secret; no token setup in PowerShell.
 * Never create or change credentials in the Minecraft server process.
 */
public final class CPlusLocalAuth {
    private CPlusLocalAuth() {}
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    public static Path secretPath() throws IOException {
        String override=System.getenv("NPCORTEX_CPLUS_F1_SECRET_FILE");
        if(override!=null && !override.isBlank()) {
            Path value=Path.of(override.trim());
            if(!value.isAbsolute()) throw new IOException("C-F1 shared secret path must be absolute");
            return value;
        }
        String os=System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        Path base;
        if(os.contains("win")) {
            String local=System.getenv("LOCALAPPDATA");
            base=local==null || local.isBlank()
                    ? Path.of(System.getProperty("user.home"),"AppData","Local") : Path.of(local);
        } else {
            String xdg=System.getenv("XDG_CONFIG_HOME");
            base=xdg!=null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                    ? Path.of(xdg) : Path.of(System.getProperty("user.home"),".config");
        }
        return base.resolve("NPCortex").resolve("bridge").resolve("cplus-f1.secret");
    }

    public static String fromFile(Path file) throws IOException {
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file))
            throw new IOException("C-F1 local secret is not a regular private file");
        try {
            Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE))
                throw new IOException("C-F1 local secret is not private (expected owner-only permissions)");
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs are managed by the owning Windows profile.
        }
        if (Files.size(file)>128) throw new IOException("C-F1 local secret too large");
        String secret=Files.readString(file,StandardCharsets.US_ASCII).strip();
        if (!HEX.matcher(secret).matches()) throw new IOException("C-F1 local secret invalid; no auto-rotation");
        return secret;
    }

    public static String readToken() throws IOException {
        String manual=System.getenv("NPCORTEX_CPLUS_F1_TOKEN");
        if(manual!=null && !manual.isEmpty()) {
            if(manual.length()<24 || manual.length()>512 || manual.contains("\n") || manual.contains("\r"))
                throw new IOException("C-F1 manually configured token is invalid");
            return manual;
        }
        return fromFile(secretPath());
    }
}
