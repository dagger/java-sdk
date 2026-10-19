package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterfaceVisitorTest {
  @Test
  void idHandlesLoadTheirExpectedType() throws Exception {
    String generated = generateInterface(interfaceWithIdHandleFields(), "v1.0.0");

    assertThat(generated).contains("Parent sync()").contains("Agent spawn()");
  }

  private static String generateInterface(Type type, String version) throws Exception {
    byte[] introspection = "{\"__schema\":{\"types\":[]}}".getBytes(StandardCharsets.UTF_8);
    Schema schema = Schema.initialize(new ByteArrayInputStream(introspection), version);
    return new InterfaceVisitor(schema, Path.of("."), StandardCharsets.UTF_8)
        .generateType(type)
        .toString();
  }

  private static Type interfaceWithIdHandleFields() {
    Type parent = new Type();
    parent.setKind(TypeKind.INTERFACE);
    parent.setName("Parent");
    parent.setDescription("");
    parent.setFields(
        List.of(idHandleField(parent, "sync", "Parent"), idHandleField(parent, "spawn", "Agent")));
    return parent;
  }

  private static Field idHandleField(Type parent, String name, String expectedType) {
    TypeRef idType = new TypeRef();
    idType.setKind(TypeKind.SCALAR);
    idType.setName("ID");
    TypeRef returnType = new TypeRef();
    returnType.setKind(TypeKind.NON_NULL);
    returnType.setOfType(idType);

    DirectiveArg nameArg = new DirectiveArg();
    nameArg.setName("name");
    nameArg.setValue("\"" + expectedType + "\"");
    Directive expected = new Directive();
    expected.setName("expectedType");
    expected.setArgs(List.of(nameArg));

    Field field = new Field();
    field.setName(name);
    field.setDescription("");
    field.setTypeRef(returnType);
    field.setArgs(List.of());
    field.setDirectives(List.of(expected));
    field.setParentObject(parent);
    return field;
  }
}
