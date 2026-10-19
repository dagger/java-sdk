package io.dagger.client.engineconn;

import io.dagger.client.graphql.GraphQLClient;

/** Connections over a given transport, for tests outside this package. */
public final class TestConnections {

  private TestConnections() {}

  public static Connection over(GraphQLClient client) {
    return new Connection(client, null);
  }
}
