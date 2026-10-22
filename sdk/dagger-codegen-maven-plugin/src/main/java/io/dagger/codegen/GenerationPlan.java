package io.dagger.codegen;

import io.dagger.codegen.introspection.TargetDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * What one run of the generator emits: the core API, and one client package per target.
 *
 * <p>A plan is a directory the caller assembles:
 *
 * <pre>
 *   &lt;plan&gt;/core/schema.json         the schema core is generated from
 *   &lt;plan&gt;/target-*&#47;schema.json     one target's client-facing schema
 *   &lt;plan&gt;/target-*&#47;module          that target's module name, one line
 *   &lt;plan&gt;/target-*&#47;descriptor      where that target lives, as {@link TargetDescriptor#read}
 * </pre>
 *
 * <p>The directory names carry no meaning beyond ordering the targets; a module name can hold
 * characters a path cannot, so it travels in a file. Targets are read in module-name order, so a
 * plan generates the same bytes however its caller happened to lay it out.
 */
public record GenerationPlan(Path coreSchema, List<Target> targets) {

  public record Target(String module, Path schema, TargetDescriptor descriptor) {}

  private static final String CORE = "core";
  private static final String SCHEMA = "schema.json";
  private static final String MODULE = "module";
  private static final String DESCRIPTOR = "descriptor";

  public static GenerationPlan read(Path planDirectory) throws IOException {
    Path coreSchema = planDirectory.resolve(CORE).resolve(SCHEMA);
    if (!Files.isRegularFile(coreSchema)) {
      throw new IOException("generation plan " + planDirectory + " has no " + CORE + "/" + SCHEMA);
    }
    List<Target> targets = new ArrayList<>();
    try (Stream<Path> entries = Files.list(planDirectory)) {
      for (Path entry : entries.sorted().toList()) {
        if (!Files.isDirectory(entry) || entry.getFileName().toString().equals(CORE)) {
          continue;
        }
        Path module = entry.resolve(MODULE);
        Path schema = entry.resolve(SCHEMA);
        Path descriptor = entry.resolve(DESCRIPTOR);
        if (!Files.isRegularFile(module)
            || !Files.isRegularFile(schema)
            || !Files.isRegularFile(descriptor)) {
          throw new IOException(
              String.format(
                  "generation plan entry %s needs a %s, a %s and a %s",
                  entry, MODULE, SCHEMA, DESCRIPTOR));
        }
        targets.add(
            new Target(
                Files.readString(module, StandardCharsets.UTF_8).trim(),
                schema,
                TargetDescriptor.read(descriptor)));
      }
    }
    targets.sort(Comparator.comparing(Target::module));
    return new GenerationPlan(coreSchema, List.copyOf(targets));
  }
}
