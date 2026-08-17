package io.dagger.codegen.introspection;

import com.palantir.javapoet.TypeSpec;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

abstract class AbstractVisitor extends CodeWriter {

  private Schema schema;

  public AbstractVisitor(Schema schema, Path targetDirectory, Charset encoding) {
    super(targetDirectory, encoding);
    this.schema = schema;
  }

  void visit(Type type) throws IOException {
    TypeSpec typeSpec = generateType(type);
    write(typeSpec);
  }

  public Schema getSchema() {
    return schema;
  }

  /**
   * Whether a non-null object field has to be generated as {@code Optional} anyway, because a type
   * it shares an {@code implements} relation with declares the same field nullable.
   *
   * <p>GraphQL lets an implementation narrow a nullable field to non-null, and lets an object
   * implement several interfaces that disagree on that. Java has one return type per method: an
   * implementation must satisfy every interface it implements, so {@code Optional} is
   * all-or-nothing across a whole {@code implements} component — otherwise the generated class
   * cannot satisfy its own {@code implements} clause.
   */
  boolean requiresOptionalObjectField(Field field) {
    if (!getSchema().supportsNullableObjects() || !field.getTypeRef().isObjectOrInterface()) {
      return false;
    }
    return implementsComponent(field.getParentObject()).stream()
        .filter(related -> related.getFields() != null)
        .flatMap(related -> related.getFields().stream())
        .anyMatch(
            declared ->
                declared.getName().equals(field.getName())
                    && declared.getTypeRef().isOptional()
                    && declared.getTypeRef().isObjectOrInterface());
  }

  /** Every type reachable from this one through {@code implements}, in either direction. */
  private Set<Type> implementsComponent(Type type) {
    Set<Type> component = new LinkedHashSet<>();
    Deque<Type> pending = new ArrayDeque<>(List.of(type));
    while (!pending.isEmpty()) {
      Type current = pending.poll();
      if (!component.add(current)) {
        continue;
      }
      Stream.concat(
              current.getImplementedInterfaceNames().stream().map(this::typeNamed),
              getSchema().getTypes().stream()
                  .filter(
                      other -> other.getImplementedInterfaceNames().contains(current.getName())))
          .filter(Objects::nonNull)
          .forEach(pending::add);
    }
    return component;
  }

  private Type typeNamed(String name) {
    return getSchema().getTypes().stream()
        .filter(type -> name.equals(type.getName()))
        .findFirst()
        .orElse(null);
  }

  abstract TypeSpec generateType(Type type);
}
