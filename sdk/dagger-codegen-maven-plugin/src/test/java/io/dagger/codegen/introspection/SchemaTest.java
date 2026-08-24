package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SchemaTest {

  @Test
  void nullableObjectsAreSupportedFromBeta10Onwards() throws Exception {
    assertThat(schemaAtVersion("v1.0.0-beta.9").supportsNullableObjects()).isFalse();
    assertThat(schemaAtVersion("v1.0.0-beta.9-dev").supportsNullableObjects()).isFalse();
    assertThat(schemaAtVersion("v0.21.4").supportsNullableObjects()).isFalse();

    assertThat(schemaAtVersion("v1.0.0-beta.10").supportsNullableObjects()).isTrue();
    assertThat(schemaAtVersion("v1.0.0-beta.10-dev").supportsNullableObjects()).isTrue();
    assertThat(schemaAtVersion("v1.0.0-rc.1").supportsNullableObjects()).isTrue();
    assertThat(schemaAtVersion("v1.0.0").supportsNullableObjects()).isTrue();
  }

  @Test
  void buildMetadataDoesNotChangeTheVerdict() throws Exception {
    // The engine reports its version with a +<commit> suffix.
    assertThat(schemaAtVersion("v1.0.0-beta.9+1c6e07b1").supportsNullableObjects()).isFalse();
    assertThat(schemaAtVersion("v1.0.0-beta.10+1c6e07b1").supportsNullableObjects()).isTrue();
  }

  @Test
  void unknownVersionsAreTreatedAsDevelopmentBuilds() throws Exception {
    assertThat(schemaAtVersion(null).supportsNullableObjects()).isTrue();
    assertThat(schemaAtVersion("").supportsNullableObjects()).isTrue();
    assertThat(schemaAtVersion("development").supportsNullableObjects()).isTrue();
  }

  private static Schema schemaAtVersion(String version) throws Exception {
    byte[] introspection = "{\"__schema\":{\"types\":[]}}".getBytes(StandardCharsets.UTF_8);
    return Schema.initialize(new ByteArrayInputStream(introspection), version);
  }
}
