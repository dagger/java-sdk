package io.dagger.sdk;

import io.dagger.sdk.exception.DaggerQueryException;
import jakarta.json.bind.annotation.JsonbTypeSerializer;
import java.util.concurrent.ExecutionException;

@JsonbTypeSerializer(IDAbleSerializer.class)
public interface IDAble<S> {

  /** Returns the unified ID for this object. */
  S id() throws ExecutionException, InterruptedException, DaggerQueryException;
}
