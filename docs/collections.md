# Collections

A collection is an object that holds a set of items a module discovers at run
time, each with a unique key. This SDK needs an engine v1.0.0-beta.18 or later.
The engine serves collections from v1.0.0-beta.15
([dagger/dagger#14221](https://github.com/dagger/dagger/pull/14221)).
v1.0.0-beta.17 adds the projected types that every call into a collection
resolves ([dagger/dagger#14447](https://github.com/dagger/dagger/pull/14447)),
and item selectors on `dagger call`
([dagger/dagger#14590](https://github.com/dagger/dagger/pull/14590),
[dagger/dagger#14602](https://github.com/dagger/dagger/pull/14602)).

## Declare a collection

Mark an `@Object` class with `@Collection`. A collection has two members:

- A keys field, marked with `@Keys`. Its type is a list of a scalar or an enum,
  such as `List<String>`, and it holds one unique key per item.
- A get function, a `@Function` method marked with `@Get`. It takes one
  argument of the key type and returns the item for that key.

Without the markers, the engine looks for a field named `keys` and a function
named `get`.

```java
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
public class Items {
  @Keys private List<String> names;
  @Delta private CollectionDelta selection;

  public Items() {}

  Items(List<String> names) {
    this.names = names;
  }

  @Function
  @Get
  public Item lookup(String name) {
    return new Item(name);
  }

  @Function
  public List<String> selected() {
    return names;
  }

  @Function
  public List<String> removed()
      throws ExecutionException, DaggerQueryException, InterruptedException {
    return selection.removedKeys();
  }

  @Function
  public Items change(List<String> names) {
    this.names = names;
    return this;
  }
}
```

```java
@Object
public class Shelf {
  @Function
  public Items items() {
    return new Items(List.of("b", "a", "c"));
  }
}
```

`@Keys` and `@Delta` make a field part of the object's state, whatever its
visibility. `CollectionDelta` is a core type: import it from
`io.dagger.client.modules.core`, like `Directory` or `Container`.

The keys field can hold `String`, `Integer`, `Float`, `Boolean`, an `@Enum`,
or a core scalar such as `Platform`. The get function takes the same type, or
its primitive.

## What callers see

The engine replaces the collection's API with the same functions on every
collection:

| Function | Returns |
|---|---|
| `keys` | The keys, in the order the module returned them |
| `list` | Every item |
| `get` | The item with the given key |
| `subset` | A collection of the items with the given keys |
| `batch` | The collection's other functions |

Every function other than the get function is a batch function. The engine
calls a batch function on a copy of the collection whose keys field holds only
the selected keys. In the example, `selected` returns those keys. The engine
adds `batch` only when the collection has a batch function.

A batch check or generator replaces the check or generator with the same name
on the item type. Without one, the item's check runs once per selected item.

An item can be a collection itself.

## Call a collection through a client

A client generated from a module with a collection has the same functions,
because the engine generates the client from the projected schema. The engine
prefixes the name of each type a module declares with the module name, unless
the name already starts with it, so `Items` in the `shelf` module is
`ShelfItems` in its client:

```java
import static io.dagger.client.modules.shelf.Shelf.shelf;

import io.dagger.client.modules.shelf.ShelfItems;

ShelfItems items = shelf().items();
List<String> keys = items.keys();
String name = items.get("a").name();
List<String> removed = items.subset(List.of("c")).batch().removed();
```

## Select items on the command line

The CLI adds a selector flag for each item type. The flag is named after the
module and the item type, so `Item` in the `shelf` module adds `--shelf-item`:

```shell
dagger call items name --shelf-item=a
dagger call --shelf-item=a name
dagger call items selected --shelf-item=a --shelf-item=c
dagger call items get --key=a name
```

A selector after the collection selects within it. A selector before the first
function starts the pipeline at the item. A repeated selector selects a subset
for a batch function. `get` and `subset` remain available by name.

When more than one function returns the collection, each flag also names its
function. If `items` and `others` both return `Items`, the flags are
`--shelf-items-item` and `--shelf-others-item`, and `--shelf-item` is an
unknown flag.

The help of a command after the collection lists the flags under `SELECTION`,
for example `dagger call items selected --help`. `dagger call --help` does not
list them.

`dagger check` takes the flags for a module that the workspace registers. For
a check `verify` on `Items`:

```shell
dagger check --shelf --check=verify --shelf-item=a --shelf-item=b
```

With `-m`, the flags are unknown. Put the selectors in the check's link
instead:

```shell
dagger check -m ./shelf 'items/verify?shelf-item=a&shelf-item=b'
```

## Collection delta

A field marked with `@Delta`, or named `delta`, receives the changes since the
module created the collection. Its type is `CollectionDelta`. The engine fills
it before each call. `selection.addedKeys()` and `selection.removedKeys()`
return the keys added and removed. Both are empty when the collection holds
every item the module returned.

The delta keys are text, whatever the key type. An `Integer` key `2` reads
`"2"`. An enum key reads its constant name, such as `"RED"`.

## Base state

The engine compares the current keys with the keys of the collection's base,
the collection the module first returned. The engine hands the base to the
module with each call, as state outside the module's schema. The SDK keeps
that state for the one call it serves, and needs no field in the class:

- A function that returns its receiver, `this`, keeps the receiver's base. In
  the example, the collection that `change(List.of("c", "d"))` returns has a
  delta with `d` added and `b` and `a` removed.
- Any other result starts a base of its own, a new instance built from the
  receiver's fields included. Its delta is empty.

So change a collection in place and return `this` to keep its delta. Return a
new instance to start a new base.

## Entrypoints

The annotation processor writes the markers into both entrypoints: the Dang
entrypoint's `types`, which the engine reads without building the module, and
`register()`, which a module on the runtime runs.

A collection without a get function fails to load. When a method is marked
`@Get` without `@Function`, generation stops with an error that names it:

```
Method 'lookup' of 'Items' is marked @Get but not @Function. Add @Function: a collection's get function must be exposed.
```
