package io.dagger.client.engineconn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionTest {

  @TempDir Path dir;

  @Test
  void aSessionInTheEnvironmentIsAttachedToAndNothingIsStarted() throws Exception {
    Connection connection = Connection.get(dir.toString(), false, "54321", "tok", false);

    assertThat(connection.session()).isNull();
    assertThat(connection.getGraphQLClient()).isNotNull();
    connection.close();
  }

  @Test
  void aModuleWithoutASessionInTheEnvironmentIsRefusedOne() {
    assertThatThrownBy(() -> Connection.get(dir.toString(), false, null, null, true))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("DAGGER_SESSION_PORT")
        .hasMessageContaining("never starts one");
  }

  @Test
  void aModuleStillAttachesToTheSessionInItsEnvironment() throws Exception {
    Connection connection = Connection.get(dir.toString(), false, "54321", "tok", true);

    assertThat(connection.session()).isNull();
    connection.close();
  }

  @Test
  void aConnectionCarriesTheCurrentTrace() throws Exception {
    AtomicReference<String> traceparent = new AtomicReference<>();
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/query",
        exchange -> {
          try (exchange) {
            traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            byte[] payload = "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
          }
        });
    http.start();
    SpanContext parent =
        SpanContext.createFromRemoteParent(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    try {
      Connection connection;
      try (Scope scope = Context.current().with(Span.wrap(parent)).makeCurrent()) {
        connection = Connection.getConnection(http.getAddress().getPort(), "tok", null);
      }
      connection.getGraphQLClient().executeQuery("query {version}");
      connection.close();
    } finally {
      http.stop(0);
    }

    assertThat(traceparent.get())
        .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
  }

  @Test
  void aConnectionLeavesTheGlobalOpenTelemetryToItsOwner() throws Exception {
    boolean setBefore = GlobalOpenTelemetry.isSet();

    Connection.getConnection(54321, "tok", null).close();

    assertThat(GlobalOpenTelemetry.isSet()).isEqualTo(setBefore);
  }

  @Test
  void closingAConnectionClosesTheSessionItStarted() throws Exception {
    Path cli = fakeCli("echo '{\"port\":54321,\"session_token\":\"tok\"}'\n" + "cat > /dev/null\n");
    CLISession session =
        CLISession.start(cli, dir, false, Duration.ofMillis(500), Duration.ofMillis(500));

    Connection connection =
        Connection.getConnection(session.port(), session.sessionToken(), session);
    connection.close();

    assertThat(session.isAlive()).isFalse();
  }

  private Path fakeCli(String body) throws IOException {
    Path cli = dir.resolve("dagger");
    Files.writeString(cli, "#!/bin/sh\n" + body);
    Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
    return cli;
  }
}
