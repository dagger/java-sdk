package io.dagger.modules.shelf;

import io.dagger.module.annotation.Check;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Object;

@Object
public class Item {
  public String name;

  public Item() {}

  Item(String name) {
    this.name = name;
  }

  /** Rejects item c. The engine passes the collection the selectors narrowed, as items. */
  @Function
  @Check
  public void verify(Items items) {
    if (name.equals("item:c")) {
      throw new IllegalStateException(
          "verified " + String.join(",", items.selected()) + ": c is rejected");
    }
  }
}
