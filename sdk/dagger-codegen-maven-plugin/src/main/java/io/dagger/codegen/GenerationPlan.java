package io.dagger.codegen;

import io.dagger.codegen.introspection.ClientBinding;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * What one codegen run emits: a core package and any number of module client packages, each from
 * its own schema, all into one output tree in one Maven invocation.
 *
 * <p>On disk a plan is a directory with one subdirectory per entry, each holding {@code
 * schema.json} — the introspection JSON to generate from — and {@code meta.json}:
 *
 * <pre>
 * {"mode":"core"}
 * {"mode":"client","module":"hello",
 *  "binding":{"kind":"LOCAL_SOURCE","ref":"dagger/modules/hello","pin":""}}
 * </pre>
 *
 * Entries are processed in name order so the output is deterministic.
 */
public final class GenerationPlan {

  /** One package to generate. {@code module} and {@code binding} are null for core. */
  public record Entry(String name, Path schema, String mode, String module, ClientBinding binding) {

    public boolean isCore() {
      return "core".equals(mode);
    }
  }

  private GenerationPlan() {}

  /** A plan with a single core entry, for the schema-only invocation. */
  public static List<Entry> core(Path schema) {
    return List.of(new Entry("core", schema, "core", null, null));
  }

  public static List<Entry> read(Path dir) throws IOException {
    try (Stream<Path> children = Files.list(dir)) {
      return children.filter(Files::isDirectory).sorted().map(GenerationPlan::entry).toList();
    }
  }

  private static Entry entry(Path dir) {
    Path schema = dir.resolve("schema.json");
    Path meta = dir.resolve("meta.json");
    if (!Files.isRegularFile(schema) || !Files.isRegularFile(meta)) {
      throw new IllegalArgumentException(
          "plan entry " + dir.getFileName() + " needs both schema.json and meta.json");
    }
    JsonObject json;
    try (JsonReader reader = Json.createReader(Files.newBufferedReader(meta))) {
      json = reader.readObject();
    } catch (IOException e) {
      throw new IllegalArgumentException("plan entry " + dir.getFileName() + ": " + meta, e);
    }
    String mode = json.getString("mode", null);
    switch (mode == null ? "" : mode) {
      case "core" -> {
        return new Entry(dir.getFileName().toString(), schema, "core", null, null);
      }
      case "client" -> {
        String module = json.getString("module", null);
        JsonObject binding = json.getJsonObject("binding");
        if (module == null || binding == null) {
          throw new IllegalArgumentException(
              "plan entry " + dir.getFileName() + ": a client needs module and binding");
        }
        return new Entry(
            dir.getFileName().toString(),
            schema,
            "client",
            module,
            new ClientBinding(
                module,
                binding.getString("kind"),
                binding.getString("ref"),
                binding.getString("pin", "")));
      }
      default ->
          throw new IllegalArgumentException(
              "plan entry " + dir.getFileName() + ": mode must be core or client, not " + mode);
    }
  }
}
