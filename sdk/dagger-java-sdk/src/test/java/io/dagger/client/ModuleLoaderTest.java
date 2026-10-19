package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dagger.client.exception.DaggerQueryException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModuleLoaderTest {

  private static final String SERVED = "{\"data\":{\"serveModule\":null}}";
  private static final String GREETED = "{\"data\":{\"lib\":{\"greet\":\"hello\"}}}";

  private static final ModuleTarget LIB = ModuleTarget.local("/.dagger/modules/lib");
  private static final ModuleTarget HELLO =
      ModuleTarget.git("github.com/dagger/hello@v1", "0123abc");

  @Test
  void aLocalTargetIsServedByItsAddressBeforeTheSelection() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(ModuleLoaderTest::answer)) {
      String greeting = greet(engine.session(), LIB);

      assertThat(greeting).isEqualTo("hello");
      assertThat(engine.queries())
          .containsExactly(
              "query {serveModule(address:\"/.dagger/modules/lib\")}", "query {lib {greet}}");
    }
  }

  @Test
  void aGitTargetIsServedAtItsPin() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(ModuleLoaderTest::answer)) {
      greet(engine.session(), HELLO);

      assertThat(engine.queries().get(0))
          .startsWith("query {serveModule(")
          .contains("address:\"github.com/dagger/hello@v1\"")
          .contains("refPin:\"0123abc\"");
    }
  }

  @Test
  void aSessionServesATargetOnce() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(ModuleLoaderTest::answer)) {
      greet(engine.session(), LIB);
      greet(engine.session(), LIB);
      engine.session().queryBuilder().withTarget(LIB).chainNode("Lib", "Lib@1").executeQuery();

      assertThat(serves(engine)).isEqualTo(1);
      assertThat(engine.queries()).hasSize(4);
    }
  }

  @Test
  void eachTargetIsServed() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(ModuleLoaderTest::answer)) {
      engine.session().queryBuilder().withTarget(LIB).withTarget(HELLO).chain("lib").executeQuery();

      assertThat(serves(engine)).isEqualTo(2);
    }
  }

  @Test
  void anotherSessionServesAgain() throws Exception {
    try (FakeEngine first = FakeEngine.replying(ModuleLoaderTest::answer);
        FakeEngine second = FakeEngine.replying(ModuleLoaderTest::answer)) {
      greet(first.session(), LIB);
      greet(second.session(), LIB);

      assertThat(serves(first)).isEqualTo(1);
      assertThat(serves(second)).isEqualTo(1);
    }
  }

  @Test
  void aSelectionWithoutATargetServesNothing() throws Exception {
    try (FakeEngine engine = FakeEngine.replying("{\"data\":{\"container\":{\"id\":\"c\"}}}")) {
      engine.session().queryBuilder().chain("container").chain("id").executeQuery(String.class);

      assertThat(engine.queries()).containsExactly("query {container {id}}");
    }
  }

  @Test
  void aTargetSurvivesANodeLookup() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(ModuleLoaderTest::answer)) {
      engine
          .session()
          .queryBuilder()
          .withTarget(LIB)
          .chainNode("Lib", "Lib@1")
          .chain(List.of("greet"))
          .executeQuery();

      assertThat(engine.queries())
          .containsExactly(
              "query {serveModule(address:\"/.dagger/modules/lib\")}",
              "query {node(id:\"Lib@1\") {... on Lib {greet}}}");
    }
  }

  @Test
  void aRefusedServeFailsTheSelectionAndIsTriedAgain() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    try (FakeEngine engine =
        FakeEngine.replying(
            query -> {
              if (query.contains("serveModule") && attempts.getAndIncrement() == 0) {
                return "{\"errors\":[{\"message\":\"serve module: no such module\","
                    + "\"path\":[\"serveModule\"]}]}";
              }
              return answer(query);
            })) {
      assertThatThrownBy(() -> greet(engine.session(), LIB))
          .isInstanceOf(DaggerQueryException.class)
          .hasMessage("serve module: no such module");
      assertThat(engine.queries()).noneMatch(query -> query.contains("greet"));

      assertThat(greet(engine.session(), LIB)).isEqualTo("hello");
      assertThat(serves(engine)).isEqualTo(2);
    }
  }

  @Test
  void concurrentSelectionsServeOnce() throws Exception {
    try (FakeEngine engine =
        FakeEngine.replying(
            query -> {
              sleep();
              return answer(query);
            })) {
      CountDownLatch start = new CountDownLatch(1);
      List<Thread> callers = new ArrayList<>();
      List<Throwable> failures = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        Thread caller =
            new Thread(
                () -> {
                  try {
                    start.await();
                    greet(engine.session(), LIB);
                  } catch (Exception e) {
                    synchronized (failures) {
                      failures.add(e);
                    }
                  }
                });
        caller.start();
        callers.add(caller);
      }
      start.countDown();
      for (Thread caller : callers) {
        caller.join();
      }

      assertThat(failures).isEmpty();
      assertThat(serves(engine)).isEqualTo(1);
    }
  }

  private static String greet(Session session, ModuleTarget target) throws Exception {
    return session
        .queryBuilder()
        .withTarget(target)
        .chain("lib")
        .chain("greet")
        .executeQuery(String.class);
  }

  private static String answer(String query) {
    return query.contains("serveModule") ? SERVED : GREETED;
  }

  private static long serves(FakeEngine engine) {
    return engine.queries().stream().filter(query -> query.contains("serveModule")).count();
  }

  private static void sleep() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
