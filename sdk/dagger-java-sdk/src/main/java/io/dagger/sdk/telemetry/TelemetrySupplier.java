package io.dagger.sdk.telemetry;

@FunctionalInterface
public interface TelemetrySupplier<T> {

  T get() throws Exception;
}
