package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArgumentsTest {

  @Test
  void aFloatArgumentIsRenderedAsANumber() throws Exception {
    Float ratio = 1.5f;

    assertThat(Arguments.newBuilder().add("key", ratio).build().toGraphQL()).isEqualTo("key:1.5");
    assertThat(Arguments.newBuilder().add("keys", List.of(0.5f, 1.5f)).build().toGraphQL())
        .isEqualTo("keys:[0.5,1.5]");
  }
}
