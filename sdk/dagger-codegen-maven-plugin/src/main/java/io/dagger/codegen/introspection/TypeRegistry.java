package io.dagger.codegen.introspection;

import static org.apache.commons.lang3.StringUtils.capitalize;

import com.palantir.javapoet.ClassName;
import java.util.Map;

/**
 * Where every Java class a generated package refers to lives, and what it is called.
 *
 * <p>Generated code names three kinds of class: a schema type, a hand-written runtime class ({@code
 * QueryBuilder}, {@code Arguments}, ...), and itself. Every visitor used to name them by simple
 * name, which is only correct while everything lands in one package. Routing them through a
 * registry is the seam a second package needs.
 *
 * <p>Core and the runtime are two packages, because only core is generated: the runtime is
 * hand-written and stays where it is however the generated packages are laid out.
 *
 * <p>A schema type's class is named after the type, or, when the schema's names are formatted by
 * the engine (see {@link FormattedNames}), by the name the engine formatted for it: {@code
 * JsonValue} for {@code JSONValue}. Strings sent to the API always use the GraphQL type name.
 */
public final class TypeRegistry {

  private final String targetPackage;
  private final String corePackage;
  private final String runtimePackage;
  private final Map<String, String> packageByTypeName;
  private final Map<String, String> javaTypeNames;

  private TypeRegistry(
      String targetPackage,
      String corePackage,
      String runtimePackage,
      Map<String, String> packageByTypeName,
      Map<String, String> javaTypeNames) {
    this.targetPackage = targetPackage;
    this.corePackage = corePackage;
    this.runtimePackage = runtimePackage;
    this.packageByTypeName = packageByTypeName;
    this.javaTypeNames = javaTypeNames;
  }

  public static TypeRegistry singlePackage(String pkg) {
    return new TypeRegistry(pkg, pkg, pkg, Map.of(), Map.of());
  }

  /**
   * Core in one package, the hand-written runtime in another, and every type a module owns in that
   * module's own package.
   *
   * <p>Built once for a whole plan, so a module's package can name a core type and core can name a
   * module's type without either knowing where the other landed.
   */
  public static TypeRegistry acrossPackages(
      String corePackage, String runtimePackage, Map<String, String> packageByTypeName) {
    return new TypeRegistry(
        corePackage, corePackage, runtimePackage, Map.copyOf(packageByTypeName), Map.of());
  }

  /** The same resolution, writing into a different package. */
  public TypeRegistry emittingInto(String pkg) {
    return new TypeRegistry(pkg, corePackage, runtimePackage, packageByTypeName, javaTypeNames);
  }

  /**
   * The same resolution, naming classes by the names the engine formatted for their types, keyed by
   * GraphQL type name (see {@link Schema#javaTypeNames}). A type without one keeps its name.
   */
  public TypeRegistry withJavaTypeNames(Map<String, String> javaTypeNames) {
    return new TypeRegistry(
        targetPackage, corePackage, runtimePackage, packageByTypeName, Map.copyOf(javaTypeNames));
  }

  /** Whether classes are named by engine-formatted names. */
  public boolean formatsTypeNames() {
    return !javaTypeNames.isEmpty();
  }

  public String targetPackage() {
    return targetPackage;
  }

  /**
   * The simple name of the Java class generated for a GraphQL type: {@code Core} for {@code Query},
   * the name the engine formatted when there is one, else the type's name capitalized.
   */
  public String javaTypeName(String graphqlName) {
    if ("Query".equals(graphqlName)) {
      return Helpers.formatName(graphqlName);
    }
    String formatted = javaTypeNames.get(graphqlName);
    if (formatted == null || formatted.isEmpty()) {
      return capitalize(graphqlName);
    }
    return formatted;
  }

  /**
   * The Java class generated for a GraphQL type. {@code Query} is {@code Core}, the builtin scalars
   * are their {@code java.lang} counterparts, and a type a module owns is in that module's package.
   */
  public ClassName forType(String graphqlName) {
    switch (graphqlName) {
      case "String":
        return ClassName.get(String.class);
      case "Boolean":
        return ClassName.get(Boolean.class);
      case "Int":
        return ClassName.get(Integer.class);
      case "Float":
        return ClassName.get(Float.class);
      default:
        return ClassName.get(
            packageByTypeName.getOrDefault(graphqlName, corePackage), javaTypeName(graphqlName));
    }
  }

  public ClassName forInterfaceClient(String graphqlName) {
    ClassName iface = forType(graphqlName);
    return iface.peerClass(iface.simpleName() + "Client");
  }

  public ClassName runtime(String simpleName) {
    return ClassName.get(runtimePackage, simpleName);
  }

  public ClassName runtime(String subpackage, String simpleName) {
    return ClassName.get(runtimePackage + "." + subpackage, simpleName);
  }
}
