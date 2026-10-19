# Dagger Java SDK

The user experience for authoring Dagger modules in Java.

Modules created with this tool use a **self-contained** layout: the Dagger Java
SDK is vendored into the module as real, buildable source, all generated files
are committed to version control, and **no code generation runs at module load
time** — the runtime just builds and packages the module.

> This repository owns both halves of the Java SDK. The root Dang module
> (`main.dang` / `mod.dang`) owns code generation and scaffolding, and runs when
> the engine asks it to generate a scope. The module *runtime* (the SDK
> contract: building and packaging Java modules) is the build/package-only Dang
> module under `runtime/`; new modules reference it as
> `github.com/dagger/java-sdk/runtime`.

> [!IMPORTANT]
> This SDK needs an engine `v1.0.0-beta.18` or later. On an older engine,
> `dagger module init` and `dagger generate` stop before they write anything,
> with a message that names the floor and the engine's version.
>
> It writes module manifests through
> [`github.com/dagger/sdk-helpers`](https://github.com/dagger/sdk-helpers), the
> manifest builder that
> [dagger/dagger#13992](https://github.com/dagger/dagger/pull/13992) moved out of
> the engine, at the `v1.0.2` tag rather than the `dagger.io/sdk/helpers@v1`
> vanity address the other SDKs moved to: engine `v1.0.0-beta.11` resolves that
> address as a local path and then fails to load the workspace at all.

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
  dagger-module.toml                                            # [runtime] source = github.com/dagger/java-sdk/runtime
  pom.xml                                                       # two-pass build; dagger.proc defaults to "none"
  src/main/java/io/dagger/modules/<pkg>/<Module>.java
  src/main/java/io/dagger/modules/<pkg>/package-info.java
  src/generated/java/io/dagger/gen/entrypoint/Entrypoint.java   # generated entrypoint
  sdk/src/main/java/...                                         # vendored SDK library
  sdk/src/processor/java/...                                    # vendored annotation processor
  sdk/src/generated/java/...                                    # client bindings (from the engine schema)
```

The SDK settings become typed flags on `dagger module init java` and are
persisted on the scope:

```sh
dagger module init java --name my-module --template empty
```

`--template` picks a starter under `templates/`: `default` (a small working
module), `empty` (a bare object class), or `legacy`. `--lock` records the commit
each git client resolved to as a pin; without it a client added at a branch or
tag keeps following it.

Because everything is committed and the pom defaults `dagger.proc=none`, the
module builds with a plain `mvn package` (no annotation processor at build time)
— in an IDE or CI, without Dagger.

## Generate

```sh
dagger generate
```

This regenerates every module scope recorded in `dagger.toml`. The engine
narrows the set to the scopes containing your current directory, so running it
inside a module regenerates that module.

Generation runs Maven in containers this SDK controls: it builds the vendored
codegen plugin, generates the client bindings from the engine's introspection
schema, vendors the SDK library and annotation processor as source, and runs the
processor once to produce the entrypoint. It does not delegate code generation
back to the engine.

## Module scopes

A Java scope is a directory with a `pom.xml`. `findClientRoot` answers with the
nearest one at or above your current directory, which is how
`dagger module client add` and friends find the module you are standing in. A
project built with anything but Maven has no `pom.xml`, so this SDK reports no
scope for it.

## Module clients

Module dependencies are replaced by generated module clients:

```sh
dagger module client add <module-ref>
```

In a module scope the client set becomes the module's dependency set. Each
client is recorded in the manifest the module has — `dagger-module.toml`, or the
`dagger.json` of a pre-1.0 module — and its types are part of the generated
bindings; a client that is removed is dropped from both.

Standalone clients — in a scope that has no Java module — are not generated yet.
Adding one is refused and the workspace is left unchanged.

> [!WARNING]
> The client set is the *whole* dependency set. A module that recorded
> dependencies before this interface existed has no clients recorded for them,
> so the first `dagger generate` drops them. Re-register each one first:
>
> ```sh
> dagger module client add <module-ref>
> ```

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

`dagger.toml` does not install the `e2e` module, so `dagger check` runs the e2e
checks only on the floor engine. AGENTS.md has the commands that run them on the
local engine.
