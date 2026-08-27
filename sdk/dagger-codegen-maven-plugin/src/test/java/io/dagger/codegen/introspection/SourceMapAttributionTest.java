package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceMapAttributionTest {

  /**
   * Shaped after a real clientSchemaIntrospectionJSON dump: the module contributes its own type,
   * the fields on it, and a field on the core Query type. Core types and fields carry no directive.
   */
  private static final String SCHEMA =
      """
      {"__schema":{"types":[
        {"name":"Query","kind":"OBJECT","fields":[
          {"name":"container","type":{"kind":"OBJECT","name":"Container"}},
          {"name":"e2E","type":{"kind":"NON_NULL","ofType":{"kind":"OBJECT","name":"E2E"}},
           "directives":[{"name":"sourceMap","args":[{"name":"module","value":"\\"e2e\\""}]}]}
        ]},
        {"name":"Container","kind":"OBJECT","fields":[
          {"name":"withExec","type":{"kind":"OBJECT","name":"Container"}}
        ]},
        {"name":"Binding","kind":"OBJECT","fields":[
          {"name":"asString","type":{"kind":"SCALAR","name":"String"}},
          {"name":"asE2E","type":{"kind":"OBJECT","name":"E2E"},
           "directives":[{"name":"sourceMap","args":[{"name":"module","value":"\\"e2e\\""}]}]}
        ]},
        {"name":"E2E","kind":"OBJECT",
         "directives":[{"name":"sourceMap","args":[{"name":"module","value":"\\"e2e\\""}]}],
         "fields":[
          {"name":"initCheck","type":{"kind":"SCALAR","name":"String"},
           "directives":[{"name":"sourceMap","args":[{"name":"module","value":"\\"e2e\\""}]}]}
        ]}
      ]}}
      """;

  @Test
  void coreTypesAndFieldsHaveNoOwningModule() throws Exception {
    Schema schema = parse();
    assertThat(type(schema, "Container").getOwningModule()).isNull();
    assertThat(type(schema, "Query").getOwningModule()).isNull();
    assertThat(field(schema, "Container", "withExec").getOwningModule()).isNull();
    assertThat(field(schema, "Query", "container").getOwningModule()).isNull();
  }

  @Test
  void moduleTypesAreAttributedToTheirModule() throws Exception {
    assertThat(type(parse(), "E2E").getOwningModule()).isEqualTo("e2e");
  }

  @Test
  void moduleFieldsOnCoreTypesAreAttributedToTheModule() throws Exception {
    Schema schema = parse();
    // The extension points: a module reaches core through Query and Binding.
    assertThat(field(schema, "Query", "e2E").getOwningModule()).isEqualTo("e2e");
    assertThat(field(schema, "Binding", "asE2E").getOwningModule()).isEqualTo("e2e");
    // ...while the core type hosting them stays core.
    assertThat(type(schema, "Binding").getOwningModule()).isNull();
  }

  @Test
  void moduleFieldsOnModuleTypesAreAttributedToTheModule() throws Exception {
    assertThat(field(parse(), "E2E", "initCheck").getOwningModule()).isEqualTo("e2e");
  }

  @Test
  void theDirectiveValueIsUnquoted() {
    assertThat(
            Directive.getSourceMapModule(
                java.util.List.of(directive("sourceMap", "module", "\"hello\""))))
        .isEqualTo("hello");
  }

  @Test
  void anEmptyOrAbsentModuleReadsAsCore() {
    assertThat(
            Directive.getSourceMapModule(
                java.util.List.of(directive("sourceMap", "module", "\"\""))))
        .isNull();
    assertThat(
            Directive.getSourceMapModule(
                java.util.List.of(directive("expectedType", "name", "\"Container\""))))
        .isNull();
    assertThat(Directive.getSourceMapModule(null)).isNull();
  }

  private static Directive directive(String name, String argName, String value) {
    DirectiveArg arg = new DirectiveArg();
    arg.setName(argName);
    arg.setValue(value);
    Directive d = new Directive();
    d.setName(name);
    d.setArgs(java.util.List.of(arg));
    return d;
  }

  private static Schema parse() throws Exception {
    return Schema.initialize(
        new ByteArrayInputStream(SCHEMA.getBytes(StandardCharsets.UTF_8)), "v1.0.0-beta.10");
  }

  private static Type type(Schema schema, String name) {
    return schema.getTypes().stream()
        .filter(t -> name.equals(t.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no type " + name));
  }

  private static Field field(Schema schema, String typeName, String fieldName) {
    return type(schema, typeName).getFields().stream()
        .filter(f -> fieldName.equals(f.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no field " + typeName + "." + fieldName));
  }
}
