package io.dagger.annotation.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.dagger.module.info.FieldInfo;
import io.dagger.module.info.FunctionInfo;
import io.dagger.module.info.ModuleInfo;
import io.dagger.module.info.ObjectInfo;
import io.dagger.module.info.ParameterInfo;
import io.dagger.module.info.TypeInfo;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.type.TypeKind;
import org.junit.jupiter.api.Test;

class EntrypointGenerationTest {

  /** The JSON scalar's class, named by the schema the SDK under test was generated from. */
  private static final String JSON = DaggerType.JSON_CLASS.simpleName();

  /** The Dang entrypoint runs the jar with engine-call, which reaches this switch directly. */
  @Test
  void engineCallModeRunsTheStaticDispatchSwitch() {
    String java = generated();

    assertThat(java)
        .contains(
            "private static "
                + JSON
                + " invoke("
                + JSON
                + " parentJson, String parentName, String fnName,")
        .contains("if (args.length > 0 && args[0].equals(\"engine-call\")) {")
        .contains(
            "System.exit(ModuleDispatcher.engineCall(args, "
                + JSON
                + "::from, Entrypoint::invoke));");
  }

  @Test
  void anObjectWithoutConstructorAnswersItsConstructorWithItsEmptyState() {
    assertThat(generated())
        .contains(
            String.join(
                "\n",
                "    if (parentName.equals(\"Hello\")) {",
                "      if (fnName.equals(\"\")) {",
                "        return " + JSON + ".from(\"{}\");",
                "      }"));
  }

  @Test
  void registerDeclaresTheCachePolicy() {
    String java = generated();

    assertThat(java)
        .contains(
            ".withCachePolicy(FunctionCachePolicy."
                + DaggerModuleAnnotationProcessor.cachePolicy("NEVER", "Never")
                + ")")
        .contains(
            ".withCachePolicy(FunctionCachePolicy."
                + DaggerModuleAnnotationProcessor.cachePolicy("PER_SESSION", "PerSession")
                + ")")
        .contains(
            ".withCachePolicy(FunctionCachePolicy."
                + DaggerModuleAnnotationProcessor.cachePolicy("DEFAULT", "Default")
                + ", new Function.WithCachePolicyArguments().withTimeToLive(\"1h\"))");
  }

  /** The cache policy constants are the SDK's own: Java's spelling, or the schema's. */
  @Test
  void theCachePolicyConstantsExist() throws Exception {
    for (String[] names :
        new String[][] {
          {"NEVER", "Never"}, {"PER_SESSION", "PerSession"}, {"DEFAULT", "Default"}
        }) {
      String constant = DaggerModuleAnnotationProcessor.cachePolicy(names[0], names[1]);
      assertThat(io.dagger.client.modules.core.FunctionCachePolicy.class.getField(constant))
          .isNotNull();
    }
  }

  private static String generated() {
    ObjectInfo hello =
        new ObjectInfo(
            "Hello",
            "io.dagger.modules.hello.Hello",
            "",
            new FieldInfo[0],
            new FunctionInfo[] {
              function("fresh", "never"), function("session", "session"), function("hourly", "1h")
            },
            Optional.empty(),
            false);
    return DaggerModuleAnnotationProcessor.generate(
            new ModuleInfo(null, new ObjectInfo[] {hello}, Map.of()))
        .toString();
  }

  private static FunctionInfo function(String name, String cache) {
    return new FunctionInfo(
        name,
        name,
        "",
        cache,
        new TypeInfo("java.lang.String", TypeKind.DECLARED.name()),
        new ParameterInfo[0],
        false,
        false,
        false,
        false);
  }
}
