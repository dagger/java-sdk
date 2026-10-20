# Dagger Java SDK

The user experience for authoring Dagger modules in Java, and for calling
modules from Java programs.

A Java module is **self-contained**: the Dagger Java SDK is vendored into it as
buildable source, every file generation writes is committed, and **nothing is
generated when the engine loads the module**. The module builds with a plain
`mvn package`, in an IDE or in CI, without Dagger.

> This repository owns both halves of the Java SDK. The root Dang module
> (`main.dang` / `mod.dang`) owns code generation and scaffolding, and runs when
> the engine asks it to generate a scope. A new module runs on a **Dang
> entrypoint** generated into it: the engine reads the module's types from it
> without building the module, and builds the jar only to call a function. A
> module generated before the entrypoint keeps the build/package-only Dang
> module under `runtime/`, referenced as `github.com/dagger/java-sdk/runtime`,
> until it moves to the entrypoint.

> [!IMPORTANT]
> This SDK needs an engine `v1.0.0-beta.18` or later. On an older engine,
> `dagger module init` and `dagger generate` stop before they write anything,
> with a message that names the floor and the engine's version.
>
> It writes module manifests through
> [`github.com/dagger/sdk-helpers`](https://github.com/dagger/sdk-helpers), the
> manifest builder that
> [dagger/dagger#13992](https://github.com/dagger/dagger/pull/13992) moved out of
> the engine, pinned at its `v1.0.2` tag.

## Install

```sh
dagger module install github.com/dagger/java-sdk
```

The engine recognizes the SDK interface and records the module as the `java` SDK
in `dagger.toml`:

```toml
[modules.java-sdk]
source = "github.com/dagger/java-sdk"

[sdks.java]
module = "java-sdk"
```

## Create a module

```sh
dagger module init java --name my-module --path .dagger/modules/my-module
```

The engine records the module scope in `dagger.toml` and calls this SDK's
`generateScope`, which renders the template, writes `dagger-module.toml`, and
generates the SDK bindings in one step:

```
<module>/
  dagger-module.toml                                            # name and [entrypoint] only
  pom.xml                                                       # two-pass build; dagger.proc defaults to "none"
  src/main/java/io/dagger/modules/<pkg>/<Module>.java
  src/main/java/io/dagger/modules/<pkg>/package-info.java
  src/generated/java/io/dagger/gen/entrypoint/Entrypoint.java   # generated entrypoint
  src/generated/dang/entrypoint/main.dang                       # the module's types and its calls
  src/generated/dang/entrypoint/build.dang                      # the jar's Maven build
  sdk/src/main/java/...                                         # vendored SDK library
  sdk/src/processor/java/...                                    # vendored annotation processor
  clients/core/src/main/java/...                                # the engine's core API (from the engine schema)
  clients/<name>/src/main/java/...                              # one package per module client
```

The SDK settings become typed flags on `dagger module init java` and are
persisted on the scope:

```sh
dagger module init java --name my-module --template empty
```

`--template` picks a starter under `templates/`: `default` (a small working
module), `empty` (a bare object class), or `legacy`. `--lock` records, for a
module on the runtime, the commit each git client resolved to as a pin in its
manifest; without it a client added at a branch or tag keeps following it. A
client of a module on the entrypoint always loads the commit it was generated
against.

Because everything is committed and the pom defaults `dagger.proc=none`, a
plain `mvn package` runs no annotation processor: it compiles the committed
entrypoint.

## Generate

```sh
dagger generate
```

This regenerates every scope recorded in `dagger.toml`: each module, and each
plain project with clients. The engine narrows the set to the scopes containing
your current directory, so running it inside a module regenerates that module.

Generation runs Maven in containers this SDK controls: it builds the vendored
codegen plugin, generates core from the engine's introspection schema and each
client from its target's schema, vendors the SDK library and annotation
processor as source, and runs the processor once to produce the entrypoint. It
does not delegate code generation back to the engine.

A Java scope is a directory with a `pom.xml`. `findClientRoot` answers with the
nearest one at or above your current directory, which is how
`dagger module client add` and friends find the module or the
[plain project](#clients-in-a-plain-maven-project) you are standing in. A
project built with anything but Maven has no `pom.xml`, so this SDK reports no
scope for it.

## The Dang entrypoint

`dagger generate` writes the module's types into
`src/generated/dang/entrypoint/main.dang`, so `dagger functions` lists them
without a build. A call builds the jar with Maven from the module directory, in
the images the runtime pins, and runs it once per call with the request on
standard input. The dispatch is the switch the annotation processor generates in
`Entrypoint.java`; a failed function's error reaches the caller with its text.

The `dangEntrypoint` setting picks the entrypoint or the runtime:

- Unset, a new module gets the entrypoint, and an existing module keeps what it
  has.
- `true` moves an existing module to the entrypoint.
- `false` keeps a module on `github.com/dagger/java-sdk/runtime`. A module on
  the entrypoint refuses it; remove `[entrypoint]` from its
  `dagger-module.toml` to move it back.

Set it on one module's scope in `dagger.toml`, or under
`[modules.java-sdk.settings]` for every Java module of the workspace:

```toml
[sdks.java.scopes."path/to/module".settings]
dangEntrypoint = true
```

A module on the entrypoint calls other modules through its
[clients](#module-clients). Its `dagger-module.toml` holds its name and the
entrypoint only, so generation refuses a manifest setting it would drop, such
as `include`.

`@Function(cache = ...)` sets a function's cache policy on either path:
`"never"`, `"session"`, or a duration such as `"10m"` after which the result is
computed again.

## The core API

The engine's own API is a client package, `io.dagger.client.modules.core`,
generated under `clients/core`. Module code enters it through `core()`, a
static method of its root class `Core`:

```java
import static io.dagger.client.modules.core.Core.core;

import io.dagger.client.modules.core.Container;

Container base = core().container().from("alpine:3.22"); // the session Dagger.dag() holds
Container same = core(session).container().from("alpine:3.22"); // an io.dagger.client.Session
```

`Dagger.dag()` returns that `io.dagger.client.Session`, which has no API of
its own. The hand-written runtime stays in `io.dagger.client`: `Dagger`,
`Session` and the exceptions under `io.dagger.client.exception`.

### Upgrade a module written against `dag()`

This SDK keeps no compatibility layer. Module code that calls
`dag().container()` or imports a core type from `io.dagger.client` no longer
compiles. `dagger generate` refuses a `pom.xml` that still compiles
`sdk/src/generated/java`, where core was generated before, and then fails with
the compiler's error:

```
cannot find symbol
  symbol:   method container()
  location: class io.dagger.client.Session
```

To upgrade the module:

1. In `pom.xml`, replace `<source>sdk/src/generated/java</source>` with
   `<source>clients/core/src/main/java</source>`.
2. Replace `import static io.dagger.client.Dagger.dag;` with
   `import static io.dagger.client.modules.core.Core.core;`.
3. Replace each `dag().` call with `core().`.
4. Replace each import of a core type, such as `io.dagger.client.Container`,
   with the same class in `io.dagger.client.modules.core`.
5. Replace each use of the class `Client` with `Core`, such as
   `Client.ClientDepArguments` with `Core.ClientDepArguments`.
6. Run `dagger generate`.

## Module clients

A module calls other modules through generated clients:

```sh
dagger module client add <module-ref>
```

On the Dang entrypoint, each client is a package of its own,
`io.dagger.client.modules.<pkg>`, generated under `clients/<name>`. `<pkg>` is
the module name in lowercase, with only its letters and digits. Module code
enters a client by static import, as it enters core:

```java
import static io.dagger.client.modules.clientdep.ClientDep.clientDep;

String greeting = clientDep().greeting();
```

A client loads its target with `serveModule` when the module first calls it. A
local target is named by its path from the workspace root, and the engine
resolves that path in the calling module's own tree. A git target is loaded at
the commit its ref resolved to when the client was generated, even after a newer
tag matches the ref; `dagger generate` moves the client to the newer commit.

A client types each check of its target as the core object `Check`, whatever
type the check's author wrote and whatever engine version the target declares.
Reading `pass()` or `error()` runs the check. `error()` is empty when the check
passes:

```java
import io.dagger.client.modules.core.Check;

Check test = clientDep().test();
boolean passed = test.pass();
String failure = passed ? "" : test.error().orElseThrow().message();
```

The module's `dagger-module.toml` lists no dependencies. Generation adds a
`dagger-clients` profile to `pom.xml`, which compiles each client's package. It
rewrites the profile on every generation and removes it with the last client, so
do not edit it.

A module that moves to the entrypoint keeps its `[[dependencies]]` as clients:
generation drops each dependency that a client of the scope covers, and refuses
a dependency that no client covers, by name. Add a client for it, or remove it
from the manifest.

### Clients on the runtime

On the runtime (`dangEntrypoint = false`), the client set becomes the module's
dependency set instead. Each client is recorded in the manifest the module has —
`dagger-module.toml`, or the `dagger.json` of a pre-1.0 module — and its types
are generated into the core package, so module code reaches it through
`core()`, as `core().clientDep()`. A client that is removed is dropped from
both.

> [!WARNING]
> On the runtime, the client set is the *whole* dependency set. A module that
> recorded dependencies before this interface existed has no clients recorded
> for them, so the first `dagger generate` drops them. Re-register each one
> first:
>
> ```sh
> dagger module client add <module-ref>
> ```

## Clients in a plain Maven project

A plain Maven project, a `pom.xml` with no `dagger-module.toml`, calls modules
through clients too. Add a client from inside the project:

```sh
dagger module client add <module-ref> --sdk java
```

Generation writes the tree a module gets, without the module's own files:

```
<project>/
  pom.xml                            # gains the dagger-sdk and dagger-clients profiles
  sdk/src/main/java/...              # vendored SDK library
  sdk/src/processor/java/...         # vendored annotation processor, not compiled here
  clients/core/src/main/java/...     # the engine's core API, as a client sees it
  clients/<name>/src/main/java/...   # one package per client
```

The `dagger-sdk` profile compiles `sdk/` and `clients/core` and adds the
dependencies they need. The `dagger-clients` profile compiles each client's
package, as in a module. Generation rewrites both profiles every time, so do not
edit them. The SDK sources need Java 17 or later.

A client package is the same in a project as in a module. Core is not: a
project gets the engine's client API, which has `host()` and the engine's own
types that a module's core hides.

A program calls a client with no connection code:

```java
import static io.dagger.client.modules.clientdep.ClientDep.clientDep;

public class App {
  public static void main(String[] args) throws Exception {
    System.out.println(clientDep().greeting());
  }
}
```

The first call starts `dagger session` in the program's working directory, with
the CLI that `_EXPERIMENTAL_DAGGER_CLI_BIN` names or the `dagger` on the `PATH`.
The session stops before the program exits. A local target is named by its path
from the workspace root, so run the program inside the workspace. A program that
Dagger starts uses the session in its environment instead.

The project builds with `mvn package` and no engine. Its `pom.xml` decides how
to package the program. The SDK logs through SLF4J and adds no backend for it:
add one, such as `slf4j-simple`, to see the session's progress.

## Collections

`@Collection` marks an object whose items the engine serves by key, as `keys`,
`get`, `list`, `subset` and `batch`. `@Keys` marks its keys field, `@Get` its
get function, and `@Delta` a `CollectionDelta` field that receives the keys
added and removed since the module returned it. A function that returns its
receiver keeps the collection's base; a new instance starts its own.
[docs/collections.md](docs/collections.md) describes them.

## Pre-1.0 modules

A module configured by `dagger.json` is migrated the first time it is generated:
its contents move into a `dagger-module.toml` and the `dagger.json` is removed,
so the two cannot disagree. The runtime it already names is preserved — a module
on the engine's builtin `java` runtime stays there, and does not silently move
onto this repository's.

## Test

```sh
dagger check
```

On the engine the CLI runs on, this runs the SDK library's unit tests
(`packager:unit-tests`) and the staleness checks of the prebuilt assets and the
templates. The `engine-e2e` checks build engines from the dagger/dagger commits
pinned in `.dagger/modules/engine-e2e`:

- `engine-e2e:floor-check` builds the floor release, `v1.0.0-beta.18`, and runs
  every e2e check on it.
- `engine-e2e:dev-sdk-check` installs this checkout as the `java` SDK on the
  same engine, initializes a Java module through the CLI, and calls it.
- `engine-e2e:below-floor-check` builds `v1.0.0-beta.17` and checks that
  generation and `dagger module init` refuse it.
- `engine-e2e:cache-policy-sessions-check`, `pinned-git-client-check` and
  `standalone-client-check` run calls in sessions of their own on the same
  engine, which the e2e checks cannot do: their calls share the check's
  session.

`dagger.toml` does not install the `e2e` module, so `dagger check` runs the e2e
checks only on the floor engine. AGENTS.md has the commands that run them on the
local engine.
