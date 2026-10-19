package io.dagger.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratorTest {

  @TempDir Path plan;
  @TempDir Path out;
  @TempDir Path classes;

  @Test
  void everyPackageThePlanNamesIsEmittedOnce() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(emitted())
        .contains(
            "io/dagger/client/Client.java",
            "io/dagger/client/Container.java",
            "io/dagger/client/modules/alpha/Alpha.java",
            "io/dagger/client/modules/alpha/AlphaReport.java",
            "io/dagger/client/modules/beta/Beta.java");
    assertThat(emitted()).doesNotContain("io/dagger/client/Alpha.java");
  }

  /** A target is reached from its own package, so no core source names one. */
  @Test
  void coreNamesNoClientPackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    for (String source : emitted()) {
      if (source.startsWith("io/dagger/client/modules/")) {
        continue;
      }
      assertThat(read(source))
          .as("core source %s", source)
          .doesNotContain("io.dagger.client.modules")
          .doesNotContain("ModuleTarget");
    }
    assertThat(read("io/dagger/client/Client.java")).doesNotContain("Alpha").doesNotContain("Beta");
  }

  /** The way into a target is a static method on its root type, taking the session it runs in. */
  @Test
  void aTargetIsEnteredFromItsOwnPackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains("import io.dagger.client.Session;")
        .contains("public static Alpha alpha(Session session, String source)")
        .contains("public static Alpha alpha(String source)")
        .contains("return alpha(Dagger.dag(), source)");
    assertThat(read("io/dagger/client/modules/beta/Beta.java"))
        .contains("public static Beta beta(Session session)")
        .contains("public static Beta beta()");
  }

  /** A target's optional constructor arguments travel with the method that takes them. */
  @Test
  void theArgumentsHolderOfAnEntryPointIsNestedInTheRootType() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains("public static class AlphaArguments")
        .contains(
            "public static Alpha alpha(Session session, String source, AlphaArguments optArgs)")
        .contains("public static Alpha alpha(String source, AlphaArguments optArgs)");
    assertThat(read("io/dagger/client/Client.java")).doesNotContain("AlphaArguments");
  }

  /**
   * The target rides on the chain the entry point starts, so whatever runs the chain serves it
   * first: the field selected here does not exist until the target is served.
   */
  @Test
  void anEntryPointChainsFromTheSessionCarryingItsTarget() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(methodOf(read("io/dagger/client/modules/beta/Beta.java"), "beta(Session"))
        .isEqualTo(
            """
                public static Beta beta(Session session) {
                    QueryBuilder nextQueryBuilder = session.queryBuilder().withTarget(TARGET).chain("beta");
                    return new Beta(nextQueryBuilder);
                }
            """
                .stripTrailing());
  }

  /** A field a module contributes to another core type moves with it, receiver and all. */
  @Test
  void aTargetsContributionToACoreTypeIsEnteredFromItsOwnPackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains("import io.dagger.client.Binding;")
        .contains("public static Alpha asAlpha(Binding binding)");
    assertThat(read("io/dagger/client/Binding.java")).doesNotContain("asAlpha");
    assertThat(emitted()).doesNotContain("io/dagger/client/modules/alpha/Binding.java");
  }

  /**
   * The receiver's chain names the session the target has to be served into. Starting from the
   * session {@code Dagger.dag()} holds would land the target beside the caller's query rather than
   * in it, so the receiver is the only argument.
   */
  @Test
  void aContributionToACoreTypeChainsFromItsReceiver() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    String alpha = read("io/dagger/client/modules/alpha/Alpha.java");
    assertThat(methodOf(alpha, "asAlpha("))
        .isEqualTo(
            """
                public static Alpha asAlpha(Binding binding) {
                    QueryBuilder nextQueryBuilder = binding.queryBuilder().withTarget(TARGET).chain("asAlpha");
                    return new Alpha(nextQueryBuilder);
                }
            """
                .stripTrailing());
    assertThat(alpha).doesNotContain("asAlpha(Session").doesNotContain("asAlpha()");
  }

  @Test
  void aTargetsPackageReachesCoreTypesInTheCorePackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains("import io.dagger.client.Container;");
  }

  /** The descriptor is an address and, for git, a pin: the engine names the module it serves. */
  @Test
  void aTargetCarriesItsDescriptorAndNoName() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), gitTarget("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains(
            "private static final ModuleTarget TARGET ="
                + " ModuleTarget.local(\"/dagger/modules/alpha\");");
    assertThat(read("io/dagger/client/modules/beta/Beta.java"))
        .contains(
            "private static final ModuleTarget TARGET ="
                + " ModuleTarget.git(\"github.com/dagger/beta@v1\", \"0123abc\");");
  }

  @Test
  void aCoreTypeCarriesNoTarget() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/Container.java"))
        .doesNotContain("withTarget")
        .doesNotContain("TARGET");
  }

  /**
   * A client package names core classes and the hand-written runtime from another package, so
   * everything it reaches has to be public: checked by compiling it against stubs of the runtime
   * that mirror its signatures.
   */
  @Test
  void aClientPackageCompilesAgainstCoreFromAnotherPackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), gitTarget("beta", BETA));

    generate();

    assertCompiles(
        Stream.of(
            "io/dagger/client/modules/", "io/dagger/client/Container", "io/dagger/client/Binding"));
  }

  @Test
  void theSamePlanGeneratesTheSameBytesWhateverOrderItsEntriesAreLaidOutIn() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));
    generate();
    String first = read("io/dagger/client/Client.java");

    Path reversed = Files.createTempDirectory("plan-reversed");
    writePlanAt(reversed, CORE_WITH_TWO_TARGETS, target("beta", BETA), target("alpha", ALPHA));
    Path secondOut = Files.createTempDirectory("out-reversed");
    new Generator(secondOut, StandardCharsets.UTF_8, VERSION)
        .generate(GenerationPlan.read(reversed));

    assertThat(Files.readString(secondOut.resolve("io/dagger/client/Client.java")))
        .isEqualTo(first);
  }

  @Test
  void aModuleTheCoreSchemaMentionsButThePlanDoesNotGenerateIsRefused() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beta");
  }

  /** Core is reserved for the engine's own API, which becomes a client package of its own. */
  @Test
  void aTargetNamedCoreIsRefused() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("core", ALPHA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("io.dagger.client.modules.core");
  }

  /**
   * The engine hides part of core from module code, so a target's client-facing core holds types
   * and fields the module-facing one does not. Only the plan's core is emitted.
   */
  @Test
  void aTargetWhoseCoreIsWiderThanThePlansIsAccepted() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA_ON_A_WIDER_CORE), target("beta", BETA));

    generate();

    assertThat(emitted()).contains("io/dagger/client/modules/alpha/Alpha.java");
    assertThat(emitted()).doesNotContain("io/dagger/client/Host.java");
  }

  /**
   * A target's local types are namespaced under its own name, so {@code foo} and {@code foo-bar}
   * can both claim {@code FooBar} while their packages, {@code foo} and {@code foobar}, do not
   * collide.
   */
  @Test
  void twoTargetsOwningOneTypeNameAreRefused() throws Exception {
    writePlan(
        CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA_OWNING_ALPHAS_TYPE));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alpha")
        .hasMessageContaining("beta")
        .hasMessageContaining("AlphaReport");
  }

  @Test
  void aTargetOwningATypeNameCoreAlsoHasIsRefused() throws Exception {
    writePlan(
        CORE_WITH_TWO_TARGETS, target("alpha", ALPHA_OWNING_A_CORE_TYPE), target("beta", BETA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alpha")
        .hasMessageContaining("Container");
  }

  @Test
  void aPlanWithoutACoreSchemaIsRefused() throws Exception {
    writePlanAt(plan, null, target("alpha", ALPHA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("core/schema.json");
  }

  @Test
  void aTargetWithoutADescriptorIsRefused() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, new Entry("alpha", ALPHA, null), target("beta", BETA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("descriptor");
  }

  @Test
  void aGitTargetWithoutAPinIsRefused() throws Exception {
    writePlan(
        CORE_WITH_TWO_TARGETS,
        new Entry("alpha", ALPHA, "git\ngithub.com/dagger/alpha@v1"),
        target("beta", BETA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("not a target descriptor");
  }

  @Test
  void aLocalTargetOutsideTheWorkspaceRootIsRefused() throws Exception {
    writePlan(
        CORE_WITH_TWO_TARGETS,
        new Entry("alpha", ALPHA, "local\nmodules/alpha"),
        target("beta", BETA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("workspace-absolute");
  }

  private void generate() throws IOException {
    new Generator(out, StandardCharsets.UTF_8, VERSION).generate(GenerationPlan.read(plan));
  }

  private record Entry(String module, String schema, String descriptor) {}

  private static Entry target(String module, String schema) {
    return new Entry(module, schema, "local\n/dagger/modules/" + module);
  }

  private static Entry gitTarget(String module, String schema) {
    return new Entry(module, schema, "git\ngithub.com/dagger/" + module + "@v1\n0123abc");
  }

  private void writePlan(String coreSchema, Entry... targets) throws IOException {
    writePlanAt(plan, coreSchema, targets);
  }

  private static void writePlanAt(Path root, String coreSchema, Entry... targets)
      throws IOException {
    if (coreSchema != null) {
      Files.createDirectories(root.resolve("core"));
      Files.writeString(root.resolve("core/schema.json"), coreSchema);
    }
    for (int i = 0; i < targets.length; i++) {
      Path entry = root.resolve("target-" + i);
      Files.createDirectories(entry);
      Files.writeString(entry.resolve("schema.json"), targets[i].schema());
      Files.writeString(entry.resolve("module"), targets[i].module());
      if (targets[i].descriptor() != null) {
        Files.writeString(entry.resolve("descriptor"), targets[i].descriptor());
      }
    }
  }

  private List<String> emitted() throws IOException {
    try (Stream<Path> files = Files.walk(out)) {
      return files
          .filter(Files::isRegularFile)
          .map(path -> out.relativize(path).toString())
          .sorted()
          .toList();
    }
  }

  /** One generated method, from its signature to the line that closes it. */
  private static String methodOf(String source, String signature) {
    List<String> lines = source.lines().toList();
    int start =
        IntStream.range(0, lines.size())
            .filter(i -> lines.get(i).contains(" " + signature))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no method " + signature));
    int end = start;
    while (!lines.get(end).equals("    }")) {
      end++;
    }
    return String.join("\n", lines.subList(start, end + 1));
  }

  private String read(String relative) throws IOException {
    return Files.readString(out.resolve(relative));
  }

  /** Compile the emitted sources under the given prefixes, with the runtime stubbed. */
  private void assertCompiles(Stream<String> prefixes) throws IOException {
    List<String> wanted = prefixes.toList();
    List<JavaFileObject> units = new ArrayList<>();
    for (String source : emitted()) {
      if (wanted.stream().anyMatch(source::startsWith)) {
        units.add(new SourceFile(source, read(source)));
      }
    }
    RUNTIME_STUBS.forEach((name, body) -> units.add(new SourceFile(name, body)));

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    boolean compiled =
        compiler
            .getTask(
                null,
                null,
                diagnostics,
                List.of("--release", "17", "-proc:none", "-d", classes.toString()),
                null,
                units)
            .call();

    assertThat(compiled)
        .withFailMessage(
            "Generated sources did not compile:%n%s",
            diagnostics.getDiagnostics().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n")))
        .isTrue();
  }

  /**
   * The runtime a client package is written against: {@code Session}, {@code ModuleTarget} and
   * {@code QueryBuilder.withTarget}. {@code Dagger.dag()} returns the session, which is the shape
   * the ambient entry point needs.
   */
  private static final Map<String, String> RUNTIME_STUBS =
      Map.of(
          "io/dagger/client/QueryBuilder.java",
          """
          package io.dagger.client;
          public final class QueryBuilder {
            public QueryBuilder withTarget(ModuleTarget target) { return this; }
            public QueryBuilder chain(String field) { return this; }
            public QueryBuilder chain(String field, Arguments args) { return this; }
          }
          """,
          "io/dagger/client/Arguments.java",
          """
          package io.dagger.client;
          public class Arguments {
            public static Builder newBuilder() { return new Builder(); }
            public Arguments merge(Arguments other) { return this; }
            public static class Builder {
              public Builder add(String name, Object value) { return this; }
              public Arguments build() { return new Arguments(); }
            }
          }
          """,
          "io/dagger/client/ModuleTarget.java",
          """
          package io.dagger.client;
          public record ModuleTarget(String address, String pin) {
            public static ModuleTarget local(String address) { return new ModuleTarget(address, null); }
            public static ModuleTarget git(String address, String pin) { return new ModuleTarget(address, pin); }
          }
          """,
          "io/dagger/client/Session.java",
          """
          package io.dagger.client;
          public class Session {
            public QueryBuilder queryBuilder() { return null; }
          }
          """,
          "io/dagger/client/Dagger.java",
          """
          package io.dagger.client;
          public class Dagger {
            public static Session dag() { return null; }
          }
          """);

  private static final class SourceFile extends SimpleJavaFileObject {
    private final String source;

    private SourceFile(String path, String source) {
      super(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }

  private static final String VERSION = "v1.0.0-beta.18";

  private static String owned(String module) {
    return "\"directives\": [{\"name\": \"sourceMap\", \"args\": [{\"name\": \"module\","
        + " \"value\": \"\\\""
        + module
        + "\\\"\"}]}]";
  }

  private static final String CORE_WITH_TWO_TARGETS =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "alpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [{"name": "source", "type": {"kind": "NON_NULL", "ofType": {"kind": "SCALAR", "name": "String"}}}, {"name": "tag", "type": {"kind": "SCALAR", "name": "String"}}], %s},
          {"name": "beta", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Beta"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": [
          {"name": "asAlpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Alpha", "fields": [], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s},
        {"kind": "OBJECT", "name": "Beta", "fields": [], %s}
      ]}}
      """
          .formatted(
              owned("alpha"),
              owned("beta"),
              owned("alpha"),
              owned("alpha"),
              owned("alpha"),
              owned("beta"));

  private static final String ALPHA =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "alpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [{"name": "source", "type": {"kind": "NON_NULL", "ofType": {"kind": "SCALAR", "name": "String"}}}, {"name": "tag", "type": {"kind": "SCALAR", "name": "String"}}], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": [
          {"name": "asAlpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Alpha", "fields": [
          {"name": "base", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []}
        ], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("alpha"), owned("alpha"), owned("alpha"), owned("alpha"));

  /**
   * Alpha's client-facing core carries a type and a field the module-facing core does not, the way
   * a real one carries Host and Query.host.
   */
  private static final String ALPHA_ON_A_WIDER_CORE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "host", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Host"}}, "args": []},
          {"name": "alpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Host", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": []},
        {"kind": "OBJECT", "name": "Alpha", "fields": [], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("alpha"), owned("alpha"), owned("alpha"));

  /** Beta owns a type alpha owns too, as two targets whose namespaced names meet would. */
  private static final String BETA_OWNING_ALPHAS_TYPE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "beta", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Beta"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Beta", "fields": [], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("beta"), owned("beta"), owned("beta"));

  /** Alpha owns a type core has, which no target may take over. */
  private static final String ALPHA_OWNING_A_CORE_TYPE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "alpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [{"name": "source", "type": {"kind": "NON_NULL", "ofType": {"kind": "SCALAR", "name": "String"}}}, {"name": "tag", "type": {"kind": "SCALAR", "name": "String"}}], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": [], %s},
        {"kind": "OBJECT", "name": "Alpha", "fields": [], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("alpha"), owned("alpha"), owned("alpha"), owned("alpha"));

  private static final String BETA =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "beta", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Beta"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": []},
        {"kind": "OBJECT", "name": "Beta", "fields": [], %s}
      ]}}
      """
          .formatted(owned("beta"), owned("beta"));
}
