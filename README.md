# Dagger Java SDK

The user experience for authoring Dagger modules in Java.

Modules created with this tool use a **self-contained** layout: the Dagger Java
SDK is vendored into the module as real, buildable source, all generated files
are committed to version control, and **no code generation runs at module load
time** — the runtime just builds and packages the module.

> This repository owns both halves of the Java SDK. The root Dang module
> (`main.dang` / `mod.dang`) owns code generation and scaffolding, and runs at
> `dagger generate` time. The module *runtime* (the SDK contract: building and
> packaging Java modules) is the build/package-only Go module under `runtime/`;
> new modules reference it as `github.com/dagger/java-sdk/runtime`.

## Install

```sh
dagger workspace install github.com/dagger/java-sdk
```

## Create a module

```sh
dagger call java-sdk init --name=my-module
dagger generate
```

`init` scaffolds a new module:

```
<module>/
  dagger.json                 # sdk.source=java; codegen.automaticGitignore=false
  pom.xml                     # two-pass build; dagger.proc defaults to "none"
  src/main/java/io/dagger/modules/<pkg>/<Module>.java
  src/main/java/io/dagger/modules/<pkg>/package-info.java
```

`dagger generate` (or `dagger call java-sdk generate --path=<module>`) fills in
the generated, committed sources:

```
  src/generated/java/io/dagger/gen/entrypoint/Entrypoint.java   # generated entrypoint
  sdk/src/main/java/...                                         # vendored SDK library
  sdk/src/processor/java/...                                    # vendored annotation processor
  sdk/src/generated/java/...                                    # client bindings (from the engine schema)
```

Because everything is committed and the pom defaults `dagger.proc=none`, the
module builds with a plain `mvn package` (no annotation processor at build time)
— in an IDE or CI, without Dagger.

## How generation works

`generate` runs Maven in containers it controls: it builds the vendored codegen
plugin, generates the client bindings from the engine's introspection schema,
vendors the SDK library and annotation processor as source, and runs the
processor once to produce the entrypoint. It does not delegate code generation
back to the engine.

## The codegen flag

`init` sets, in the module's `dagger.json`:

- `codegen.automaticGitignore: false`

This single flag carries two meanings: the generated files are committed (not
git-ignored), and — because the committed files can be trusted — the builtin
runtime skips codegen at module load and only builds/packages the committed
sources.
