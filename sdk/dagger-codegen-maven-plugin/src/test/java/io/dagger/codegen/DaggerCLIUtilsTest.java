package io.dagger.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DaggerCLIUtilsTest {

  private static final String DAGGER_VERSION =
      """
      version:     v1.0.0-beta.10
      commit:      0e19eba6
      dirty:       no
      platform:    linux/amd64
      runner-host: image://registry.dagger.io/engine:v1.0.0-beta.10
      """;

  @Test
  void readsTheVersionOffTheAlignedOutput() {
    assertThat(DaggerCLIUtils.parseVersion(DAGGER_VERSION)).isEqualTo("v1.0.0-beta.10");
  }

  @Test
  void stripsTheVPrefixOfAPlainRelease() {
    assertThat(DaggerCLIUtils.parseVersion("version:     v0.21.4\ncommit:      abc\n"))
        .isEqualTo("0.21.4");
  }

  @Test
  void outputWithoutAVersionLineIsAnError() {
    assertThatThrownBy(() -> DaggerCLIUtils.parseVersion("something else entirely\n"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no version line");
  }
}
