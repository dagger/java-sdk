package io.dagger.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModuleBindingTest {

  private static final String SERVED = "{\"data\":{\"moduleSource\":{}}}";

  @Test
  void aLocalModuleIsServedByWorkspacePathUnderItsFinalName() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(SERVED)) {
      ModuleBinding.ensureServed(
          new QueryBuilder(engine.client()), "hello", "LOCAL_SOURCE", "dagger/modules/hello", "");
      assertThat(engine.query())
          .contains("currentWorkspace {moduleSource(path:\"dagger/modules/hello\")")
          .contains("withName(name:\"hello\") {asModule {serve}}");
    }
  }

  @Test
  void aGitModuleIsServedByCanonicalRefAndPin() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(SERVED)) {
      ModuleBinding.ensureServed(
          new QueryBuilder(engine.client()),
          "alias",
          "GIT_SOURCE",
          "github.com/dagger/hello",
          "0123abc");
      assertThat(engine.query())
          .contains("moduleSource(refString:\"github.com/dagger/hello\"")
          .contains("refPin:\"0123abc\"")
          .contains("withName(name:\"alias\") {asModule {serve}}")
          .doesNotContain("currentWorkspace");
    }
  }

  @Test
  void anUnpinnedGitModuleSendsNoPin() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(SERVED)) {
      ModuleBinding.ensureServed(
          new QueryBuilder(engine.client()), "hello", "GIT", "github.com/dagger/hello", "");
      assertThat(engine.query()).doesNotContain("refPin");
    }
  }

  @Test
  void aBindingServesOncePerClient() throws Exception {
    // The first call is the one that can fail: the engine rejects a different source under the
    // same name. Once it has succeeded, a repeat of the exact tuple can only be deduplicated.
    try (FakeEngine engine = FakeEngine.replying(SERVED)) {
      QueryBuilder root = new QueryBuilder(engine.client());
      ModuleBinding.ensureServed(root, "hello", "LOCAL", "hello", "");
      ModuleBinding.ensureServed(root, "hello", "LOCAL", "hello", "");
      assertThat(engine.requests()).hasSize(1);

      ModuleBinding.ensureServed(root, "other", "LOCAL", "hello", "");
      assertThat(engine.requests()).hasSize(2);
    }
  }

  @Test
  void aSecondClientServesAgain() throws Exception {
    try (FakeEngine first = FakeEngine.replying(SERVED);
        FakeEngine second = FakeEngine.replying(SERVED)) {
      ModuleBinding.ensureServed(new QueryBuilder(first.client()), "hello", "LOCAL", "hello", "");
      ModuleBinding.ensureServed(new QueryBuilder(second.client()), "hello", "LOCAL", "hello", "");
      assertThat(first.requests()).hasSize(1);
      assertThat(second.requests()).hasSize(1);
    }
  }

  @Test
  void aSourceKindAClientCannotServeIsRejected() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(SERVED)) {
      assertThatThrownBy(
              () ->
                  ModuleBinding.ensureServed(
                      new QueryBuilder(engine.client()), "hello", "DIR_SOURCE", "/tmp/x", ""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("DIR_SOURCE");
      assertThat(engine.requests()).isEmpty();
    }
  }
}
