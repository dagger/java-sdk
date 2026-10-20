package io.dagger.modules.shelf;

import io.dagger.module.annotation.Collection;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Get;
import io.dagger.module.annotation.Keys;
import io.dagger.module.annotation.Object;
import java.util.List;

@Object
@Collection
public class Rack {
  @Keys private List<String> slots;

  public Rack() {}

  Rack(List<String> slots) {
    this.slots = slots;
  }

  @Function
  @Get
  public Entry slot(String name) {
    return new Entry(name);
  }

  @Function
  public List<String> selected() {
    return slots;
  }
}
