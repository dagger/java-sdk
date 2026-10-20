package io.dagger.modules.shelf;

import io.dagger.module.annotation.Collection;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Get;
import io.dagger.module.annotation.Keys;
import io.dagger.module.annotation.Object;
import java.util.List;

/** A collection whose items are collections. */
@Object
@Collection
public class Racks {
  @Keys private List<String> names;

  public Racks() {}

  Racks(List<String> names) {
    this.names = names;
  }

  @Function
  @Get
  public Rack rack(String name) {
    return new Rack(List.of(name + "-1", name + "-2"));
  }

  @Function
  public int count() {
    return names.size();
  }
}
