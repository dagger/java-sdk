package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dagger.client.graphql.GraphQLClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class QueryBuilderTest {

  @Test
  void nullableObjectQueryRebuildsTheObjectFromItsId() throws Exception {
    AtomicReference<String> request = new AtomicReference<>();
    try (Server server =
        Server.replying(
            "{\"data\":{\"typeDef\":{\"asObject\":{\"id\":\"ObjectTypeDef@abc\"}}}}", request)) {
      QueryBuilder resolved =
          new QueryBuilder(server.client())
              .chain("typeDef")
              .chain("asObject")
              .executeNullableObjectQuery("ObjectTypeDef");

      assertThat(request.get()).contains("query {typeDef {asObject {id}}}");
      assertThat(resolved).isNotNull();
      assertThat(resolved.chain(List.of("id")).buildQuery())
          .isEqualTo("query {node(id:\"ObjectTypeDef@abc\") {... on ObjectTypeDef {id}}}");
    }
  }

  @Test
  void nullableObjectQueryReturnsNullWhenTheFieldIsNull() throws Exception {
    AtomicReference<String> request = new AtomicReference<>();
    try (Server server = Server.replying("{\"data\":{\"typeDef\":{\"asObject\":null}}}", request)) {
      QueryBuilder resolved =
          new QueryBuilder(server.client())
              .chain("typeDef")
              .chain("asObject")
              .executeNullableObjectQuery("ObjectTypeDef");

      assertThat(request.get()).contains("query {typeDef {asObject {id}}}");
      assertThat(resolved).isNull();
    }
  }

  /** A GraphQL endpoint serving one canned response, so a real client can be exercised. */
  private record Server(HttpServer http, GraphQLClient client) implements AutoCloseable {

    static Server replying(String payload, AtomicReference<String> request) throws IOException {
      HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      http.createContext("/query", exchange -> respond(exchange, payload, request));
      http.start();
      String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/query";
      return new Server(http, new GraphQLClient(url, "token", Map.of()));
    }

    // GraphQLClient sets no request timeout, so every path must send a response.
    private static void respond(
        HttpExchange exchange, String payload, AtomicReference<String> request) throws IOException {
      try (exchange) {
        request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    }

    @Override
    public void close() {
      client.close();
      http.stop(0);
    }
  }
}
