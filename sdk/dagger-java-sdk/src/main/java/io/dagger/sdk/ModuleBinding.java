package io.dagger.sdk;

import io.dagger.sdk.exception.DaggerQueryException;
import io.dagger.sdk.graphql.GraphQLClient;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * The serve preamble of a generated module client.
 *
 * <p>A generated client is bound to one module. Before its bindings can resolve, that module has to
 * be served into the session, and {@link #ensureServed} does exactly that. The engine keys served
 * modules by name and deduplicates a serve of the same source and pin, and rejects one whose source
 * differs, so the first serve is both idempotent and the only way to learn about a conflict. Inside
 * a module the engine has already served every dependency, and the module itself, so it costs one
 * round trip and changes nothing; in a standalone client it is the bootstrap.
 *
 * <p>An exact binding tuple that has been served successfully is remembered per session, so the
 * second and later calls on the same client cost nothing. That is safe where a schema probe was
 * not: a conflicting module of the same name would already have failed the first call, so the cache
 * can only ever skip a serve the engine would have deduplicated.
 *
 * <p>The generated code carries data only: the module's final name (after any dependency alias),
 * where its source lives, and how to reach it. A git module serves from its canonical ref and pin,
 * which resolve from anywhere. A local module serves by its workspace-root-absolute path (leading
 * "/") through {@code currentWorkspace}, so it resolves from the workspace root whatever the cwd
 * is, and nowhere outside that workspace.
 */
public final class ModuleBinding {

  // Weak in the session: a client that has been closed must not pin its served set.
  private static final Map<GraphQLClient, Set<String>> SERVED =
      Collections.synchronizedMap(new WeakHashMap<>());

  private ModuleBinding() {}

  /**
   * Serve the bound module into the session this query builder is attached to.
   *
   * @param root the query builder at the root of the client
   * @param name the module's final name, which namespaces its types in the schema
   * @param kind the module source kind as the engine reports it: {@code GIT_SOURCE} or {@code
   *     LOCAL_SOURCE}
   * @param ref the canonical git ref for a git module, the workspace-root-absolute path (leading
   *     "/") for a local one
   * @param pin the resolved commit for a git module; ignored for a local one
   */
  public static void ensureServed(
      QueryBuilder root, String name, String kind, String ref, String pin)
      throws ExecutionException, InterruptedException, DaggerQueryException {
    Set<String> served =
        SERVED.computeIfAbsent(root.client(), client -> ConcurrentHashMap.newKeySet());
    String binding = String.join("\u0000", name, kind, ref, pin == null ? "" : pin);
    if (served.contains(binding)) {
      return;
    }
    QueryBuilder source;
    switch (kind) {
      case "GIT_SOURCE", "GIT" -> {
        Arguments.Builder args = Arguments.newBuilder().add("refString", ref);
        if (pin != null && !pin.isEmpty()) {
          args.add("refPin", pin);
        }
        source = root.chain("moduleSource", args.build());
      }
      case "LOCAL_SOURCE", "LOCAL" ->
          source =
              root.chain("currentWorkspace")
                  .chain("moduleSource", Arguments.newBuilder().add("path", ref).build());
      default ->
          throw new IllegalArgumentException(
              "module " + name + " has source kind " + kind + ", which a client cannot serve");
    }
    source
        .chain("withName", Arguments.newBuilder().add("name", name).build())
        .chain("asModule")
        .chain("serve")
        .executeQuery();
    served.add(binding);
  }
}
