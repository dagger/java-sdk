package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dagger.client.exception.DaggerQueryException;
import io.dagger.client.exception.StaleClientException;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class StaleClientTest {

  private static final String SERVED = "{\"data\":{\"serveModule\":null}}";

  private static final String MISSING_GREET =
      "{\"errors\":[{\"message\":\"Cannot query field \\\"greet\\\" on type \\\"Lib\\\".\","
          + "\"locations\":[{\"line\":1,\"column\":14}],"
          + "\"extensions\":{\"code\":\"GRAPHQL_VALIDATION_FAILED\"}}],\"data\":null}";

  private static final ModuleTarget LIB = ModuleTarget.local("/.dagger/modules/lib");
  private static final ModuleTarget HELLO =
      ModuleTarget.git("github.com/dagger/hello@v1", "0123abc");

  @Test
  void aSelectionOnATargetThatFailsValidationIsAStaleClient() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(servedThen(query -> MISSING_GREET))) {
      assertThatThrownBy(() -> greet(engine.session().queryBuilder().withTarget(LIB)))
          .isInstanceOf(StaleClientException.class)
          .isInstanceOf(DaggerQueryException.class)
          .hasMessage(
              "Cannot query field \"greet\" on type \"Lib\". The client of /.dagger/modules/lib"
                  + " is out of date. Run `dagger generate`.")
          .extracting(e -> ((DaggerQueryException) e).getError().getMessage())
          .isEqualTo("Cannot query field \"greet\" on type \"Lib\".");
    }
  }

  @Test
  void aStaleGitClientIsNamedWithItsPin() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(servedThen(query -> MISSING_GREET))) {
      assertThatThrownBy(() -> greet(engine.session().queryBuilder().withTarget(HELLO)))
          .isInstanceOf(StaleClientException.class)
          .hasMessageContaining("The client of github.com/dagger/hello@v1 at 0123abc is");
    }
  }

  @Test
  void aSelectionOnTwoTargetsNamesBoth() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(servedThen(query -> MISSING_GREET))) {
      assertThatThrownBy(
              () -> greet(engine.session().queryBuilder().withTarget(LIB).withTarget(HELLO)))
          .isInstanceOf(StaleClientException.class)
          .hasMessageContaining(
              "The clients of /.dagger/modules/lib, github.com/dagger/hello@v1 at 0123abc are");
    }
  }

  @Test
  void aCoreSelectionThatFailsValidationIsAQueryError() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(MISSING_GREET)) {
      assertThatThrownBy(() -> greet(engine.session().queryBuilder()))
          .isInstanceOf(DaggerQueryException.class)
          .isNotInstanceOf(StaleClientException.class);
    }
  }

  @Test
  void aFailureFromSomethingThatRanIsAQueryError() throws Exception {
    String nested =
        "{\"errors\":[{\"message\":\"Cannot query field \\\"x\\\" on type \\\"Other\\\".\","
            + "\"path\":[\"lib\",\"greet\"],"
            + "\"extensions\":{\"code\":\"GRAPHQL_VALIDATION_FAILED\"}}],\"data\":null}";
    try (FakeEngine engine = FakeEngine.replying(servedThen(query -> nested))) {
      assertThatThrownBy(() -> greet(engine.session().queryBuilder().withTarget(LIB)))
          .isInstanceOf(DaggerQueryException.class)
          .isNotInstanceOf(StaleClientException.class);
    }
  }

  /** Regenerating a client cannot give the engine a field it lacks. */
  @Test
  void anEngineWithoutServeModuleIsAQueryError() throws Exception {
    String noServe =
        "{\"errors\":[{\"message\":\"Cannot query field \\\"serveModule\\\" on type"
            + " \\\"Query\\\".\",\"extensions\":{\"code\":\"GRAPHQL_VALIDATION_FAILED\"}}]}";
    try (FakeEngine engine = FakeEngine.replying(noServe)) {
      assertThatThrownBy(() -> greet(engine.session().queryBuilder().withTarget(LIB)))
          .isInstanceOf(DaggerQueryException.class)
          .isNotInstanceOf(StaleClientException.class)
          .hasMessageContaining("serveModule");
    }
  }

  @Test
  void anObjectLoadedFromAListKeepsItsTargets() throws Exception {
    try (FakeEngine engine =
        FakeEngine.replying(
            servedThen(
                query ->
                    query.startsWith("query {node(")
                        ? MISSING_GREET
                        : "{\"data\":{\"lib\":{\"items\":[{\"id\":\"Item@1\"}]}}}"))) {
      List<QueryBuilder> items =
          engine
              .session()
              .queryBuilder()
              .withTarget(LIB)
              .chain("lib")
              .chain("items", List.of("id"))
              .executeObjectListQuery("Item");

      assertThatThrownBy(() -> greet(items.get(0))).isInstanceOf(StaleClientException.class);
    }
  }

  @Test
  void aNullableObjectKeepsItsTargets() throws Exception {
    try (FakeEngine engine =
        FakeEngine.replying(
            servedThen(
                query ->
                    query.startsWith("query {node(")
                        ? MISSING_GREET
                        : "{\"data\":{\"lib\":{\"maybe\":{\"id\":\"Lib@1\"}}}}"))) {
      QueryBuilder maybe =
          engine
              .session()
              .queryBuilder()
              .withTarget(LIB)
              .chain("lib")
              .chain("maybe")
              .executeNullableObjectQuery("Lib");

      assertThatThrownBy(() -> greet(maybe)).isInstanceOf(StaleClientException.class);
    }
  }

  private static String greet(QueryBuilder from) throws Exception {
    return from.chain("greet").executeQuery(String.class);
  }

  private static Function<String, String> servedThen(Function<String, String> selection) {
    return query -> query.contains("serveModule") ? SERVED : selection.apply(query);
  }
}
