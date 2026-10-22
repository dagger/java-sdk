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
public class Flags {
  @Keys private List<Boolean> values;
  @Delta private CollectionDelta selection;

  public Flags() {}

  Flags(List<Boolean> values) {
    this.values = values;
  }

  @Function
  @Get
  public Entry flag(boolean key) {
    return new Entry("f" + key);
  }

  @Function
  public List<String> removed()
      throws ExecutionException, DaggerQueryException, InterruptedException {
    return selection.removedKeys();
  }
}
