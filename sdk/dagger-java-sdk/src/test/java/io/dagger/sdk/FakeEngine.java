package io.dagger.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dagger.sdk.graphql.GraphQLClient;
import jakarta.json.Json;
import jakarta.json.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A GraphQL endpoint serving one canned response, so a real client can be exercised. */
record FakeEngine(HttpServer http, GraphQLClient client, List<String> requests)
    implements AutoCloseable {

  static FakeEngine replying(String payload) throws IOException {
    List<String> requests = new CopyOnWriteArrayList<>();
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/query", exchange -> respond(exchange, payload, requests));
    http.start();
    String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/query";
    return new FakeEngine(http, new GraphQLClient(url, "token", Map.of()), requests);
  }

  /** The last request body received. */
  String request() {
    return requests.isEmpty() ? null : requests.get(requests.size() - 1);
  }

  /** The GraphQL query inside the last request, unescaped. */
  String query() {
    try (JsonReader reader = Json.createReader(new StringReader(request()))) {
      return reader.readObject().getString("query");
    }
  }

  // GraphQLClient sets no request timeout, so every path must send a response.
  private static void respond(HttpExchange exchange, String payload, List<String> requests)
      throws IOException {
    try (exchange) {
      requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
