# Java SDK runtime (build/package-only)

This is the Java module runtime, relocated from `dagger/dagger`'s
`sdk/java/runtime` with code generation removed. New Java modules reference it as
their runtime (`github.com/dagger/java-sdk/runtime`); the root Dang module
(`main.dang`) sets it via `targetRuntime`.

## What it does

It only **fetches dependencies, builds, and packages** a Java module from its
committed sources — it does not generate code.

`ModuleRuntime` mounts the module, runs `mvn clean package -DskipTests`, and
returns a JRE container that runs the resulting jar. New-style modules are
self-contained: the Java SDK is vendored as source under `<module>/sdk/` and the
entrypoint is committed under `<module>/src/generated/java`, so a plain
`mvn package` (the module pom defaults `dagger.proc=none`) compiles them together
and only downloads third-party dependencies.

## What owns code generation instead

Code generation lives in this repository's root Dang module (`main.dang` /
`mod.dang`) and runs at `dagger generate` time. New-style modules commit the
generated files, so the engine skips codegen at module load — this runtime never
regenerates them.

`Codegen` here is an intentional no-op (returns the module source unchanged): the
SDK runtime contract still includes it, but generation is owned by `generate`.
The committed `dagger.gen.go` must be regenerated (`dagger develop`) whenever the
runtime's own Go API changes.
