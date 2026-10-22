package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.dagger.client.modules.core.JsonConverter;
import io.dagger.client.modules.core.Platform;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Module objects reach the engine and come back through JsonConverter. */
class JsonConverterTest {

  @Test
  void aListOfEnumValuesConvertsBothWays() throws Exception {
    Colors colors = new Colors();
    colors.values = List.of(Color.RED, Color.BLUE);

    String json = JsonConverter.toJSON(colors).convert();

    assertThat(json).isEqualTo("{\"values\":[\"RED\",\"BLUE\"]}");
    assertThat(JsonConverter.fromJSON(json, Colors.class).values)
        .containsExactly(Color.RED, Color.BLUE);
  }

  @Test
  void aCustomScalarIsReadAsItsOwnType() throws Exception {
    Platform platform = JsonConverter.fromJSON("\"linux/arm64\"", Platform.class);
    Platforms platforms = JsonConverter.fromJSON("{\"values\":[\"linux/amd64\"]}", Platforms.class);

    assertThat(platform.convert()).isEqualTo("linux/arm64");
    assertThat(platforms.values.get(0)).isInstanceOf(Platform.class);
    assertThat(JsonConverter.toJSON(platforms).convert())
        .isEqualTo("{\"values\":[\"linux/amd64\"]}");
  }

  public enum Color {
    RED,
    BLUE
  }

  public static class Colors {
    private List<Color> values;

    public Colors() {}
  }

  public static class Platforms {
    private List<Platform> values;

    public Platforms() {}
  }
}
