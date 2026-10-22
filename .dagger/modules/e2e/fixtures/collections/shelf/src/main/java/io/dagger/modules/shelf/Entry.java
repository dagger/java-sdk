package io.dagger.modules.shelf;

import io.dagger.module.annotation.Object;

@Object
public class Entry {
  public String name;

  public Entry() {}

  Entry(String name) {
    this.name = name;
  }
}
