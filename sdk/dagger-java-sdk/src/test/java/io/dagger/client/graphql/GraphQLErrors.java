package io.dagger.client.graphql;

import jakarta.json.Json;
import java.io.StringReader;

/** Builds the GraphQL errors the engine replies with, for tests outside this package. */
public final class GraphQLErrors {

  private GraphQLErrors() {}

  /** The error a GraphQL response holds as {@code json}. */
  public static GraphQLError of(String json) {
    return GraphQLError.fromJson(Json.createReader(new StringReader(json)).readObject());
  }
}
