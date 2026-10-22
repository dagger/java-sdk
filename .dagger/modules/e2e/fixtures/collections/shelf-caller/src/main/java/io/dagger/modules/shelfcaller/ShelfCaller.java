package io.dagger.modules.shelfcaller;

import static io.dagger.client.modules.shelf.Shelf.shelf;

import io.dagger.client.exception.DaggerQueryException;
import io.dagger.client.modules.core.Platform;
import io.dagger.client.modules.shelf.ShelfItem;
import io.dagger.client.modules.shelf.ShelfItems;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Object;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Calls the shelf module's collections through its client. keys, get, list, subset and batch exist
 * only in the engine's projection of a collection, so the client has them only if it was generated
 * from that.
 */
@Object
public class ShelfCaller {
  @Function
  public String report() throws ExecutionException, DaggerQueryException, InterruptedException {
    ShelfItems items = shelf().items();
    List<String> listed = new ArrayList<>();
    for (ShelfItem item : items.list()) {
      listed.add(item.name());
    }
    return "keys="
        + items.keys()
        + " list="
        + listed
        + " get="
        + items.get("a").name()
        + " removed="
        + items.subset(List.of("c")).batch().removed();
  }

  @Function
  public String keyTypes() throws ExecutionException, DaggerQueryException, InterruptedException {
    return "number="
        + shelf().numbers().get(2).name()
        + " colors="
        + shelf().colors().keys()
        + " platform="
        + shelf().platforms().get(Platform.from("linux/arm64")).name()
        + " rack="
        + shelf().racks().get("r2").keys();
  }
}
