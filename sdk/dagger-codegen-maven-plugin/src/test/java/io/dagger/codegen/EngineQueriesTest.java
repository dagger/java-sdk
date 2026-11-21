package io.dagger.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.dagger.codegen.introspection.FormattedNames;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Codegen formats names through the nested session it runs in, when it has one. */
class EngineQueriesTest {

  private HttpServer server;
  private final List<String> requests = new ArrayList<>();
  private final List<String> authorizations = new ArrayList<>();
  private final List<String> clientMetadata = new ArrayList<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void postsTheQueryToTheSessionAndReturnsItsData() throws Exception {
    serve(200, "{\"data\":{\"f0\":[\"withGpu\"]}}");

    FormattedNames.QueryRunner runner =
        EngineQueries.fromEnvironment(session("s3cret"), "/nonexistent/dagger");
    String data;
    try (InputStream in = runner.query(FormattedNames.QUERY, "{\"names\":[\"withGPU\"]}")) {
      data = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(data).isEqualTo("{\"f0\":[\"withGpu\"]}");
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0))
        .contains("\"query\":\"query FormatIdentifiers(")
        .contains("\"variables\":{\"names\":[\"withGPU\"]}");
    assertThat(authorizations)
        .containsExactly(
            "Basic "
                + Base64.getEncoder().encodeToString("s3cret:".getBytes(StandardCharsets.UTF_8)));
    // No client metadata: the engine serves the request at its own version.
    assertThat(clientMetadata).containsExactly((String) null);
  }

  @Test
  void reportsTheErrorsOfTheResponse() throws Exception {
    serve(
        200,
        "{\"errors\":[{\"message\":\"Cannot query field \\\"formatIdentifiers\\\" on type"
            + " \\\"Query\\\".\"}],\"data\":null}");

    FormattedNames.QueryRunner runner = EngineQueries.fromEnvironment(session("t"), null);

    assertThatThrownBy(() -> runner.query(FormattedNames.QUERY, "{\"names\":[]}"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Cannot query field \"formatIdentifiers\"");
  }

  @Test
  void reportsAFailedResponse() throws Exception {
    serve(401, "unauthorized");

    FormattedNames.QueryRunner runner = EngineQueries.fromEnvironment(session("t"), null);

    assertThatThrownBy(() -> runner.query(FormattedNames.QUERY, "{\"names\":[]}"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("HTTP 401")
        .hasMessageContaining("unauthorized");
  }

  @Test
  void withoutASessionTheCliIsAsked() throws Exception {
    FormattedNames.QueryRunner runner =
        EngineQueries.fromEnvironment(Map.of(), "/nonexistent/dagger");

    assertThatThrownBy(() -> runner.query(FormattedNames.QUERY, "{\"names\":[]}"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("/nonexistent/dagger");
  }

  @Test
  void theCliGetsTheVariablesAsJson() {
    assertThat(DaggerCLIUtils.queryCommand("dagger", "{\"names\":[\"a\"]}"))
        .containsExactly("dagger", "query", "-s", "-M", "--var-json", "{\"names\":[\"a\"]}");
    assertThat(DaggerCLIUtils.queryCommand("dagger", null))
        .containsExactly("dagger", "query", "-s", "-M");
  }

  private Map<String, String> session(String token) {
    return Map.of(
        EngineQueries.SESSION_PORT,
        String.valueOf(server.getAddress().getPort()),
        EngineQueries.SESSION_TOKEN,
        token);
  }

  private void serve(int status, String body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/query",
        exchange -> {
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          clientMetadata.add(exchange.getRequestHeaders().getFirst("X-Dagger-Client-Metadata"));
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }
}
