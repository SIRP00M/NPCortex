import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Parse every Forge Java file without needing a full Minecraft/Forge classpath.
 *  This is a syntax check, NOT Forge compilation or runtime verification.
 */
public final class JavaSyntaxSmokeCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Provide Forge Java source root");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK required (javac unavailable)");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var stream = Files.walk(Path.of(args[0]));
             StandardJavaFileManager files = compiler.getStandardFileManager(
                     diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<Path> sources = stream.filter(p -> p.toString().endsWith(".java")).toList();
            if (sources.isEmpty()) throw new IOException("No Java files in Forge source root");
            JavacTask parser = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("--release", "17", "-proc:none"), null,
                    files.getJavaFileObjectsFromPaths(sources));
            parser.parse();
            int errors = 0;
            for (Diagnostic<? extends JavaFileObject> message : diagnostics.getDiagnostics()) {
                if (message.getKind() == Diagnostic.Kind.ERROR) {
                    System.err.println(message);
                    errors++;
                }
            }
            System.out.println("Forge Java 17 syntax: " + sources.size() + " files; " + errors + " errors");
            if (errors > 0) System.exit(1);
        }
    }
}
