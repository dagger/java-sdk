package io.dagger.codegen.introspection;

/**
 * Where the module a client binds to lives, as baked into the client's serve preamble.
 *
 * @param module the module's final name, after any dependency alias
 * @param kind the engine's source kind: {@code GIT_SOURCE} or {@code LOCAL_SOURCE}
 * @param ref the canonical git ref, or the workspace-root-absolute path of a local module — the
 *     leading "/" is what makes it resolve from the workspace root rather than from the client's
 *     cwd
 * @param pin the resolved commit of a git module; empty for a local one
 */
public record ClientBinding(String module, String kind, String ref, String pin) {

  public ClientBinding {
    if (module == null || module.isBlank()) {
      throw new IllegalArgumentException("a client binding needs a module name");
    }
    if (pin == null) {
      pin = "";
    }
  }

  /** The same binding under another final name, as a dependency alias renames a module. */
  public ClientBinding withModule(String module) {
    return new ClientBinding(module, kind, ref, pin);
  }
}
