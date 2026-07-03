package io.dagger.client.engineconn;

import io.dagger.client.graphql.GraphQLClient;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Connection {

  static final Logger LOG = LoggerFactory.getLogger(Connection.class);

  private final GraphQLClient graphQLClient;

  Connection(GraphQLClient graphQLClient) {
    this.graphQLClient = graphQLClient;
  }

  public GraphQLClient getGraphQLClient() {
    return this.graphQLClient;
  }

  public void close() throws Exception {
    this.graphQLClient.close();
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
    // Inject OpenTelemetry context into headers
    Map<String, String> headers = new HashMap<>();
    GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .inject(Context.current(), headers, (carrier, key, value) -> carrier.put(key, value));

    return new Connection(
        new GraphQLClient(String.format("http://127.0.0.1:%d/query", port), token, headers));
  }
}
