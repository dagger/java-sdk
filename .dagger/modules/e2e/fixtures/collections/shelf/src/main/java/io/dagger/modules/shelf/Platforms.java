package io.dagger.modules.shelf;

import io.dagger.client.exception.DaggerQueryException;
import io.dagger.client.modules.core.CollectionDelta;
import io.dagger.client.modules.core.Platform;
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
public class Platforms {
  @Keys private List<Platform> values;
  @Delta private CollectionDelta selection;

  public Platforms() {}

  Platforms(List<Platform> values) {
    this.values = values;
  }

  @Function
  @Get
  public Entry platform(Platform key) {
    return new Entry(key.convert());
  }

  @Function
  public List<String> removed()
      throws ExecutionException, DaggerQueryException, InterruptedException {
    return selection.removedKeys();
  }
}
