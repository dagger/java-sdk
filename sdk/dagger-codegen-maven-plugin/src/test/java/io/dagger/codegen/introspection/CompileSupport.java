package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Compiles generated sources in memory, so a test can prove they are valid Java. */
final class CompileSupport {

  private CompileSupport() {}

  static void assertCompiles(Path outputDirectory, Map<String, String> sources) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).isNotNull();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    List<JavaFileObject> compilationUnits =
        sources.entrySet().stream()
            .map(entry -> new SourceFile(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
    boolean compiled =
        compiler
            .getTask(
                null,
                null,
                diagnostics,
                List.of("--release", "17", "-proc:none", "-d", outputDirectory.toString()),
                null,
                compilationUnits)
            .call();
    assertThat(compiled)
        .withFailMessage(
            "Generated sources did not compile:%n%s",
            diagnostics.getDiagnostics().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n")))
        .isTrue();
  }

  private static final class SourceFile extends SimpleJavaFileObject {
    private final String source;

    SourceFile(String qualifiedName, String source) {
      super(
          URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension),
          Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }
}
