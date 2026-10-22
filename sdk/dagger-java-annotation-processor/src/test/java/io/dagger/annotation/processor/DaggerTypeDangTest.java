package io.dagger.annotation.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.dagger.module.info.TypeInfo;
import java.util.Set;
import javax.lang.model.type.TypeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The Dang backend must describe the same schema as the JavaPoet backend. */
class DaggerTypeDangTest {

  @AfterEach
  void resetKnownEnums() {
    DaggerType.setKnownEnums(Set.of());
  }

  @Test
  void primitiveKindsCollapseTheWayTheJavaBackendCollapsesThem() {
    assertThat(DaggerType.of("int").toDangTypeDef())
        .isEqualTo("typeDef.withKind(TypeDefKind.INTEGER_KIND)");
    assertThat(DaggerType.of("long").toDangTypeDef())
        .isEqualTo("typeDef.withKind(TypeDefKind.INTEGER_KIND)");
    assertThat(DaggerType.of("double").toDangTypeDef())
        .isEqualTo("typeDef.withKind(TypeDefKind.FLOAT_KIND)");
    assertThat(DaggerType.of("boolean").toDangTypeDef())
        .isEqualTo("typeDef.withKind(TypeDefKind.BOOLEAN_KIND)");
    assertThat(declared("java.lang.String").toDangTypeDef())
        .isEqualTo("typeDef.withKind(TypeDefKind.STRING_KIND)");
  }

  @Test
  void voidIsAnOptionalVoidKind() {
    assertThat(DaggerType.of("void").toDangTypeDef())
        .isEqualTo("typeDef.withKind(TypeDefKind.VOID_KIND).withOptional(true)");
  }

  @Test
  void objectsScalarsAndEnumsRenderByName() {
    assertThat(declared("io.dagger.client.modules.core.Container").toDangTypeDef())
        .isEqualTo("typeDef.withObject(\"Container\")");
    assertThat(declared("io.dagger.client.modules.core.JSON").toDangTypeDef())
        .isEqualTo("typeDef.withScalar(\"JSON\")");

    DaggerType.setKnownEnums(Set.of("io.dagger.modules.demo.Severity"));
    assertThat(declared("io.dagger.modules.demo.Severity").toDangTypeDef())
        .isEqualTo("typeDef.withEnum(\"Severity\")");
  }

  @Test
  void listsAndArraysBothRenderAsListOf() {
    assertThat(declared("java.util.List<io.dagger.client.modules.core.File>").toDangTypeDef())
        .isEqualTo("typeDef.withListOf(typeDef.withObject(\"File\"))");
    assertThat(DaggerType.of("io.dagger.client.modules.core.File[]").toDangTypeDef())
        .isEqualTo("typeDef.withListOf(typeDef.withObject(\"File\"))");
  }

  @Test
  void optionalDecoratesItsInnerTypeInTheSameOrderTheJavaBackendUses() {
    DaggerType type = declared("java.util.Optional<java.util.List<java.lang.String>>");

    assertThat(type.toDangTypeDef())
        .isEqualTo(
            "typeDef.withListOf(typeDef.withKind(TypeDefKind.STRING_KIND)).withOptional(true)");
    assertThat(type.toDaggerTypeDef().toString()).endsWith(".withOptional(true)");
  }

  @Test
  void stringLiteralsAreEscaped() {
    assertThat(Dang.quote("a \"b\" \\ c\nd\te")).isEqualTo("\"a \\\"b\\\" \\\\ c\\nd\\te\"");
    assertThat(Dang.quote("x\fy")).isEqualTo("\"x\\u000cy\"");
  }

  private static DaggerType declared(String typeName) {
    return DaggerType.of(new TypeInfo(typeName, TypeKind.DECLARED.name()));
  }
}
