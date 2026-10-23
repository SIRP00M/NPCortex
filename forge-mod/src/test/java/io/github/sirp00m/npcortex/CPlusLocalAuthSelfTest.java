package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.cplus.CPlusLocalAuth;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Standalone JDK 17 local-auth contract; optional arg is Python-created shared secret path. */
public final class CPlusLocalAuthSelfTest {
    private static int checks=0;
    private static void yes(boolean value) { checks++; if(!value) throw new AssertionError("check "+checks); }
    private static boolean fails(Path p) {
        try { CPlusLocalAuth.fromFile(p); return false; }
        catch (java.io.IOException expected) { return true; }
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("npcortex-cplus-local-auth-");
        Path valid=dir.resolve("test.secret");
        String fixture="0123456789abcdef".repeat(4);
        try {
            yes(fails(valid));
            Files.writeString(valid,fixture+"\n");
            try { Files.setPosixFilePermissions(valid,PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* Windows */ }
            yes(CPlusLocalAuth.fromFile(valid).equals(fixture));
            yes(CPlusLocalAuth.fromFile(valid).length()==64);
            Files.writeString(valid,"invalid-or-partial");
            yes(fails(valid));
            Files.writeString(valid,"a".repeat(129));
            yes(fails(valid));
            Files.writeString(valid,fixture+"\n");
            try {
                Files.setPosixFilePermissions(valid,PosixFilePermissions.fromString("rw-r--r--"));
                yes(fails(valid));
                Files.setPosixFilePermissions(valid,PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) { /* Windows ACLs */ }
            yes(CPlusLocalAuth.fromFile(valid).equals(fixture));
            if (args.length==1) {
                String python=Files.readString(Path.of(args[0])).strip();
                yes(CPlusLocalAuth.fromFile(Path.of(args[0])).equals(python));
                yes(python.length()==64);
            }
        } finally {
            Files.deleteIfExists(valid);
            Files.deleteIfExists(dir);
        }
        System.out.println("PASS C+ local authentication Java checks="+checks);
    }
}
