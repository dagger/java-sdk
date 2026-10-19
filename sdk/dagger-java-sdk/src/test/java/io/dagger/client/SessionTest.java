package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SessionTest {

  @Test
  void aSelectionFromTheSessionRootReachesItsEngine() throws Exception {
    try (FakeEngine engine = FakeEngine.replying("{\"data\":{\"version\":\"v1.0.0-beta.18\"}}")) {
      String version = engine.session().queryBuilder().chain("version").executeQuery(String.class);

      assertThat(version).isEqualTo("v1.0.0-beta.18");
      assertThat(engine.queries()).containsExactly("query {version}");
    }
  }

  @Test
  void everySelectionChainsFromTheSameRoot() throws Exception {
    try (FakeEngine engine = FakeEngine.replying("{\"data\":{}}")) {
      Session session = engine.session();

      assertThat(session.queryBuilder()).isSameAs(session.queryBuilder());
      assertThat(session.queryBuilder().chain("container").chain(List.of("id")).buildQuery())
          .isEqualTo("query {container {id}}");
    }
  }
}
