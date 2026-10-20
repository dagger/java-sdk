package io.dagger.modules.shelf;

import io.dagger.client.exception.DaggerQueryException;
import io.dagger.client.modules.core.CollectionDelta;
import io.dagger.module.annotation.Check;
import io.dagger.module.annotation.Collection;
import io.dagger.module.annotation.Delta;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Get;
import io.dagger.module.annotation.Keys;
import io.dagger.module.annotation.Object;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Named items. The members carry names of their own, so the engine finds them by the markers, not
 * by the names it falls back to.
 */
@Object
@Collection
public class Items {
  @Keys private List<String> names;
  @Delta private CollectionDelta selection;
  private String prefix;

  public Items() {}

  Items(List<String> names, String prefix) {
    this.names = names;
    this.prefix = prefix;
  }

  @Function
  @Get
  public Item lookup(String name) {
    return new Item(prefix + name);
  }

  @Function
  public List<String> selected() {
    return names;
  }

  @Function
  public List<String> added()
      throws ExecutionException, DaggerQueryException, InterruptedException {
    return selection.addedKeys();
  }

  @Function
  public List<String> removed()
      throws ExecutionException, DaggerQueryException, InterruptedException {
    return selection.removedKeys();
  }

  /** Returns this collection, changed, so it keeps its base. */
  @Function
  public Items change(List<String> names) {
    this.names = names;
    return this;
  }

  /** Returns a new collection, which starts a base of its own. */
  @Function
  public Items fresh() {
    return new Items(List.of("z"), prefix);
  }

  /** Replaces Item.verify: runs once over the selected items, and rejects item c. */
  @Function
  @Check
  public void verify() {
    if (names.contains("c")) {
      throw new IllegalStateException("verified " + String.join(",", names) + ": c is rejected");
    }
  }
}
