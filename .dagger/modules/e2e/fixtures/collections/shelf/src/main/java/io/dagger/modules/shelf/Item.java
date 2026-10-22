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

  /** Items.verify replaces this check, so it never runs on an item of Items. */
  @Function
  @Check
  public void verify() {
    throw new IllegalStateException("the item's verify ran for " + name);
  }
}
