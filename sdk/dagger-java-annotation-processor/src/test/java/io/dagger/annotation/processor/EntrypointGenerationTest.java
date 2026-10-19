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

  @Test
  void registerDeclaresTheCachePolicy() {
    String java = generated();

    assertThat(java)
        .contains(".withCachePolicy(FunctionCachePolicy.Never)")
        .contains(".withCachePolicy(FunctionCachePolicy.PerSession)")
        .contains(
            ".withCachePolicy(FunctionCachePolicy.Default, new"
                + " Function.WithCachePolicyArguments().withTimeToLive(\"1h\"))");
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
            Optional.empty());
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
        false);
  }
}
