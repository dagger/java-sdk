package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dagger.codegen.GenerationPlan;
import io.dagger.codegen.Generator;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Java identifiers follow the names the engine formatted, while everything sent to the API keeps
 * the schema's names.
 */
class FormattedNamesCodegenTest {
  @TempDir Path output;
  @TempDir Path plans;

  /** What the fake engine was asked: one version per request. */
  private final List<String> versions = new ArrayList<>();

  @Test
  void namesJavaIdentifiersFromFormattedNames() throws Exception {
    generate(schema(), engine());

    // The engine was asked once, with the schema's own version.
    assertThat(versions).containsExactly("v1.0.0-beta.21");

    String container = read("Container");
    // Methods are camelCase with acronyms written like words, and select the schema's fields.
    assertThat(container)
        .contains("public String htmlUrl()")
        .contains("this.queryBuilder.chain(\"htmlURL\")")
        .contains("@JsonbProperty(\"htmlURL\")")
        .contains("private String htmlUrl;")
        .contains("public String asJson()")
        .contains("this.queryBuilder.chain(\"asJSON\")")
        .contains("public List<String> prerequisiteShas()")
        .contains("public Container withGpu(List<String> devices)")
        .contains("builder.add(\"devices\", devices)")
        .contains("this.queryBuilder.chain(\"withGPU\", fieldArgs)")
        .contains("public Container withMcpServer(String name)")
        .contains("public Container withMcpServer(String name, WithMCPServerArguments optArgs)")
        .contains("this.queryBuilder.chain(\"withMCPServer\", fieldArgs)")
        .contains("public Container importTarball(String source)")
        .contains("this.queryBuilder.chain(\"import\", fieldArgs)");
    // Optional arguments keep their class, and send the schema's argument names.
    assertThat(container)
        .contains("public static class WithMCPServerArguments")
        .contains("private Boolean insecureSkipTlsVerify;")
        .contains(
            "public WithMCPServerArguments withInsecureSkipTlsVerify(Boolean insecureSkipTlsVerify)")
        .contains("builder.add(\"insecureSkipTLSVerify\", this.insecureSkipTlsVerify)");
    // The old names stay as deprecated aliases.
    assertThat(container)
        .contains("public String htmlURL()")
        .contains("return htmlUrl();")
        .contains("public String asJSON()")
        .contains("return asJson();")
        .contains("public List<String> prerequisiteSHAs()")
        .contains("public Container withGPU(List<String> devices)")
        .contains("return withGpu(devices);")
        .contains("public Container withMCPServer(String name)")
        .contains("public Container withMCPServer(String name, WithMCPServerArguments optArgs)")
        .contains("return withMcpServer(name, optArgs);")
        .contains(
            "public WithMCPServerArguments withInsecureSkipTLSVerify(Boolean insecureSkipTlsVerify)")
        .contains("return withInsecureSkipTlsVerify(insecureSkipTlsVerify);")
        .contains("@deprecated Use {@link #withMcpServer} instead.")
        .doesNotContain("importTarball_");

    // Interfaces declare the new name, and keep the old one as a default method.
    assertThat(read("Node"))
        .contains("String htmlUrl() throws")
        .contains("default String htmlURL() throws")
        .contains("return htmlUrl();");
    assertThat(read("NodeClient"))
        .contains("public String htmlUrl()")
        .contains("this.queryBuilder.chain(\"htmlURL\")")
        .doesNotContain("String htmlURL()");

    // Input objects send and serialize the schema's field names.
    assertThat(read("BuildArg"))
        .contains("@JsonbProperty(\"insecureSkipTLSVerify\")")
        .contains("private Boolean insecureSkipTlsVerify;")
        .contains("public Boolean isInsecureSkipTlsVerify()")
        .contains("public void setInsecureSkipTlsVerify(Boolean insecureSkipTlsVerify)")
        .contains("public BuildArg withInsecureSkipTlsVerify(Boolean insecureSkipTlsVerify)")
        .contains("map.put(\"insecureSkipTLSVerify\", this.insecureSkipTlsVerify)")
        .contains("public Boolean isInsecureSkipTLSVerify()")
        .contains("public void setInsecureSkipTLSVerify(Boolean insecureSkipTlsVerify)")
        .contains("public BuildArg withInsecureSkipTLSVerify(Boolean insecureSkipTlsVerify)")
        .contains("private String name;")
        .doesNotContain("@JsonbProperty(\"name\")")
        .doesNotContain("@GraphQLType");

    // Enum constants are SCREAMING_SNAKE_CASE, carrying the schema values they go over the wire
    // as. The old constants stay as deprecated aliases.
    assertThat(read("FunctionCachePolicy"))
        .contains("public enum FunctionCachePolicy implements GraphQLEnum")
        .contains("@JsonbTypeSerializer(GraphQLEnumSerializer.class)")
        .contains("@JsonbTypeDeserializer(GraphQLEnumDeserializer.class)")
        .contains("DEFAULT(\"Default\")")
        .contains("PER_SESSION(\"PerSession\")")
        .contains("public static final FunctionCachePolicy PerSession = PER_SESSION;")
        .contains("public static final FunctionCachePolicy Default = DEFAULT;")
        .contains("@deprecated Use {@link #PER_SESSION} instead.")
        .contains("public String graphQLValue()")
        .contains("public boolean hasGraphQLValue(String value)")
        .doesNotContain("@GraphQLType");
    // A value and its alias share a constant, sent as the first value and read back as either.
    assertThat(read("ImageLayerCompression"))
        .contains("GZIP(\"Gzip\", \"GZIP\")")
        .contains("TCP(\"TCP\")")
        .contains("public static final ImageLayerCompression Gzip = GZIP;")
        .doesNotContain("ImageLayerCompression GZIP =")
        .doesNotContain("ImageLayerCompression TCP =");
    assertThat(container)
        .contains("public ImageLayerCompression compression()")
        .contains("return nextQueryBuilder.executeQuery(ImageLayerCompression.class)");

    // Classes follow Java's convention, and name their GraphQL type when it differs.
    assertThat(read("JsonValue"))
        .contains("@GraphQLType(\"JSONValue\")")
        .contains("public class JsonValue implements IDAble<Id>")
        .contains("public Json contents()")
        .contains("chainNode(\"JSONValue\", new Id(id))");
    assertThat(read("Json"))
        .contains("@GraphQLType(\"JSON\")")
        .contains("public class Json extends Scalar<String>");
    assertThat(read("Id")).contains("@GraphQLType(\"ID\")").contains("public class Id extends");
    assertThat(container)
        .contains("public class Container implements Node, IDAble<Id>")
        .doesNotContain("@GraphQLType")
        // Lists of objects instantiate the classes, but select the GraphQL type.
        .contains("public List<JsonValue> values()")
        .contains("nextQueryBuilder.executeObjectListQuery(\"JSONValue\")")
        .contains("new JsonValue(qb)");
    // Loading by ID reads the class's GraphQL type.
    assertThat(read("Core"))
        .contains("public <T> T loadObjectFromID(Class<T> clazz, Id id)")
        .contains("GraphQLType graphQLType = clazz.getAnnotation(GraphQLType.class);")
        .contains("this.queryBuilder.chainNode(typeName, id)")
        .doesNotContain("@GraphQLType");
    // JSON conversions go through the enums' values.
    assertThat(read("JsonConverter"))
        .contains("public static Json toJSON(Object object)")
        .contains("GraphQLEnums.value((Enum<?>) object)")
        .contains("GraphQLEnums.valueOf(clazz, jsonb.fromJson(json, String.class))");
    assertThat(output.resolve(corePath("JSON"))).doesNotExist();
  }

  /** A names file beside the schema stands in for the engine. */
  @Test
  void readsANamesFileInsteadOfAskingTheEngine() throws Exception {
    Path plan = plan(schema());
    Files.copy(resource("formatted-names.names.json"), plan.resolve("core/names.json"));
    new Generator(
            output,
            StandardCharsets.UTF_8,
            "v1.0.0-beta.28",
            (query, variables) -> {
              throw new AssertionError("asked the engine despite a names file");
            })
        .generate(GenerationPlan.read(plan));

    assertThat(read("Container")).contains("public String htmlUrl()");
    assertThat(read("JsonValue")).contains("@GraphQLType(\"JSONValue\")");
  }

  /**
   * An engine that can't format the names fails codegen, rather than generating the schema's names:
   * that would be a different API from the one the schema names.
   */
  @Test
  void failsWhenTheEngineCannotFormatTheNames() throws Exception {
    assertThatThrownBy(
            () ->
                generate(
                    schema(),
                    (query, variables) -> {
                      throw new IOException("Cannot query field \"naming\"");
                    }))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Query.naming")
        .hasMessageContaining("v1.0.0-beta.21")
        .hasMessageContaining("Cannot query field \"naming\"");

    assertThatThrownBy(
            () ->
                generate(
                    schema(),
                    (query, variables) ->
                        new ByteArrayInputStream("{\"f0\":[]}".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("could not format");
    assertThat(output.resolve(corePath("Container"))).doesNotExist();
  }

  @Test
  void keepsSchemaNamesMissingFromTheNames() throws Exception {
    Map<String, Map<String, String>> names = names();
    names.get(FormattedNames.MEMBERS).remove("withMCPServer");
    names.get(FormattedNames.TYPES).remove("JSONValue");
    names.get(FormattedNames.ENUM_VALUES).remove("PerSession");
    generate(schema(), engine(names));

    // Its argument is still renamed.
    assertThat(read("Container"))
        .contains("public Container withMCPServer(String name)")
        .contains("public static class WithMCPServerArguments")
        .contains("private Boolean insecureSkipTlsVerify;")
        .contains("public Container withGpu(List<String> devices)")
        .contains("public List<JSONValue> values()")
        .doesNotContain("withMcpServer");
    assertThat(read("JSONValue")).doesNotContain("GraphQLType").contains("IDAble<Id>");
    assertThat(read("FunctionCachePolicy"))
        .contains("DEFAULT(\"Default\")")
        .contains("PerSession(\"PerSession\")")
        .doesNotContain("PER_SESSION");
  }

  private void generate(byte[] schema, FormattedNames.QueryRunner engine) throws Exception {
    new Generator(output, StandardCharsets.UTF_8, "v1.0.0-beta.28", engine)
        .generate(GenerationPlan.read(plan(schema)));
  }

  /** A plan generating core alone from the schema. */
  private Path plan(byte[] schema) throws IOException {
    Path plan = Files.createTempDirectory(plans, "plan");
    Files.createDirectories(plan.resolve("core"));
    Files.write(plan.resolve("core/schema.json"), schema);
    return plan;
  }

  /** The fixture schema. */
  private static byte[] schema() throws IOException {
    try (InputStream in = resource("formatted-names.json")) {
      return in.readAllBytes();
    }
  }

  private FormattedNames.QueryRunner engine() throws Exception {
    return engine(names());
  }

  /**
   * A fake engine answering naming's formatIdentifiers from a names file, and the name itself for a
   * name the file doesn't have.
   */
  private FormattedNames.QueryRunner engine(Map<String, Map<String, String>> names) {
    return (query, variables) -> {
      assertThat(query).contains("naming(version: $version)").contains("formatIdentifiers(");
      JsonObject vars = Json.createReader(new StringReader(variables)).readObject();
      versions.add(vars.isNull("version") ? null : vars.getString("version"));
      List<String> requested =
          vars.getJsonArray("names").stream().map(v -> ((JsonString) v).getString()).toList();
      JsonObjectBuilder data = Json.createObjectBuilder();
      for (int i = 0; i < FormattedNames.FORMATS.size(); i++) {
        Map<String, String> formatted = names.getOrDefault(FormattedNames.FORMATS.get(i), Map.of());
        JsonArrayBuilder out = Json.createArrayBuilder();
        requested.forEach(name -> out.add(formatted.getOrDefault(name, name)));
        data.add("f" + i, out);
      }
      String response = Json.createObjectBuilder().add("naming", data).build().toString();
      return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
    };
  }

  private static Map<String, Map<String, String>> names() throws Exception {
    try (InputStream in = resource("formatted-names.names.json")) {
      return FormattedNames.read(in);
    }
  }

  private String read(String className) throws Exception {
    return Files.readString(output.resolve(corePath(className)));
  }

  private static String corePath(String className) {
    return Generator.CORE_PACKAGE.replace('.', '/') + "/" + className + ".java";
  }

  private static InputStream resource(String name) {
    return FormattedNamesCodegenTest.class.getResourceAsStream("/schemas/" + name);
  }
}
