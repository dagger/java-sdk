package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.annotation.JsonbTypeSerializer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Enums whose constants are named apart from their schema values go by those values. */
class GraphQLEnumsTest {

  /** Shaped like a generated enum under engine-formatted names. */
  @JsonbTypeSerializer(GraphQLEnumSerializer.class)
  @JsonbTypeDeserializer(GraphQLEnumDeserializer.class)
  public enum Policy implements GraphQLEnum {
    DEFAULT("Default"),
    PER_SESSION("PerSession", "PER_SESSION");

    @Deprecated public static final Policy PerSession = PER_SESSION;

    private final String[] values;

    Policy(String... values) {
      this.values = values;
    }

    @Override
    public String graphQLValue() {
      return values[0];
    }

    @Override
    public boolean hasGraphQLValue(String value) {
      return List.of(values).contains(value);
    }
  }

  /** A module's own enum, named like its values. */
  public enum Level {
    LOW
  }

  public static class Holder {
    public Policy policy = Policy.PER_SESSION;
  }

  @Test
  void anArgumentGoesAsItsSchemaValue() throws Exception {
    assertThat(Arguments.newBuilder().add("policy", Policy.PER_SESSION).build().toGraphQL())
        .isEqualTo("policy:\"PerSession\"");
    assertThat(Arguments.newBuilder().add("policy", Policy.PerSession).build().toGraphQL())
        .isEqualTo("policy:\"PerSession\"");
    assertThat(
            Arguments.newBuilder()
                .add("policies", List.of(Policy.DEFAULT, Policy.PER_SESSION))
                .build()
                .toGraphQL())
        .isEqualTo("policies:[\"Default\",\"PerSession\"]");
    assertThat(Arguments.newBuilder().add("level", Level.LOW).build().toGraphQL())
        .isEqualTo("level:\"LOW\"");
  }

  @Test
  void aValueReadsBackAsItsConstant() {
    assertThat(GraphQLEnums.valueOf(Policy.class, "PerSession")).isEqualTo(Policy.PER_SESSION);
    assertThat(GraphQLEnums.valueOf(Policy.class, "PER_SESSION")).isEqualTo(Policy.PER_SESSION);
    assertThat(GraphQLEnums.valueOf(Level.class, "LOW")).isEqualTo(Level.LOW);
    assertThatThrownBy(() -> GraphQLEnums.valueOf(Policy.class, "Never"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jsonCarriesTheSchemaValuesInAListToo() throws Exception {
    try (Jsonb jsonb = JsonbBuilder.create()) {
      assertThat(jsonb.toJson(new Holder())).isEqualTo("{\"policy\":\"PerSession\"}");
      assertThat(jsonb.toJson(GraphQLEnums.values(Policy.PER_SESSION))).isEqualTo("\"PerSession\"");
      assertThat(jsonb.toJson(GraphQLEnums.values(List.of(Policy.PER_SESSION, Policy.DEFAULT))))
          .isEqualTo("[\"PerSession\",\"Default\"]");
      assertThat(jsonb.toJson(GraphQLEnums.values(new Policy[] {Policy.DEFAULT})))
          .isEqualTo("[\"Default\"]");
      assertThat(jsonb.toJson(GraphQLEnums.values(List.of(List.of(Level.LOW)))))
          .isEqualTo("[[\"LOW\"]]");
      assertThat(jsonb.fromJson("{\"policy\":\"PerSession\"}", Holder.class).policy)
          .isEqualTo(Policy.PER_SESSION);
    }
  }
}
