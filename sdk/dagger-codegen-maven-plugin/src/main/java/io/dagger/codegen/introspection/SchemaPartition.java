package io.dagger.codegen.introspection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One package's worth of a schema: the types and fields a single generated package emits.
 *
 * <p>The engine marks every module-contributed type and field with {@code @sourceMap(module:)};
 * core carries no mark. That is the whole partition:
 *
 * <ul>
 *   <li>{@link #core}: every unowned type, with owned fields removed. Because everything a module
 *       contributed is stripped, the result does not depend on which module's schema it came from.
 *   <li>{@link #client}: every type owned by one module, plus that module's fields on core types —
 *       its {@link #extensions() extensions} of core ({@code Query.hello}, {@code
 *       Binding.asHello}), which have no home of their own in Java and are emitted as shims on the
 *       module's entry point.
 * </ul>
 *
 * The full schema stays available through {@link #schema()} for lookups; only what is emitted is
 * narrowed.
 */
public final class SchemaPartition {

  private final Schema schema;
  private final String module;
  private final List<Type> types;
  private final Map<String, List<Field>> extensions;

  private SchemaPartition(
      Schema schema, String module, List<Type> types, Map<String, List<Field>> extensions) {
    this.schema = schema;
    this.module = module;
    this.types = types;
    this.extensions = extensions;
  }

  /** The unowned part of a schema, with owned fields stripped from core types. */
  public static SchemaPartition core(Schema schema) {
    List<Type> types =
        emittable(schema)
            .filter(type -> type.getOwningModule() == null)
            .map(type -> type.withFields(unownedFields(type)))
            .toList();
    return new SchemaPartition(schema, null, types, Map.of());
  }

  /**
   * The part of a schema owned by {@code module}. Fails when the schema contains nothing owned by
   * that module: generating an empty client is always a misconfiguration, never a result.
   */
  public static SchemaPartition client(Schema schema, String module) {
    Objects.requireNonNull(module, "module");
    List<Type> types =
        emittable(schema).filter(type -> module.equals(type.getOwningModule())).toList();
    Map<String, List<Field>> extensions = new LinkedHashMap<>();
    emittable(schema)
        .filter(type -> type.getOwningModule() == null)
        .forEach(
            type -> {
              List<Field> owned = ownedFields(type, module);
              if (!owned.isEmpty()) {
                extensions.put(type.getName(), owned);
              }
            });
    if (types.isEmpty() && extensions.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "schema contains nothing owned by module %s (owned modules: %s)",
              module, ownedModules(schema)));
    }
    // Not Map.copyOf: the shims are emitted in this order, and an unordered copy would reshuffle
    // them from one run to the next.
    return new SchemaPartition(schema, module, types, Collections.unmodifiableMap(extensions));
  }

  /** The schema this partition was cut from, whole, for type lookups. */
  public Schema schema() {
    return schema;
  }

  /** The module this partition emits for, or null for core. */
  public String module() {
    return module;
  }

  /** The types this partition emits, narrowed to the fields it owns, in schema order. */
  public List<Type> types() {
    return types;
  }

  /** Core types carrying fields owned by this partition's module, by type name. Empty for core. */
  public Map<String, List<Field>> extensions() {
    return extensions;
  }

  /** Every type name owned by any module anywhere in the schema. */
  public Set<String> ownedTypeNames() {
    return emittable(schema)
        .filter(type -> type.getOwningModule() != null)
        .map(Type::getName)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Walk what this partition emits, in the order the generator needs. The non-schema emissions
   * ({@code Version}, the IDAble helpers) belong to core alone: emitted into every client package
   * they would collide with core's.
   */
  public void visit(SchemaVisitor visitor) {
    types.stream()
        .filter(t -> t.getKind() == TypeKind.SCALAR)
        .filter(t -> !Schema.BUILTIN_SCALARS.contains(t.getName()))
        .forEach(visitor::visitScalar);
    types.stream().filter(t -> t.getKind() == TypeKind.INPUT_OBJECT).forEach(visitor::visitInput);
    types.stream().filter(t -> t.getKind() == TypeKind.INTERFACE).forEach(visitor::visitInterface);
    types.stream().filter(t -> t.getKind() == TypeKind.OBJECT).forEach(visitor::visitObject);
    types.stream().filter(t -> t.getKind() == TypeKind.ENUM).forEach(visitor::visitEnum);
    if (module == null) {
      visitor.visitVersion(schema.getVersion());
      visitor.visitIDAbles(
          types.stream().filter(t -> t.getKind() == TypeKind.OBJECT && t.providesId()).toList());
    }
  }

  private static java.util.stream.Stream<Type> emittable(Schema schema) {
    return schema.getTypes().stream().filter(t -> !t.getName().startsWith("_"));
  }

  private static List<Field> unownedFields(Type type) {
    if (type.getFields() == null) {
      return null;
    }
    return type.getFields().stream().filter(f -> f.getOwningModule() == null).toList();
  }

  private static List<Field> ownedFields(Type type, String module) {
    if (type.getFields() == null) {
      return List.of();
    }
    return type.getFields().stream().filter(f -> module.equals(f.getOwningModule())).toList();
  }

  private static List<String> ownedModules(Schema schema) {
    List<String> modules = new ArrayList<>();
    emittable(schema)
        .map(Type::getOwningModule)
        .filter(Objects::nonNull)
        .distinct()
        .forEach(modules::add);
    return modules;
  }
}
