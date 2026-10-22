package io.dagger.codegen.introspection;

import static org.apache.commons.lang3.StringUtils.capitalize;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;

public class Helpers {

  // Object's no-argument methods are inherited by every generated client. Even non-final
  // methods can conflict with a field's return type or checked exceptions.
  private static final List<String> JAVA_OBJECT_METHODS =
      List.of(
          "getClass", "hashCode", "toString", "clone", "finalize", "notify", "notifyAll", "wait");

  // Every generated object also carries this accessor, for client packages to chain from.
  private static final String QUERY_BUILDER_ACCESSOR = "queryBuilder";

  private static final List<String> JAVA_KEYWORDS =
      List.of(
          "abstract",
          "assert",
          "continue",
          "for",
          "new",
          "switch",
          "assert",
          "default",
          "goto",
          "package",
          "synchronized",
          "boolean",
          "do",
          "if",
          "private",
          "this",
          "break",
          "double",
          "implements",
          "protected",
          "throw",
          "byte",
          "else",
          "import",
          "public",
          "throws",
          "case",
          "enum",
          "instanceof",
          "return",
          "transient",
          "catch",
          "extends",
          "int",
          "short",
          "try",
          "char",
          "final",
          "interface",
          "static",
          "void",
          "class",
          "finally",
          "long",
          "strictfp",
          "volatile",
          "const",
          "float",
          "native",
          "super",
          "while");

  static ClassName convertScalarToObject(
      TypeRegistry registry, String typeName, String expectedType) {
    if (expectedType != null && !expectedType.isEmpty()) {
      return registry.forType(expectedType);
    }
    if (typeName.endsWith("ID") && typeName.length() > 2) {
      return registry.forType(typeName.substring(0, typeName.length() - 2));
    }
    return registry.forType(typeName);
  }

  /**
   * Returns true if the field returns an ID handle to an object: a unified ID carrying an
   * expectedType directive (sync(), spawn(), ...) or a legacy FooID scalar. The generated method
   * loads that object from the returned ID rather than exposing the ID itself.
   */
  static boolean isIdToConvert(Field field) {
    return idHandleType(field) != null;
  }

  /**
   * Returns the name of the object type an ID-handle field resolves to, or null when the field does
   * not return an ID handle (see {@link #isIdToConvert}).
   */
  static String idHandleType(Field field) {
    if ("id".equals(field.getName())) {
      return null;
    }
    if (!field.getTypeRef().isScalar()) {
      return null;
    }
    String typeName = field.getTypeRef().getTypeName();
    // Unified ID: the expectedType directive names the object
    if ("ID".equals(typeName)) {
      String expectedType = field.getExpectedType();
      return expectedType == null || expectedType.isEmpty() ? null : expectedType;
    }
    // Legacy: FooID scalar
    if (typeName != null && typeName.endsWith("ID") && typeName.length() > 2) {
      return typeName.substring(0, typeName.length() - 2);
    }
    return null;
  }

  static List<Field> getArrayField(Field field, Schema schema) {
    TypeRef fieldType = field.getTypeRef();
    if (!fieldType.isOptional()) {
      fieldType = fieldType.getOfType();
    }
    if (!fieldType.isList()) {
      throw new IllegalArgumentException("field is not a list");
    }
    fieldType = fieldType.getOfType();
    if (!fieldType.isOptional()) {
      fieldType = fieldType.getOfType();
    }
    final String typeName = fieldType.getName();
    Type schemaType =
        schema.getTypes().stream()
            .filter(t -> typeName.equals(t.getName()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Schema type %s not found", typeName)));
    return schemaType.getFields().stream().filter(f -> f.getTypeRef().isScalar()).toList();
  }

  static String formatName(Type type) {
    return formatName(type.getName());
  }

  static String formatName(String graphqlName) {
    if ("Query".equals(graphqlName)) {
      return "Core";
    } else {
      return capitalize(graphqlName);
    }
  }

  /**
   * Marks a generated class with the GraphQL type it stands for, when engine-formatted names named
   * it apart from the type: the runtime reads it wherever it sends a class's type name to the API.
   */
  static void addGraphQLType(TypeSpec.Builder builder, TypeRegistry registry, String typeName) {
    if (!registry.formatsTypeNames()
        || "Query".equals(typeName)
        || typeName.equals(registry.javaTypeName(typeName))) {
      return;
    }
    builder.addAnnotation(
        AnnotationSpec.builder(registry.runtime("GraphQLType"))
            .addMember("value", "$S", typeName)
            .build());
  }

  /**
   * The Java spelling of a field, argument or input field name: camelCase with acronyms written
   * like words ({@code asJson}, {@code withGpu}) as the engine formatted it (see {@link
   * FormattedNames}), else the schema name as is. Strings sent to the API always use the schema
   * name.
   */
  static String javaName(String name, String formattedName) {
    if (formattedName == null || formattedName.isEmpty()) {
      return name;
    }
    return formattedName;
  }

  /** The Java method name of a field. */
  static String formatName(Field field) {
    return escapeMethodName(field, javaName(field.getName(), field.getFormattedName()));
  }

  /**
   * The Java method name of a field before engine-formatted names: the schema name, escaped. When
   * it differs from {@link #formatName(Field)}, it's kept as a deprecated alias.
   */
  static String legacyName(Field field) {
    return escapeMethodName(field, field.getName());
  }

  private static String escapeMethodName(Field field, String name) {
    if ("Container".equals(field.getParentObject().getName()) && "import".equals(field.getName())) {
      return "importTarball";
    } else if (JAVA_KEYWORDS.contains(name)
        || ((JAVA_OBJECT_METHODS.contains(name) || QUERY_BUILDER_ACCESSOR.equals(name))
            && field.getRequiredArgs().isEmpty())) {
      return name + "_";
    } else {
      return name;
    }
  }

  /**
   * The class holding a field's optional arguments. It keeps the name derived from the schema name:
   * like the arguments classes of entry points, it is named apart from formatted names, so code
   * naming it keeps compiling.
   */
  static String argumentsClassName(Field field) {
    return capitalize(legacyName(field)) + "Arguments";
  }

  /** The Java variable name of an argument or input field. */
  static String formatName(InputObject arg) {
    return escapeVariableName(javaName(arg.getName(), arg.getFormattedName()));
  }

  private static String escapeVariableName(String name) {
    if (JAVA_KEYWORDS.contains(name)) {
      return "_" + name;
    } else {
      return name;
    }
  }

  /** The name of the "with" setter of an argument or input field. */
  static String withSetterName(InputObject var) {
    return "with" + capitalize(javaName(var.getName(), var.getFormattedName()));
  }

  /** The name of the "with" setter of an argument or input field before formatted names. */
  static String legacyWithSetterName(InputObject var) {
    return "with" + capitalize(var.getName());
  }

  /**
   * A deprecated method under a name the generator used before formatted names, forwarding to the
   * method that replaces it.
   */
  static MethodSpec deprecatedAlias(MethodSpec target, String legacyName, Modifier... modifiers) {
    MethodSpec.Builder builder =
        MethodSpec.methodBuilder(legacyName)
            .addModifiers(Modifier.PUBLIC)
            .addModifiers(modifiers)
            .addTypeVariables(target.typeVariables())
            .returns(target.returnType())
            .addParameters(target.parameters())
            .addExceptions(target.exceptions())
            .addAnnotation(Deprecated.class)
            .addJavadoc("@deprecated Use {@link #$L} instead.\n", target.name());
    String args =
        target.parameters().stream().map(ParameterSpec::name).collect(Collectors.joining(", "));
    if (TypeName.VOID.equals(target.returnType())) {
      builder.addStatement("$L($L)", target.name(), args);
    } else {
      builder.addStatement("return $L($L)", target.name(), args);
    }
    return builder.build();
  }

  static MethodSpec getter(String var, TypeName type) {
    String prefix =
        (TypeName.BOOLEAN.equals(type) || ClassName.get(Boolean.class).equals(type)) ? "is" : "get";
    return MethodSpec.methodBuilder(prefix + capitalize(var))
        .addModifiers(Modifier.PUBLIC)
        .returns(type)
        .addStatement("return this.$L", var)
        .build();
  }

  static MethodSpec setter(String var, TypeName type) {
    return MethodSpec.methodBuilder("set" + capitalize(var))
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ParameterSpec.builder(type, var).build())
        .addStatement("this.$1L = $1L", var)
        .build();
  }

  static MethodSpec withSetter(InputObject var, TypeName type, TypeName returnType) {
    return withSetter(var, type, returnType, null);
  }

  static MethodSpec withSetter(InputObject var, TypeName type, TypeName returnType, String doc) {
    MethodSpec.Builder builder =
        MethodSpec.methodBuilder(withSetterName(var))
            .addModifiers(Modifier.PUBLIC)
            .addParameter(type, Helpers.formatName(var))
            .returns(returnType)
            .addStatement("this.$1L = $1L", Helpers.formatName(var))
            .addStatement("return this");
    if (doc != null) {
      builder.addJavadoc(Helpers.escapeJavadoc(doc) + "\n");
    }
    return builder.build();
  }

  /**
   * Escape characters that have a special meaning in javadoc.
   *
   * <p>'$' is escaped for JavaPoet's format strings and '&amp;' as an HTML entity. The comment
   * terminator is escaped so that a glob example such as {@code **&#47;target/**} cannot end the
   * generated javadoc early.
   */
  static String escapeJavadoc(String str) {
    if (str == null) {
      return "";
    }
    return str.replace("$", "$$").replace("&", "&amp;").replace("*/", "*&#47;");
  }
}
