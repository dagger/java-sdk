package io.dagger.modules.entryapp;

import static io.dagger.client.Dagger.dag;

import io.dagger.client.Changeset;
import io.dagger.client.Directory;
import io.dagger.client.exception.DaggerQueryException;
import io.dagger.module.annotation.Check;
import io.dagger.module.annotation.Default;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Generate;
import io.dagger.module.annotation.Object;
import io.opentelemetry.api.trace.Span;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/** Calls every kind of function through the Dang entrypoint. */
@Object
public class EntryApp {
  private String greeting;

  public EntryApp() {}

  public EntryApp(@Default("Hello") String greeting) {
    this.greeting = greeting;
  }

  /** Greets with the greeting the constructor stored. */
  @Function
  public String greet(String name) {
    return greeting + ", " + name + "!";
  }

  @Function
  public List<String> shout(List<String> words) {
    return words.stream().map(String::toUpperCase).toList();
  }

  @Function
  public String maybe(Optional<String> value) {
    return value.map(v -> "got " + v).orElse("absent");
  }

  @Function
  public Level level(Level level) {
    return level;
  }

  @Function
  public void nothing() {}

  @Function
  public String fail() {
    throw new IllegalStateException("entry-app failed: boom");
  }

  @Function
  public String nested() throws ExecutionException, DaggerQueryException, InterruptedException {
    return dag()
        .container()
        .from("alpine:3.22")
        .withExec(List.of("echo", "nested " + greeting))
        .stdout()
        .trim();
  }

  @Function
  public Directory withGreeting(Directory dir) {
    return dir.withNewFile("greeting.txt", greeting);
  }

  /** The engine's trace, and the trace the function runs in. */
  @Function
  public String traces() {
    String traceparent = System.getenv("TRACEPARENT");
    String engine = traceparent == null ? "none" : traceparent.split("-")[1];
    return engine + " " + Span.current().getSpanContext().getTraceId();
  }

  @Function
  public String cached() {
    return UUID.randomUUID().toString();
  }

  @Function(cache = "never")
  public String fresh() {
    return UUID.randomUUID().toString();
  }

  @Function(cache = "session")
  public String perSession() {
    return UUID.randomUUID().toString();
  }

  @Function(cache = "10s")
  public String shortLived() {
    return UUID.randomUUID().toString();
  }

  @Function
  @Check
  public void passingCheck() {}

  @Function
  @Check
  public void failingCheck() {
    throw new IllegalStateException("failing-check failed: on purpose");
  }

  @Function
  @Generate
  public Changeset generateGreeting() {
    return dag()
        .directory()
        .withNewFile("generated/greeting.txt", greeting + "\n")
        .changes(dag().directory());
  }
}
