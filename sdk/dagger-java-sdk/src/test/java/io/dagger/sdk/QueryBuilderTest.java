package io.dagger.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryBuilderTest {

  @Test
  void rootDropsTheSelectionAndKeepsTheSession() throws Exception {
    try (FakeEngine server = FakeEngine.replying("{\"data\":{}}")) {
      QueryBuilder deep = new QueryBuilder(server.client()).chain("env").chain("bindings");

      assertThat(deep.root().chain("currentWorkspace").buildQuery())
          .isEqualTo("query {currentWorkspace}");
    }
  }

  @Test
  void nullableObjectQueryRebuildsTheObjectFromItsId() throws Exception {
    try (FakeEngine server =
        FakeEngine.replying(
            "{\"data\":{\"typeDef\":{\"asObject\":{\"id\":\"ObjectTypeDef@abc\"}}}}")) {
      QueryBuilder resolved =
          new QueryBuilder(server.client())
              .chain("typeDef")
              .chain("asObject")
              .executeNullableObjectQuery("ObjectTypeDef");

      assertThat(server.request()).contains("query {typeDef {asObject {id}}}");
      assertThat(resolved).isNotNull();
      assertThat(resolved.chain(List.of("id")).buildQuery())
          .isEqualTo("query {node(id:\"ObjectTypeDef@abc\") {... on ObjectTypeDef {id}}}");
    }
  }

  @Test
  void nullableObjectQueryReturnsNullWhenTheFieldIsNull() throws Exception {
    try (FakeEngine server = FakeEngine.replying("{\"data\":{\"typeDef\":{\"asObject\":null}}}")) {
      QueryBuilder resolved =
          new QueryBuilder(server.client())
              .chain("typeDef")
              .chain("asObject")
              .executeNullableObjectQuery("ObjectTypeDef");

      assertThat(server.request()).contains("query {typeDef {asObject {id}}}");
      assertThat(resolved).isNull();
    }
  }
}
