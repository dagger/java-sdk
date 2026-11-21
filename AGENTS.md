# AGENTS.md

## Run the checks locally

The checks need an engine at this SDK's floor, v1.0.0-beta.26, or later. The
CLI runs on the engine it ships with; `--engine` or `DAGGER_ENGINE` selects
another (`dagger help engine`):

```sh
export DAGGER_ENGINE=container://<running-engine>
```

Run the e2e checks from the root of this checkout, by name or all of them:

```sh
dagger check -m .dagger/modules/e2e -l
dagger check -m .dagger/modules/e2e find-client-root-check generate-scope-init-check
dagger check -m .dagger/modules/e2e 'e2e:*'
```

A check of a namespace is named with its path, such as
`collections:collection-check`. A name that matches no check passes, so
confirm a pattern with `-l` first.

Run the SDK library's unit tests and the staleness checks of the committed
assets:

```sh
dagger check packager:unit-tests
dagger check packager:generate:stale templates:generate:stale
```

The root `dagger.toml` installs the e2e module, so `dagger check` without a
pattern runs all of these checks.

Judge a check by `dagger check`, never by `dagger call <check>`: on
v1.0.0-beta.18, `dagger call` exits 0 when the check fails.
