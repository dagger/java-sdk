package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.dagger.client.modules.core.JsonConverter;
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

  public enum Color {
    RED,
    BLUE
  }

  public static class Colors {
    private List<Color> values;

    public Colors() {}
  }
}
