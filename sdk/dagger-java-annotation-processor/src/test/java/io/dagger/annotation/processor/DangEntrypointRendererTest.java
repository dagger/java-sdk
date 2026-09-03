package io.dagger.annotation.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.dagger.module.info.EnumInfo;
import io.dagger.module.info.EnumValueInfo;
import io.dagger.module.info.FieldInfo;
import io.dagger.module.info.FunctionInfo;
import io.dagger.module.info.ModuleInfo;
import io.dagger.module.info.ObjectInfo;
import io.dagger.module.info.ParameterInfo;
import io.dagger.module.info.TypeInfo;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.type.TypeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DangEntrypointRendererTest {

  private static final String ENUM_QNAME = "io.dagger.modules.demo.Severity";

  @AfterEach
  void resetKnownEnums() {
    DaggerType.setKnownEnums(Set.of());
  }

  @Test
  void theEntrypointImplementsTheThreeModuleEntrypointFields() {
    String dang = DangEntrypointRenderer.render(module());

    assertThat(dang)
        .contains("type Entrypoint implements ModuleEntrypoint {")
        .contains("pub types(workspace: Workspace!): [TypeDef!]! {")
        .contains("pub call(")
        .contains("): JSON! {");
  }

  /**
   * The interface has two fields. An earlier informal draft had a third, {@code main}, naming the
   * module's root object; the engine instead finds the object that declares a constructor.
   */
  @Test
  void theInterfaceHasNoMainField() {
    assertThat(DangEntrypointRenderer.render(module())).doesNotContain("main(");
  }

  /**
   * The same schema {@code register()} builds at run time, as Dang. Pinned whole rather than by
   * fragments: nothing parses this output before an engine loads it, so the layout is the
   * artifact.
   */
  @Test
  void typesDescribesEveryObjectAndEnum() {
    String dang = DangEntrypointRenderer.render(module());

    assertThat(dang)
        .contains(
            String.join(
                "\n",
                "  pub types(workspace: Workspace!): [TypeDef!]! {",
                "    [",
                "      typeDef.withObject(\"Demo\", description: \"A demo module\")",
                "        .withFunction(",
                "          function(\"greet\", typeDef.withKind(TypeDefKind.STRING_KIND))",
                "            .withDescription(\"Say hello\")",
                "            .withArg(\"name\", typeDef.withKind(TypeDefKind.STRING_KIND), description: \"who to greet\")",
                "            .withArg(\"loud\", typeDef.withKind(TypeDefKind.BOOLEAN_KIND).withOptional(true))",
                "            .withArg(\"level\", typeDef.withEnum(\"Severity\"))",
                "        )",
                "        .withField(\"source\", typeDef.withObject(\"Directory\"), description: \"the source tree\")",
                "        .withConstructor(",
                "          function(\"\", typeDef.withObject(\"Demo\"))",
                "            .withArg(\"prefix\", typeDef.withKind(TypeDefKind.STRING_KIND), defaultValue: JSON.decode(\"\\\"Hi\\\"\"))",
                "        ),",
                "      typeDef.withEnum(\"Severity\")",
                "        .withEnumValue(\"HIGH\", description: \"very bad\")",
                "        .withEnumValue(\"LOW\")",
                "    ]",
                "  }"));
  }

  /**
   * Argument optionality is carried on ParameterInfo, not on the type, so a Dang backend that only
   * asks DaggerType would silently make every optional argument required.
   */
  @Test
  void anOptionalArgumentIsRegisteredAsOptional() {
    String dang = DangEntrypointRenderer.render(module());

    assertThat(dang)
        .contains(".withArg(\"loud\", typeDef.withKind(TypeDefKind.BOOLEAN_KIND).withOptional(true))");
  }

  @Test
  void anEnumTypedArgumentNeedsTheEnumContextTheRendererSetsItself() {
    DaggerType.setKnownEnums(Set.of());

    String dang = DangEntrypointRenderer.render(module());

    assertThat(dang).contains(".withArg(\"level\", typeDef.withEnum(\"Severity\"))");
  }

  @Test
  void callRunsTheBuiltJarOverStandardInputWithNestingEnabled() {
    String dang = DangEntrypointRenderer.render(module());

    assertThat(dang)
        .contains(
            String.join(
                "\n",
                "  pub call(",
                "    workspace: Workspace!,",
                "    receiverType: String!,",
                "    receiverValue: JSON,",
                "    fnName: String!,",
                "    fnArgs: JSON!,",
                "  ): JSON! {"))
        .contains("let request = JSON.encode({{")
        .contains("receiverType: receiverType,")
        .contains("fnArgs: fnArgs,")
        .contains("[\"java\", \"-jar\", \"/opt/module/module.jar\", \"engine-call\"],")
        .contains("stdin: request,")
        .contains("experimentalPrivilegedNesting: true,")
        // stdout already holds JSON text, so the result is cast rather than decoded.
        .contains("(result :: JSON!)");
  }

  /** The shade plugin leaves an unshaded backup in target/; picking it would run the wrong jar. */
  @Test
  void theBuildPicksTheShadedJarAndNotTheBackup() {
    String dang = DangEntrypointRenderer.render(module());

    assertThat(dang)
        .contains("\"mvn\", \"package\", \"-DskipTests\"")
        .contains("grep -v '/original-'")
        .contains(".file(\"/tmp/module.jar\")");
  }

  @Test
  void descriptionsAreEscapedSoTheOutputStaysParseable() {
    ObjectInfo object =
        new ObjectInfo(
            "Demo",
            "io.dagger.modules.demo.Demo",
            "a \"quoted\" \\ description\nover two lines",
            new FieldInfo[0],
            new FunctionInfo[0],
            Optional.empty());

    String dang = DangEntrypointRenderer.render(new ModuleInfo(null, new ObjectInfo[] {object}, Map.of()));

    assertThat(dang)
        .contains(
            "typeDef.withObject(\"Demo\", description:"
                + " \"a \\\"quoted\\\" \\\\ description\\nover two lines\")");
  }

  /**
   * The engine finds a module's entry object by looking for the type that declares a constructor,
   * and accepts one across the whole module. Java satisfies that without trying: its analyzer only
   * records a constructor on the object whose name matches the module's.
   */
  @Test
  void exactlyOneTypeDeclaresAConstructor() {
    String dang = DangEntrypointRenderer.render(module());

    assertThat(dang.split("\\.withConstructor\\(", -1)).hasSize(2);
  }

  private static ModuleInfo module() {
    FunctionInfo greet =
        new FunctionInfo(
            "greet",
            "greet",
            "Say hello",
            new TypeInfo("java.lang.String", TypeKind.DECLARED.name()),
            new ParameterInfo[] {
              new ParameterInfo(
                  "name",
                  "who to greet",
                  new TypeInfo("java.lang.String", TypeKind.DECLARED.name()),
                  false,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty()),
              new ParameterInfo(
                  "loud",
                  "",
                  new TypeInfo("boolean", TypeKind.BOOLEAN.name()),
                  true,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty()),
              new ParameterInfo(
                  "level",
                  "",
                  new TypeInfo(ENUM_QNAME, TypeKind.DECLARED.name()),
                  false,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty()),
            },
            false,
            false,
            false);

    FunctionInfo constructor =
        new FunctionInfo(
            "<init>",
            "<init>",
            "",
            new TypeInfo("io.dagger.modules.demo.Demo", TypeKind.DECLARED.name()),
            new ParameterInfo[] {
              new ParameterInfo(
                  "prefix",
                  "",
                  new TypeInfo("java.lang.String", TypeKind.DECLARED.name()),
                  false,
                  Optional.of("\"Hi\""),
                  Optional.empty(),
                  Optional.empty()),
            },
            false,
            false,
            false);

    ObjectInfo demo =
        new ObjectInfo(
            "Demo",
            "io.dagger.modules.demo.Demo",
            "A demo module",
            new FieldInfo[] {
              new FieldInfo(
                  "source",
                  "the source tree",
                  new TypeInfo("io.dagger.client.Directory", TypeKind.DECLARED.name()))
            },
            new FunctionInfo[] {greet},
            Optional.of(constructor));

    EnumInfo severity =
        new EnumInfo(
            "Severity",
            "",
            new EnumValueInfo[] {
              new EnumValueInfo("HIGH", "very bad"), new EnumValueInfo("LOW", "")
            });

    return new ModuleInfo(
        "A demo module", new ObjectInfo[] {demo}, Map.of(ENUM_QNAME, severity));
  }
}
