import io.github.sirp00m.npcortex.client.SkinFileManager;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class SkinFileManagerTest {
    private static int assertions = 0;
    private static void require(boolean yes, String why) {
        assertions++;
        if (!yes) throw new AssertionError(why);
    }
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("npcortex-skin-test-");
        try {
            Path source = base.resolve("source.png");
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(image, "png", source.toFile());
            Path output = base.resolve("instance/config/npcortex/skins");
            Path created = SkinFileManager.importPng(source, output, "yui");
            require(Files.isRegularFile(created), "import creates a skin");
            require(created.equals(output.resolve("yui.png")), "target chosen by id");
            require(SkinFileManager.installed(output).contains("yui"), "enumeration");
            require(SkinFileManager.validId("forest_guard"), "valid id");
            require(!SkinFileManager.validId("../bad"), "no traversal");
            boolean rejected = false;
            try { SkinFileManager.importPng(source, output, "../x"); }
            catch (java.io.IOException expected) { rejected = true; }
            require(rejected, "reject invalid skin id");
            Path wrong = base.resolve("wrong.png");
            ImageIO.write(new BufferedImage(32, 64, BufferedImage.TYPE_INT_ARGB), "png", wrong.toFile());
            rejected = false;
            try { SkinFileManager.importPng(wrong, output, "yui"); }
            catch (java.io.IOException expected) { rejected = true; }
            require(rejected, "reject non 64x64");
            SkinFileManager.validatePng(created);
            require(SkinFileManager.installed(base.resolve("does-not-exist")).isEmpty(), "missing dir safe");
            System.out.println("NPCortex Java skin tests passed: " + assertions + " assertions");
        } finally {
            try (var paths = Files.walk(base)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
