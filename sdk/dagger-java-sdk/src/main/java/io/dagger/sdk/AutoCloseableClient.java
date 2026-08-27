package io.dagger.sdk;

import io.dagger.client.Client;
import io.dagger.sdk.engineconn.Connection;

public class AutoCloseableClient extends Client implements AutoCloseable {
  AutoCloseableClient(Connection connection) {
    super(connection);
  }

  AutoCloseableClient(QueryBuilder queryBuilder) {
    super(queryBuilder);
  }
}
