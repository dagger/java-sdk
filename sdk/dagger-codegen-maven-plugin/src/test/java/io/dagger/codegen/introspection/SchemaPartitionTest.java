package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaPartitionTest {

  /** A client schema for module {@code hello}: core plus one module, as the engine emits it. */
  private static final String HELLO = clientSchema("hello", "Hello", "hello", "asHello");

  /** The same core bound to a different module, to check core does not depend on the module. */
  private static final String BUILDER = clientSchema("builder", "Builder", "builder", "asBuilder");

  @Test
  void corePartitionKeepsOnlyUnownedTypes() throws Exception {
    SchemaPartition core = SchemaPartition.core(parse(HELLO));
    assertThat(names(core.types()))
        .containsExactly("Binding", "Container", "ID", "Query", "String");
    assertThat(core.module()).isNull();
    assertThat(core.extensions()).isEmpty();
  }

  @Test
  void corePartitionStripsOwnedFieldsFromCoreTypes() throws Exception {
    SchemaPartition core = SchemaPartition.core(parse(HELLO));
    assertThat(fieldNames(core, "Query")).containsExactly("container");
    assertThat(fieldNames(core, "Binding")).containsExactly("asString");
  }

  @Test
  void narrowingDoesNotTouchTheSchemaItCameFrom() throws Exception {
    Schema schema = parse(HELLO);
    SchemaPartition.core(schema);
    Type query =
        schema.getTypes().stream().filter(t -> "Query".equals(t.getName())).findFirst().get();
    assertThat(query.getFields()).extracting(Field::getName).containsExactly("container", "hello");
    // container is the field the core partition keeps, so it is the one narrowing could re-parent.
    assertThat(query.getFields().get(0).getParentObject()).isSameAs(query);
    assertThat(query.getFields().get(1).getParentObject()).isSameAs(query);
  }

  @Test
  void coreIsTheSameWhicheverModuleTheSchemaWasBoundTo() throws Exception {
    SchemaPartition fromHello = SchemaPartition.core(parse(HELLO));
    SchemaPartition fromBuilder = SchemaPartition.core(parse(BUILDER));
    assertThat(shape(fromHello)).isEqualTo(shape(fromBuilder));
  }

  @Test
  void clientPartitionKeepsOnlyTheModulesTypes() throws Exception {
    SchemaPartition client = SchemaPartition.client(parse(HELLO), "hello");
    assertThat(names(client.types())).containsExactly("Hello");
    assertThat(client.module()).isEqualTo("hello");
    assertThat(fieldNames(client, "Hello")).containsExactly("greet");
  }

  @Test
  void clientPartitionCollectsTheModulesFieldsOnCoreTypesAsExtensions() throws Exception {
    SchemaPartition client = SchemaPartition.client(parse(HELLO), "hello");
    assertThat(client.extensions().keySet()).containsExactlyInAnyOrder("Query", "Binding");
    assertThat(client.extensions().get("Query"))
        .extracting(Field::getName)
        .containsExactly("hello");
    assertThat(client.extensions().get("Binding"))
        .extracting(Field::getName)
        .containsExactly("asHello");
  }

  @Test
  void ownedTypeNamesCoverEveryModuleInTheSchema() throws Exception {
    assertThat(SchemaPartition.core(parse(HELLO)).ownedTypeNames()).containsExactly("Hello");
    assertThat(SchemaPartition.client(parse(HELLO), "hello").ownedTypeNames())
        .containsExactly("Hello");
  }

  @Test
  void aClientForAModuleTheSchemaDoesNotContainIsAnError() throws Exception {
    assertThatThrownBy(() -> SchemaPartition.client(parse(HELLO), "nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nothing owned by module nope")
        .hasMessageContaining("hello");
  }

  @Test
  void introspectionTypesAreNeverEmitted() throws Exception {
    assertThat(names(SchemaPartition.core(parse(HELLO)).types())).noneMatch(n -> n.startsWith("_"));
  }

  @Test
  void versionAndIdAbleHelpersAreEmittedByCoreOnly() throws Exception {
    RecordingVisitor core = new RecordingVisitor();
    SchemaPartition.core(parse(HELLO)).visit(core);
    assertThat(core.version).isEqualTo("v1.0.0-beta.10");
    assertThat(core.idAbles).containsExactly("Container");
    assertThat(core.objects).containsExactly("Binding", "Container", "Query");

    RecordingVisitor client = new RecordingVisitor();
    SchemaPartition.client(parse(HELLO), "hello").visit(client);
    assertThat(client.version).isNull();
    assertThat(client.idAbles).isNull();
    assertThat(client.objects).containsExactly("Hello");
  }

  private static final class RecordingVisitor implements SchemaVisitor {
    final List<String> objects = new ArrayList<>();
    String version;
    List<String> idAbles;

    @Override
    public void visitScalar(Type type) {}

    @Override
    public void visitObject(Type type) {
      objects.add(type.getName());
    }

    @Override
    public void visitInterface(Type type) {}

    @Override
    public void visitInput(Type type) {}

    @Override
    public void visitEnum(Type type) {}

    @Override
    public void visitVersion(String version) {
      this.version = version;
    }

    @Override
    public void visitIDAbles(List<Type> types) {
      this.idAbles = names(types);
    }
  }

  private static List<String> shape(SchemaPartition partition) {
    return partition.types().stream()
        .map(
            t ->
                t.getName()
                    + (t.getFields() == null
                        ? ""
                        : t.getFields().stream().map(Field::getName).toList().toString()))
        .toList();
  }

  private static List<String> names(List<Type> types) {
    return types.stream().map(Type::getName).toList();
  }

  private static List<String> fieldNames(SchemaPartition partition, String typeName) {
    return partition.types().stream()
        .filter(t -> typeName.equals(t.getName()))
        .findFirst()
        .orElseThrow()
        .getFields()
        .stream()
        .map(Field::getName)
        .toList();
  }

  private static Schema parse(String json) throws Exception {
    return Schema.initialize(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "v1.0.0-beta.10");
  }

  private static String owned(String module) {
    return "\"directives\":[{\"name\":\"sourceMap\",\"args\":[{\"name\":\"module\",\"value\":\"\\\""
        + module
        + "\\\"\"}]}]";
  }

  private static String clientSchema(String module, String root, String entry, String binding) {
    return "{\"__schema\":{\"queryType\":{\"name\":\"Query\"},\"types\":["
        + "{\"name\":\"__Schema\",\"kind\":\"OBJECT\",\"fields\":[]},"
        + "{\"name\":\"String\",\"kind\":\"SCALAR\"},"
        + "{\"name\":\"ID\",\"kind\":\"SCALAR\"},"
        + "{\"name\":\"Query\",\"kind\":\"OBJECT\",\"fields\":["
        + "  {\"name\":\"container\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\"Container\"}}},"
        + "  {\"name\":\""
        + entry
        + "\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\""
        + root
        + "\"}},"
        + owned(module)
        + "}]},"
        + "{\"name\":\"Container\",\"kind\":\"OBJECT\",\"fields\":["
        + "  {\"name\":\"id\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"SCALAR\",\"name\":\"ID\"}}},"
        + "  {\"name\":\"withExec\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\"Container\"}}}]},"
        + "{\"name\":\"Binding\",\"kind\":\"OBJECT\",\"fields\":["
        + "  {\"name\":\"asString\",\"args\":[],\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"}},"
        + "  {\"name\":\""
        + binding
        + "\",\"args\":[],\"type\":{\"kind\":\"OBJECT\",\"name\":\""
        + root
        + "\"},"
        + owned(module)
        + "}]},"
        + "{\"name\":\""
        + root
        + "\",\"kind\":\"OBJECT\","
        + owned(module)
        + ",\"fields\":["
        + "  {\"name\":\"greet\",\"args\":[],\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"},"
        + owned(module)
        + "}]}"
        + "]}}";
  }
}
