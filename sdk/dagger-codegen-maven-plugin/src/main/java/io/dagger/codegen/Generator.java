package io.dagger.codegen;

import io.dagger.codegen.introspection.ClientEntryPoint;
import io.dagger.codegen.introspection.CodegenVisitor;
import io.dagger.codegen.introspection.Field;
import io.dagger.codegen.introspection.Schema;
import io.dagger.codegen.introspection.SchemaPartition;
import io.dagger.codegen.introspection.TargetDescriptor;
import io.dagger.codegen.introspection.Type;
import io.dagger.codegen.introspection.TypeKind;
import io.dagger.codegen.introspection.TypeRef;
import io.dagger.codegen.introspection.TypeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Emits every package a {@link GenerationPlan} names, in one pass over one type registry. */
public final class Generator {

  public static final String CORE_PACKAGE = ModulePackage.ROOT + "." + ModulePackage.CORE_SEGMENT;

  public static final String RUNTIME_PACKAGE = "io.dagger.client";

  private final Path outputDirectory;
  private final Charset encoding;
  private final String engineVersion;

  public Generator(Path outputDirectory, Charset encoding, String engineVersion) {
    this.outputDirectory = outputDirectory;
    this.encoding = encoding;
    this.engineVersion = engineVersion;
  }

  public void generate(GenerationPlan plan) throws IOException {
    Map<String, String> packages =
        ModulePackage.packagesFor(
            plan.targets().stream().map(GenerationPlan.Target::module).toList());

    // Built before anything is written, because each target names core's types and the core
    // receivers it enters on: no package can be emitted until every type's home is known.
    Map<String, String> packageByTypeName = new LinkedHashMap<>();
    Map<String, String> targetByTypeName = new LinkedHashMap<>();
    Map<String, SchemaPartition> clients = new LinkedHashMap<>();
    for (GenerationPlan.Target target : plan.targets()) {
      SchemaPartition client =
          SchemaPartition.client(typeChecksAsCheck(read(target.schema())), target.module());
      clients.put(target.module(), client);
      for (String owned : client.typeNames()) {
        requireUnclaimed(targetByTypeName, owned, target.module());
        packageByTypeName.put(owned, packages.get(target.module()));
      }
    }

    Schema coreSchema = read(plan.coreSchema());
    requireEveryOwnedModulePlanned(coreSchema, packages.keySet());
    requireNoTargetShadowsCore(coreSchema, targetByTypeName);

    TypeRegistry registry =
        TypeRegistry.acrossPackages(CORE_PACKAGE, RUNTIME_PACKAGE, packageByTypeName);
    emit(SchemaPartition.core(coreSchema), registry, null, null);
    for (GenerationPlan.Target target : plan.targets()) {
      SchemaPartition client = clients.get(target.module());
      emit(
          client,
          registry.emittingInto(packages.get(target.module())),
          new ClientEntryPoint(client),
          target.descriptor());
    }
  }

  /**
   * A client runs in a v1 session, where every check returns the core object Check. A target's
   * schema read in a view before v1 keeps the type the check's author wrote, and a v1 session
   * refuses that query: a field of type Check needs a selection. So every check is typed {@code
   * Check!}, as a v1 view types it, whatever view the schema was read in.
   */
  private static Schema typeChecksAsCheck(Schema schema) {
    for (Type type : schema.getTypes()) {
      if (type.getKind() != TypeKind.OBJECT || type.getFields() == null) {
        continue;
      }
      for (Field field : type.getFields()) {
        if (field.isCheck()) {
          field.setTypeRef(nonNullCheck());
        }
      }
    }
    return schema;
  }

  private static TypeRef nonNullCheck() {
    TypeRef check = new TypeRef();
    check.setKind(TypeKind.OBJECT);
    check.setName("Check");
    TypeRef nonNull = new TypeRef();
    nonNull.setKind(TypeKind.NON_NULL);
    nonNull.setOfType(check);
    return nonNull;
  }

  /**
   * Type names are the keys the whole run resolves through, so two targets owning one name would
   * hand the second silent ownership of every reference to it. The engine namespaces a target's
   * local types under the target's name, which keeps most target sets apart but not all: a target
   * {@code foo} contributes {@code FooBar}, and so does a target named {@code foo-bar}. Their
   * package segments differ, so {@link ModulePackage} never sees it.
   */
  private static void requireUnclaimed(
      Map<String, String> targetByTypeName, String typeName, String module) {
    String previous = targetByTypeName.putIfAbsent(typeName, module);
    if (previous != null && !previous.equals(module)) {
      throw new IllegalArgumentException(
          String.format(
              "targets %s and %s both own the type %s; rename or alias one of them",
              previous, module, typeName));
    }
  }

  /** The same collision against a core type, which no target may take over. */
  private static void requireNoTargetShadowsCore(
      Schema coreSchema, Map<String, String> targetByTypeName) {
    for (String typeName : SchemaPartition.core(coreSchema).typeNames()) {
      String module = targetByTypeName.get(typeName);
      if (module != null) {
        throw new IllegalArgumentException(
            String.format(
                "target %s owns the type %s, which core also has; rename or alias the target",
                module, typeName));
      }
    }
  }

  /**
   * A core schema attributing a type or field to a module with no target would generate code
   * referring to a package this run does not write.
   */
  private static void requireEveryOwnedModulePlanned(Schema coreSchema, Set<String> planned) {
    List<String> unplanned =
        SchemaPartition.ownedModules(coreSchema).stream()
            .filter(m -> !planned.contains(m))
            .toList();
    if (!unplanned.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "the core schema attributes types or fields to %s, which the plan has no target for;"
                  + " every module the schema mentions has to be generated",
              unplanned));
    }
  }

  private void emit(
      SchemaPartition partition,
      TypeRegistry registry,
      ClientEntryPoint entryPoint,
      TargetDescriptor descriptor)
      throws IOException {
    Files.createDirectories(outputDirectory);
    partition.visit(
        new CodegenVisitor(
            partition.schema(), registry, entryPoint, descriptor, outputDirectory, encoding));
  }

  private Schema read(Path schemaFile) throws IOException {
    try (InputStream in = Files.newInputStream(schemaFile)) {
      return Schema.initialize(in, engineVersion);
    }
  }
}
