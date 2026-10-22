package io.dagger.modules.shelf;

import io.dagger.client.modules.core.Platform;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Object;
import java.util.List;

/** Collections of every key type the engine takes, and a collection of collections. */
@Object
public class Shelf {
  @Function
  public Items items() {
    return new Items(List.of("b", "a", "c"), "item:");
  }

  @Function
  public Numbers numbers() {
    return new Numbers(List.of(2, 1, 3));
  }

  @Function
  public Ratios ratios() {
    return new Ratios(List.of(0.5f, 1.5f));
  }

  @Function
  public Flags flags() {
    return new Flags(List.of(true, false));
  }

  @Function
  public Colors colors() {
    return new Colors(List.of(Color.RED, Color.BLUE));
  }

  @Function
  public Platforms platforms() {
    return new Platforms(List.of(Platform.from("linux/amd64"), Platform.from("linux/arm64")));
  }

  @Function
  public Racks racks() {
    return new Racks(List.of("r1", "r2"));
  }
}
