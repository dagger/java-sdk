package io.dagger.client.engineconn;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.smallrye.graphql.client.vertx.dynamic.VertxDynamicGraphQLClientBuilder;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Connection {

  static final Logger LOG = LoggerFactory.getLogger(Connection.class);

  private final DynamicGraphQLClient graphQLClient;
  private final Vertx vertx;

  Connection(DynamicGraphQLClient graphQLClient, Vertx vertx) {
    this.graphQLClient = graphQLClient;
    this.vertx = vertx;
  }

  public DynamicGraphQLClient getGraphQLClient() {
    return this.graphQLClient;
  }

  public void close() throws Exception {
    this.graphQLClient.close();
    this.vertx.close();
  }

  public static Connection get(String workingDir) throws IOException {
    return get(workingDir, false);
  }

  public static Connection get(String workingDir, boolean loadWorkspaceModules) throws IOException {
    String portStr = System.getenv("DAGGER_SESSION_PORT");
    String sessionToken = System.getenv("DAGGER_SESSION_TOKEN");
    if (portStr == null || sessionToken == null) {
      throw new IOException(
          "DAGGER_SESSION_PORT and DAGGER_SESSION_TOKEN must be set. The Java SDK runtime only "
              + "connects to an existing Dagger session; run it through the Dagger engine "
              + "(dagger call) or an externally provided session.");
    }
    try {
      return getConnection(Integer.parseInt(portStr), sessionToken);
    } catch (NumberFormatException nfe) {
      throw new IOException("invalid port value in DAGGER_SESSION_PORT", nfe);
    }
  }

  private static Connection getConnection(int port, String token) {
    Vertx vertx = Vertx.vertx();
    // Inject Dagger Cloud token
    String encodedToken =
        Base64.getEncoder().encodeToString((token + ":").getBytes(StandardCharsets.UTF_8));

    VertxDynamicGraphQLClientBuilder clientBuilder =
        new VertxDynamicGraphQLClientBuilder()
            .vertx(vertx)
            .url(String.format("http://127.0.0.1:%d/query", port))
            .header("authorization", String.format("Basic %s", encodedToken));

    // Inject OpenTelemetry context into headers
    GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .inject(
            Context.current(), clientBuilder, (carrier, key, value) -> carrier.header(key, value));

    return new Connection(clientBuilder.build(), vertx);
  }
}
