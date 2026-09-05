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
> This SDK implements the module-scope interface from
> [dagger/dagger#13992](https://github.com/dagger/dagger/pull/13992) and needs an
> engine that has it. On the released engine (`v1.0.0-beta.11`) the module loads
> but every call into it fails.

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
module), `empty` (a bare object class), or `legacy`.

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
dagger module client add java <module-ref>
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
> dagger module client add java <module-ref>
> ```
>
> Then check that each one landed in `dagger.toml` before you generate. On the
> `sdk-ux-module-max` engine builds this SDK currently needs,
> `dagger module client add` reports success and writes nothing.

## Pre-1.0 modules

A module configured by `dagger.json` rather than `dagger-module.toml` keeps the
runtime it already names and keeps being generated and run by it, exactly as
before. This SDK writes `dagger-module.toml` only for modules it creates.

## Skipping generation

A `.dagger-java-sdk-skip-generate` file at or above an existing module root makes
generation leave that module as it is. Useful for fixtures, vendored modules, or
anything you don't want regenerated. A module that is being created is always
generated, marker or not.

```sh
touch some/fixture/.dagger-java-sdk-skip-generate
```

## Test

```sh
dagger check
```

Checks run against two engines:

- On the released engine, everything that does not call this module: the SDK
  library's unit tests (`packager:unit-tests`), the prebuilt assets, and the
  templates.
- On an engine built from dagger/dagger#13992, the SDK interface itself.
  `engine-e-2-e:dev-sdk-check` builds that engine from the commit pinned in
  `.dagger/modules/engine-e2e` (the `engine-dev` dependency and `engineCommit`),
  installs this checkout as the `java` SDK, initializes a Java module, and calls
  it. `engine-e-2-e:sdk-contract-check` runs the `e-2-e:*` checks inside the same
  engine. Bump both pins to follow the branch.

The `e-2-e:*` checks are listed in `[modules.e2e] check.skip` because they call
this module: on the released engine they fail with `"moduleManifest" not found`.
