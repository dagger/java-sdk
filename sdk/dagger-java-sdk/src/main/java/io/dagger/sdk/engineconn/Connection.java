package io.dagger.sdk.engineconn;

import io.dagger.sdk.graphql.GraphQLClient;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Connection {

  static final Logger LOG = LoggerFactory.getLogger(Connection.class);

  private final GraphQLClient graphQLClient;
  private final CLISession session;

  Connection(GraphQLClient graphQLClient) {
    this(graphQLClient, null);
  }

  Connection(GraphQLClient graphQLClient, CLISession session) {
    this.graphQLClient = graphQLClient;
    this.session = session;
  }

  public GraphQLClient getGraphQLClient() {
    return this.graphQLClient;
  }

  /** Close the client, and the session too when this connection started it. */
  public void close() throws Exception {
    try {
      this.graphQLClient.close();
    } finally {
      if (session != null) {
        session.close();
      }
    }
  }

  public static Connection get(String workingDir) throws IOException {
    return get(workingDir, false);
  }

  /**
   * Connect to the session in the environment ({@code DAGGER_SESSION_PORT} and {@code
   * DAGGER_SESSION_TOKEN}, as a module runtime or {@code dagger run} provide), or start one with
   * the dagger CLI when there is none. A started session is closed with the connection.
   */
  public static Connection get(String workingDir, boolean loadWorkspaceModules) throws IOException {
    String portStr = System.getenv("DAGGER_SESSION_PORT");
    String sessionToken = System.getenv("DAGGER_SESSION_TOKEN");
    if (portStr != null && sessionToken != null) {
      try {
        return getConnection(Integer.parseInt(portStr), sessionToken, null);
      } catch (NumberFormatException nfe) {
        throw new IOException("invalid port value in DAGGER_SESSION_PORT", nfe);
      }
    }
    if (portStr != null || sessionToken != null) {
      throw new IOException(
          "DAGGER_SESSION_PORT and DAGGER_SESSION_TOKEN must be set together; only one is");
    }
    return fromCLI(CLISession.resolveCLI(), workingDir, loadWorkspaceModules);
  }

  static Connection fromCLI(String cli, String workingDir, boolean loadWorkspaceModules)
      throws IOException {
    CLISession session = CLISession.start(cli, Path.of(workingDir), loadWorkspaceModules);
    return getConnection(session.port(), session.sessionToken(), session);
  }

  private static Connection getConnection(int port, String token, CLISession session) {
    // Inject OpenTelemetry context into headers
    Map<String, String> headers = new HashMap<>();
    GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .inject(Context.current(), headers, (carrier, key, value) -> carrier.put(key, value));

    return new Connection(
        new GraphQLClient(String.format("http://127.0.0.1:%d/query", port), token, headers),
        session);
  }
}
