package io.dagger.annotation.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the processor in javac over module sources, as generation does. */
class CollectionProcessingTest {

  private static final String ITEM =
      """
      package demo;

      import io.dagger.module.annotation.Object;

      @Object
      public class Item {
        public String name;
      }
      """;

  private static final String MAIN =
      """
      package demo;

      import io.dagger.module.annotation.Object;

      @Object
      public class Main {}
      """;

  @TempDir Path out;

  @Test
  void aCollectionWhoseGetLacksFunctionIsRefused() {
    String items =
        """
        package demo;

        import io.dagger.module.annotation.Collection;
        import io.dagger.module.annotation.Get;
        import io.dagger.module.annotation.Keys;
        import io.dagger.module.annotation.Object;
        import java.util.List;

        @Collection
        @Object
        public class Items {
          @Keys public List<String> names;

          @Get
          public Item lookup(String name) {
            return new Item();
          }
        }
        """;

    assertThatThrownBy(() -> process(Map.of("Item", ITEM, "Items", items, "Main", MAIN)))
        .rootCause()
        .hasMessage(
            "Method 'lookup' of 'Items' is marked @Get but not @Function. Add @Function: a"
                + " collection's get function must be exposed.");
  }

  @Test
  void aBareGetBesideAMarkedGetFunctionStillLoads() throws Exception {
    String dang = process(Map.of("Item", ITEM, "Main", collectionMain("@Function @Get")));

    assertThat(dang).contains("function(\"item\", typeDef.withObject(\"Item\"))");
    assertThat(dang).doesNotContain("\"helper\"");
  }

  @Test
  void aBareGetBesideAFunctionNamedGetStillLoads() throws Exception {
    String dang = process(Map.of("Item", ITEM, "Main", collectionMain("@Function(\"get\")")));

    assertThat(dang).contains("function(\"get\", typeDef.withObject(\"Item\"))");
    assertThat(dang).doesNotContain("\"helper\"");
  }

  @Test
  void aBareGetOutsideACollectionStillLoads() throws Exception {
    String main =
        """
        package demo;

        import io.dagger.module.annotation.Get;
        import io.dagger.module.annotation.Object;

        @Object
        public class Main {
          @Get
          public String helper(String name) {
            return name;
          }
        }
        """;

    assertThat(process(Map.of("Main", main))).doesNotContain("\"helper\"");
  }

  /** Like Python's keys() and delta(), the markers expose a field whatever its visibility. */
  @Test
  void theKeysAndDeltaFieldsAreStateWhateverTheirVisibility() throws Exception {
    String items =
        """
        package demo;

        import io.dagger.client.modules.core.CollectionDelta;
        import io.dagger.module.annotation.Collection;
        import io.dagger.module.annotation.Delta;
        import io.dagger.module.annotation.Function;
        import io.dagger.module.annotation.Get;
        import io.dagger.module.annotation.Keys;
        import io.dagger.module.annotation.Object;
        import java.util.List;

        @Object
        @Collection
        public class Items {
          @Keys private List<String> names;
          @Delta private CollectionDelta selection;
          private String prefix;

          @Function
          @Get
          public Item lookup(String name) {
            return new Item();
          }
        }
        """;

    String dang = process(Map.of("Item", ITEM, "Items", items, "Main", MAIN));

    assertThat(dang)
        .contains(
            ".withField(\"names\", typeDef.withListOf(typeDef.withKind(TypeDefKind.STRING_KIND)))")
        .contains(".withField(\"selection\", typeDef.withObject(\"CollectionDelta\"))")
        .doesNotContain("\"prefix\"");
  }

  /** The members carry names of their own, so the engine can find them only by the markers. */
  @Test
  void bothBackendsDeclareTheCollectionAndItsMembers() throws Exception {
    String items =
        """
        package demo;

        import io.dagger.client.modules.core.CollectionDelta;
        import io.dagger.module.annotation.Collection;
        import io.dagger.module.annotation.Delta;
        import io.dagger.module.annotation.Function;
        import io.dagger.module.annotation.Get;
        import io.dagger.module.annotation.Keys;
        import io.dagger.module.annotation.Object;
        import java.util.List;

        @Object
        @Collection
        public class Items {
          @Keys public List<String> names;
          @Delta public CollectionDelta selection;

          @Function("find")
          @Get
          public Item lookup(String name) {
            return new Item();
          }
        }
        """;

    String dang = process(Map.of("Item", ITEM, "Items", items, "Main", MAIN));

    assertThat(dang)
        .contains(
            String.join(
                "\n",
                "      typeDef.withObject(\"Items\")",
                "        .withCollection",
                "        .withCollectionKeys(\"names\")",
                "        .withCollectionDelta(\"selection\")",
                "        .withCollectionGet(\"find\")",
                "        .withFunction("));
    assertThat(entrypoint())
        .contains(
            String.join(
                "\n",
                "            core().typeDef().withObject(\"Items\")",
                "                .withCollection()",
                "                .withCollectionKeys(\"names\")",
                "                .withCollectionDelta(\"selection\")",
                "                .withCollectionGet(\"find\")",
                "                .withFunction("));
  }

  /** Without the markers, the engine looks for members named keys and get. */
  @Test
  void anUnmarkedCollectionDeclaresOnlyThatItIsOne() throws Exception {
    String items =
        """
        package demo;

        import io.dagger.module.annotation.Collection;
        import io.dagger.module.annotation.Function;
        import io.dagger.module.annotation.Object;
        import java.util.List;

        @Object
        @Collection
        public class Items {
          public List<String> keys;

          @Function
          public Item get(String key) {
            return new Item();
          }
        }
        """;

    String dang = process(Map.of("Item", ITEM, "Items", items, "Main", MAIN));

    assertThat(dang).contains("typeDef.withObject(\"Items\")\n        .withCollection\n");
    assertThat(dang)
        .doesNotContain("withCollectionKeys", "withCollectionGet", "withCollectionDelta");
    assertThat(entrypoint())
        .contains(".withCollection()")
        .doesNotContain("withCollectionKeys", "withCollectionGet", "withCollectionDelta");
  }

  /** Markers outside a collection would make the engine refuse the object, so none are sent. */
  @Test
  void membersMarkedOutsideACollectionAreNotDeclared() throws Exception {
    String main =
        """
        package demo;

        import io.dagger.module.annotation.Function;
        import io.dagger.module.annotation.Get;
        import io.dagger.module.annotation.Keys;
        import io.dagger.module.annotation.Object;
        import java.util.List;

        @Object
        public class Main {
          @Keys public List<String> names;

          @Function
          @Get
          public String lookup(String name) {
            return name;
          }
        }
        """;

    assertThat(process(Map.of("Main", main))).doesNotContain("withCollection");
    assertThat(entrypoint()).doesNotContain("withCollection");
  }

  private static String collectionMain(String getAnnotations) {
    return """
        package demo;

        import io.dagger.module.annotation.Collection;
        import io.dagger.module.annotation.Function;
        import io.dagger.module.annotation.Get;
        import io.dagger.module.annotation.Keys;
        import io.dagger.module.annotation.Object;
        import java.util.List;

        @Object
        @Collection
        public class Main {
          @Keys public List<String> names;

          @Get
          public Item helper(String name) {
            return new Item();
          }

          %s
          public Item item(String name) {
            return new Item();
          }
        }
        """
        .formatted(getAnnotations);
  }

  /** Process the sources and return the Dang entrypoint the processor wrote. */
  private String process(Map<String, String> sources) throws IOException, URISyntaxException {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Path classes = Files.createDirectories(out.resolve("classes"));
    Path generated = Files.createDirectories(out.resolve("generated"));
    List<JavaFileObject> units = new ArrayList<>();
    sources.forEach((name, source) -> units.add(new Source("demo/" + name, source)));
    try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, null)) {
      JavaCompiler.CompilationTask task =
          javac.getTask(
              null,
              files,
              diagnostics,
              List.of(
                  "-proc:only",
                  "-d",
                  classes.toString(),
                  "-s",
                  generated.toString(),
                  "-classpath",
                  sdkClasspath()),
              null,
              units);
      task.setProcessors(List.of(new DaggerModuleAnnotationProcessor()));
      assertThat(task.call()).as(diagnostics.getDiagnostics().toString()).isTrue();
    }
    return Files.readString(classes.resolve("dagger/entrypoint/main.dang"));
  }

  /**
   * The Java entrypoint the last {@link #process} generated, whose register() serves the runtime.
   */
  private String entrypoint() throws IOException {
    return Files.readString(out.resolve("generated/io/dagger/gen/entrypoint/Entrypoint.java"));
  }

  /** The SDK library, which holds the annotations and the core client the sources import. */
  private static String sdkClasspath() throws URISyntaxException {
    return Path.of(
            io.dagger.module.annotation.Object.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI())
        .toString();
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String source;

    Source(String path, String source) {
      super(java.net.URI.create("string:///" + path + Kind.SOURCE.extension), Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }
}
