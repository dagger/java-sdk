package io.dagger.modules.shelf;

import io.dagger.client.exception.DaggerQueryException;
import io.dagger.client.modules.core.CollectionDelta;
import io.dagger.module.annotation.Collection;
import io.dagger.module.annotation.Delta;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Get;
import io.dagger.module.annotation.Keys;
import io.dagger.module.annotation.Object;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Object
@Collection
public class Numbers {
  @Keys private List<Integer> values;
  @Delta private CollectionDelta selection;

  public Numbers() {}

  Numbers(List<Integer> values) {
    this.values = values;
  }

  @Function
  @Get
  public Entry number(int key) {
    return new Entry("n" + key);
  }

  @Function
  public List<String> removed()
      throws ExecutionException, DaggerQueryException, InterruptedException {
    return selection.removedKeys();
  }
}
