package io.dagger.codegen.introspection;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

import com.palantir.javapoet.*;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.annotation.JsonbTypeSerializer;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.stream.JsonParser;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;
import javax.lang.model.element.Modifier;

class ObjectVisitor extends AbstractVisitor {

  private static final String TARGET = "TARGET";

  private static final String CORE_ENTRY = "core";

  private final ClientEntryPoint entryPoint;
  private final TargetDescriptor descriptor;

  public ObjectVisitor(
      Schema schema, TypeRegistry registry, Path targetDirectory, Charset encoding) {
    this(schema, registry, null, null, targetDirectory, encoding);
  }

  public ObjectVisitor(
      Schema schema,
      TypeRegistry registry,
      ClientEntryPoint entryPoint,
      TargetDescriptor descriptor,
      Path targetDirectory,
      Charset encoding) {
    super(schema, registry, targetDirectory, encoding);
    this.entryPoint = entryPoint;
    this.descriptor = descriptor;
  }

  /**
   * A field a module contributes to a core type, emitted as a static method on the module's root
   * type because the core class it belongs to is in another package and Java has no partial
   * classes.
   *
   * @param receiverType the core type the field was reached through, or null on {@code Query},
   *     whose receiver is the session
   */
  private record Entry(ClassName receiverType, String receiverName) {

    static final Entry ON_QUERY = new Entry(null, "session");

    boolean onQuery() {
      return receiverType == null;
    }

    /** Every entry lands in one class, so two fields of the same name need telling apart. */
    String argumentsPrefix() {
      return onQuery() ? "" : receiverType.simpleName();
    }

    /** The chain the entry selects from: the receiver's, carrying the target to serve. */
    String chainFrom() {
      return receiverName + ".queryBuilder().withTarget(" + TARGET + ")";
    }
  }

  @Override
  TypeSpec generateType(Type type) {
    ClassName thisType = registry().forType(type.getName());
    TypeSpec.Builder classBuilder =
        TypeSpec.classBuilder(Helpers.formatName(type))
            .addJavadoc(Helpers.escapeJavadoc(type.getDescription()))
            .addModifiers(Modifier.PUBLIC)
            .addField(
                FieldSpec.builder(
                        registry().runtime("QueryBuilder"), "queryBuilder", Modifier.PRIVATE)
                    .build());

    // Add implements for any interfaces this object implements
    for (String ifaceName : type.getImplementedInterfaceNames()) {
      classBuilder.addSuperinterface(registry().forType(ifaceName));
    }

    if ("Query".equals(type.getName())) {
      buildCoreEntryPoint(classBuilder, thisType);

      // loadObjectFromID: load any object by its ID using node(id:) + inline fragment
      classBuilder.addMethod(
          MethodSpec.methodBuilder("loadObjectFromID")
              .addModifiers(Modifier.PUBLIC)
              .addTypeVariable(TypeVariableName.get("T"))
              .returns(TypeVariableName.get("T"))
              .addParameter(
                  ParameterizedTypeName.get(ClassName.get(Class.class), TypeVariableName.get("T")),
                  "clazz")
              .addParameter(registry().forType("ID"), "id")
              .addJavadoc("Load any object by its ID using node(id:) with an inline fragment.\n")
              .beginControlFlow("try")
              .addStatement(
                  "$T qb = this.queryBuilder.chainNode(clazz.getSimpleName(), id)",
                  registry().runtime("QueryBuilder"))
              .addStatement(
                  "return clazz.getDeclaredConstructor($T.class).newInstance(qb)",
                  registry().runtime("QueryBuilder"))
              .nextControlFlow("catch (Exception e)")
              .addStatement("throw new RuntimeException(\"Failed to load object from ID\", e)")
              .endControlFlow()
              .build());

      // nodeQueryBuilder: create a QueryBuilder for node(id:) + inline fragment
      classBuilder.addMethod(
          MethodSpec.methodBuilder("nodeQueryBuilder")
              .addModifiers(Modifier.PUBLIC)
              .returns(registry().runtime("QueryBuilder"))
              .addParameter(ClassName.get(String.class), "typeName")
              .addParameter(registry().forType("ID"), "id")
              .addJavadoc(
                  "Create a QueryBuilder for node(id:) scoped to the given type via an inline fragment.\n")
              .addStatement("return this.queryBuilder.chainNode(typeName, id)")
              .build());
    } else {
      // Object constructor for JSON deserialization
      MethodSpec constructor =
          MethodSpec.constructorBuilder()
              .addModifiers(Modifier.PROTECTED)
              .addJavadoc("Empty constructor for JSON-B deserialization")
              .build();
      classBuilder.addMethod(constructor);

      // If Object has an "id" field, implement IDAble interface
      if (type.providesId()) {
        // With unified IDs, id() returns the ID scalar type
        classBuilder.addSuperinterface(
            ParameterizedTypeName.get(registry().runtime("IDAble"), registry().forType("ID")));
        classBuilder.addAnnotation(
            AnnotationSpec.builder(JsonbTypeSerializer.class)
                .addMember("value", "$T.class", registry().runtime("IDAbleSerializer"))
                .build());
        classBuilder.addAnnotation(
            AnnotationSpec.builder(JsonbTypeDeserializer.class)
                .addMember("value", "$T.class", thisType.nestedClass("Deserializer"))
                .build());
        classBuilder.addType(
            TypeSpec.classBuilder("Deserializer")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addSuperinterface(
                    ParameterizedTypeName.get(ClassName.get(JsonbDeserializer.class), thisType))
                .addMethod(
                    MethodSpec.methodBuilder("deserialize")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(thisType)
                        .addParameter(JsonParser.class, "parser")
                        .addParameter(DeserializationContext.class, "ctx")
                        .addParameter(java.lang.reflect.Type.class, "type")
                        .addStatement(
                            "$T id = ctx.deserialize($T.class, parser)", String.class, String.class)
                        .addStatement(
                            "$T o = new $T($T.dag().queryBuilder().chainNode($S, new $T(id)))",
                            thisType,
                            thisType,
                            registry().runtime("Dagger"),
                            type.getName(),
                            registry().forType("ID"))
                        .addStatement("return o")
                        .build())
                .build());
      }

      for (Field scalarField :
          type.getFields().stream().filter(f -> f.getTypeRef().isScalar()).toList()) {
        classBuilder.addField(
            scalarField.getTypeRef().formatOutput(registry()),
            Helpers.formatName(scalarField),
            Modifier.PRIVATE);
      }
    }

    // Object constructor for query building
    MethodSpec constructor =
        MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(registry().runtime("QueryBuilder"), "queryBuilder")
            .addCode("this.queryBuilder = queryBuilder;")
            .build();
    classBuilder.addMethod(constructor);

    // A client package chains from core objects it did not generate: the receiver of every field
    // its module contributes to a core type.
    classBuilder.addMethod(
        MethodSpec.methodBuilder("queryBuilder")
            .addModifiers(Modifier.PUBLIC)
            .returns(registry().runtime("QueryBuilder"))
            .addJavadoc("The query builder this object chains from.\n")
            .addStatement("return this.queryBuilder")
            .build());

    for (Field field : type.getFields()) {
      if (field.hasOptionalArgs()) {
        buildFieldArgumentsHelpers(classBuilder, field, type, null);
        buildFieldMethod(classBuilder, field, true, null);
      }

      buildFieldMethod(classBuilder, field, false, null);
    }

    if (entryPoint != null && entryPoint.rootTypeName().equals(type.getName())) {
      buildEntryPoints(classBuilder, type);
    }

    if (List.of("Container", "Directory").contains(type.getName())) {
      String argName = type.getName().toLowerCase() + "Func";
      classBuilder.addMethod(
          MethodSpec.methodBuilder("with")
              .addModifiers(Modifier.PUBLIC)
              .addParameter(
                  ParameterizedTypeName.get(ClassName.get(UnaryOperator.class), thisType), argName)
              .returns(thisType)
              .addStatement("return $L.apply(this)", argName)
              .build());
    }
    return classBuilder.build();
  }

  /**
   * The way into core. Core is what the session answers, so the entry wraps the session's builder:
   * it selects no field and serves no target.
   */
  private void buildCoreEntryPoint(TypeSpec.Builder classBuilder, ClassName core) {
    MethodSpec entry =
        MethodSpec.methodBuilder(CORE_ENTRY)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(core)
            .addParameter(
                ParameterSpec.builder(registry().runtime("Session"), "session")
                    .addJavadoc("the session to reach core in\n")
                    .build())
            .addJavadoc("The engine's core API.\n")
            .addStatement("return new $T(session.queryBuilder())", core)
            .build();
    classBuilder.addMethod(entry);
    classBuilder.addMethod(ambient(entry, "The engine's core API.\n"));
  }

  /**
   * The way into this client package: the module's {@code Query} field, and every field it
   * contributes to another core type.
   */
  private void buildEntryPoints(TypeSpec.Builder classBuilder, Type type) {
    classBuilder.addField(targetConstant());
    buildEntry(classBuilder, entryPoint.entryField(), type, Entry.ON_QUERY);
    entryPoint
        .shims()
        .forEach(
            (typeName, fields) -> {
              Entry shim = new Entry(registry().forType(typeName), uncapitalize(typeName));
              fields.forEach(field -> buildEntry(classBuilder, field, type, shim));
            });
  }

  /**
   * One entry, and on {@code Query} alone a second form over the session {@code Dagger.dag()}
   * holds. A field on any other core type reads the session off its receiver, so there is no
   * session left for a caller to choose.
   */
  private void buildEntry(TypeSpec.Builder classBuilder, Field field, Type type, Entry entry) {
    if (field.hasOptionalArgs()) {
      buildFieldArgumentsHelpers(classBuilder, field, type, entry);
      MethodSpec withOptArgs = buildFieldMethod(classBuilder, field, true, entry);
      if (entry.onQuery()) {
        classBuilder.addMethod(ambient(withOptArgs, Helpers.escapeJavadoc(field.getDescription())));
      }
    }
    MethodSpec method = buildFieldMethod(classBuilder, field, false, entry);
    if (entry.onQuery()) {
      classBuilder.addMethod(ambient(method, Helpers.escapeJavadoc(field.getDescription())));
    }
  }

  private FieldSpec targetConstant() {
    ClassName target = registry().runtime("ModuleTarget");
    CodeBlock initializer =
        descriptor.isGit()
            ? CodeBlock.of("$T.git($S, $S)", target, descriptor.address(), descriptor.pin())
            : CodeBlock.of("$T.local($S)", target, descriptor.address());
    return FieldSpec.builder(target, TARGET, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .initializer(initializer)
        .build();
  }

  /** The same entry over the ambient session, so a caller that never named one has a way in. */
  private MethodSpec ambient(MethodSpec entry, String javadoc) {
    List<ParameterSpec> withoutSession = entry.parameters().subList(1, entry.parameters().size());
    CodeBlock.Builder call =
        CodeBlock.builder().add("return $L($T.dag()", entry.name(), registry().runtime("Dagger"));
    withoutSession.forEach(parameter -> call.add(", $L", parameter.name()));
    call.add(")");
    return MethodSpec.methodBuilder(entry.name())
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addAnnotations(entry.annotations())
        .returns(entry.returnType())
        .addParameters(withoutSession)
        .addExceptions(entry.exceptions())
        .addJavadoc(javadoc)
        .addJavadoc("\n@see $T#dag()\n", registry().runtime("Dagger"))
        .addStatement(call.build())
        .build();
  }

  private TypeName resolveArgType(InputObject arg, Field field) {
    // For Query.node(id: ID!), keep as raw ID scalar type
    if ("Query".equals(field.getParentObject().getName()) && "id".equals(arg.getName())) {
      return arg.getType().formatOutput(registry());
    }
    String expectedType = arg.getExpectedType();
    return arg.getType().formatInput(registry(), expectedType);
  }

  private TypeName resolveReturnType(Field field) {
    if ("id".equals(field.getName())) {
      // id() field: with unified IDs, returns String
      return field.getTypeRef().formatOutput(registry());
    }
    if (Helpers.isIdToConvert(field)) {
      // ID handle: return the object the ID resolves to
      return registry().forType(Helpers.idHandleType(field));
    }
    String expectedType = field.getExpectedType();
    return field.getTypeRef().formatInput(registry(), expectedType);
  }

  private MethodSpec buildFieldMethod(
      TypeSpec.Builder classBuilder, Field field, boolean withOptionalArgs, Entry entry) {
    MethodSpec.Builder fieldMethodBuilder =
        MethodSpec.methodBuilder(Helpers.formatName(field)).addModifiers(Modifier.PUBLIC);
    String chainFrom = "this.queryBuilder";
    if (entry != null) {
      fieldMethodBuilder.addModifiers(Modifier.STATIC);
      chainFrom = entry.chainFrom();
      if (entry.onQuery()) {
        fieldMethodBuilder.addParameter(
            ParameterSpec.builder(registry().runtime("Session"), entry.receiverName())
                .addJavadoc("the session to reach the target in\n")
                .build());
      } else {
        fieldMethodBuilder.addParameter(
            ParameterSpec.builder(entry.receiverType(), entry.receiverName())
                .addJavadoc("the $L to chain from\n", entry.receiverType().simpleName())
                .build());
      }
    }
    TypeName returnType = resolveReturnType(field);
    TypeName objectReturnType = returnType;
    boolean nullableObject =
        getSchema().supportsNullableObjects()
            && field.getTypeRef().isOptional()
            && field.getTypeRef().isObjectOrInterface();
    // A non-null field still has to return Optional when an interface it shares an `implements`
    // relation with declares the field nullable, or the class does not satisfy its own `implements`
    // clause. It stays lazy: the value cannot be absent, so there is nothing to resolve.
    boolean presentObject = !nullableObject && requiresOptionalObjectField(field);
    if (nullableObject || presentObject) {
      returnType = ParameterizedTypeName.get(ClassName.get(Optional.class), returnType);
    }
    fieldMethodBuilder.returns(returnType);
    List<ParameterSpec> mandatoryParams =
        field.getRequiredArgs().stream()
            .map(
                arg ->
                    ParameterSpec.builder(resolveArgType(arg, field), Helpers.formatName(arg))
                        .addJavadoc(Helpers.escapeJavadoc(arg.getDescription()) + "\n")
                        .build())
            .toList();
    fieldMethodBuilder.addParameters(mandatoryParams);
    if (withOptionalArgs && field.hasOptionalArgs()) {
      fieldMethodBuilder.addParameter(
          ParameterSpec.builder(argumentsClass(field, entry), "optArgs")
              .addJavadoc("$L optional arguments\n", Helpers.formatName(field))
              .build());
    }
    fieldMethodBuilder.addJavadoc(Helpers.escapeJavadoc(field.getDescription()));

    if (entry == null
        && field.getTypeRef().isScalar()
        && !Helpers.isIdToConvert(field)
        && !"Query".equals(field.getParentObject().getName())) {
      fieldMethodBuilder.beginControlFlow("if (this.$L != null)", Helpers.formatName(field));
      fieldMethodBuilder.addStatement("return $L", Helpers.formatName(field));
      fieldMethodBuilder.endControlFlow();
    }
    if (field.hasArgs()) {
      fieldMethodBuilder.addStatement(
          "$1T.Builder builder = $1T.newBuilder()", registry().runtime("Arguments"));
    }
    field
        .getRequiredArgs()
        .forEach(
            arg ->
                fieldMethodBuilder.addStatement(
                    "builder.add($1S, $2L)", arg.getName(), Helpers.formatName(arg)));
    if (field.hasArgs()) {
      fieldMethodBuilder.addStatement(
          "$T fieldArgs = builder.build()", registry().runtime("Arguments"));
    }
    if (withOptionalArgs && field.hasOptionalArgs()) {
      fieldMethodBuilder.addStatement("fieldArgs = fieldArgs.merge(optArgs.toArguments())");
    }
    if (field.hasArgs()) {
      fieldMethodBuilder.addStatement(
          "$T nextQueryBuilder = $L.chain($S, fieldArgs)",
          registry().runtime("QueryBuilder"),
          chainFrom,
          field.getName());
    } else {
      fieldMethodBuilder.addStatement(
          "$T nextQueryBuilder = $L.chain($S)",
          registry().runtime("QueryBuilder"),
          chainFrom,
          field.getName());
    }

    if (field.getTypeRef().isListOfObject()) {
      String objName = field.getTypeRef().getListElementType().getName();
      // For interface list elements, use the client class
      ClassName clientClass =
          field.getTypeRef().getListElementType().isInterface()
              ? registry().forInterfaceClient(objName)
              : registry().forType(objName);
      fieldMethodBuilder.addStatement(
          "nextQueryBuilder = nextQueryBuilder.chain($T.of($S))", List.class, "id");
      fieldMethodBuilder.addStatement(
          "$T<$T> builders = nextQueryBuilder.executeObjectListQuery($S)",
          List.class,
          registry().runtime("QueryBuilder"),
          objName);
      fieldMethodBuilder.addStatement(
          "return builders.stream().map(qb -> new $T(qb)).toList()", clientClass);
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    } else if (field.getTypeRef().isList()) {
      fieldMethodBuilder.addStatement(
          "return nextQueryBuilder.executeListQuery($T.class)",
          field.getTypeRef().getListElementType().formatOutput(registry()));
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    } else if (Helpers.isIdToConvert(field)) {
      // Resolve the ID, then address the object it names through node(id:)
      fieldMethodBuilder.addStatement(
          "$T objectID = nextQueryBuilder.executeQuery($T.class)",
          registry().forType("ID"),
          registry().forType("ID"));
      // Interfaces are instantiated through their client class, like object returns
      String handleType = Helpers.idHandleType(field);
      ClassName handleClass =
          getSchema().isInterface(handleType)
              ? registry().forInterfaceClient(handleType)
              : registry().forType(handleType);
      fieldMethodBuilder.addStatement(
          "return new $T($L.chainNode($S, objectID))", handleClass, chainFrom, handleType);
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    } else if (nullableObject) {
      String graphqlTypeName = field.getTypeRef().getTypeName();
      TypeName clientClass =
          field.getTypeRef().isInterface()
              ? registry().forInterfaceClient(graphqlTypeName)
              : objectReturnType;
      fieldMethodBuilder.addStatement(
          "$T objectQueryBuilder = nextQueryBuilder.executeNullableObjectQuery($S)",
          registry().runtime("QueryBuilder"),
          graphqlTypeName);
      fieldMethodBuilder.addStatement(
          "return $T.ofNullable(objectQueryBuilder).map(qb -> new $T(qb))",
          Optional.class,
          clientClass);
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    } else if (field.getTypeRef().isObjectOrInterface()) {
      // For interface return types, instantiate the client class
      CodeBlock instantiation =
          field.getTypeRef().isInterface()
              ? CodeBlock.of(
                  "new $T(nextQueryBuilder)",
                  registry().forInterfaceClient(field.getTypeRef().getTypeName()))
              : CodeBlock.of("new $T(nextQueryBuilder)", objectReturnType);
      if (presentObject) {
        fieldMethodBuilder.addStatement("return $T.of($L)", Optional.class, instantiation);
      } else {
        fieldMethodBuilder.addStatement("return $L", instantiation);
      }
    } else {
      fieldMethodBuilder.addStatement("return nextQueryBuilder.executeQuery($T.class)", returnType);
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    }

    if (field.isDeprecated()) {
      fieldMethodBuilder.addAnnotation(Deprecated.class);
      fieldMethodBuilder.addJavadoc("@deprecated $L\n", field.getDeprecationReason());
    }

    MethodSpec method = fieldMethodBuilder.build();
    classBuilder.addMethod(method);
    return method;
  }

  /**
   * Builds the class containing the optional arguments.
   *
   * @param classBuilder
   * @param field
   * @param type
   */
  private void buildFieldArgumentsHelpers(
      TypeSpec.Builder classBuilder, Field field, Type type, Entry entry) {
    ClassName fieldArgumentsClassName = argumentsClass(field, entry);

    /* Inner class XXXArguments */
    TypeSpec.Builder fieldArgumentsClassBuilder =
        TypeSpec.classBuilder(fieldArgumentsClassName.simpleName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC);
    List<FieldSpec> optionalArgFields =
        field.getOptionalArgs().stream()
            .map(
                arg ->
                    FieldSpec.builder(
                            resolveArgType(arg, field), Helpers.formatName(arg), Modifier.PRIVATE)
                        .build())
            .toList();
    fieldArgumentsClassBuilder.addFields(optionalArgFields);

    List<MethodSpec> optionalArgFieldWithMethods =
        field.getOptionalArgs().stream()
            .map(
                arg ->
                    Helpers.withSetter(
                        arg,
                        resolveArgType(arg, field),
                        fieldArgumentsClassName,
                        arg.getDescription()))
            .toList();
    fieldArgumentsClassBuilder.addMethods(optionalArgFieldWithMethods);

    List<CodeBlock> blocks =
        field.getOptionalArgs().stream()
            .map(
                arg ->
                    CodeBlock.builder()
                        .beginControlFlow("if ($1L != null)", Helpers.formatName(arg))
                        .addStatement(
                            "builder.add($1S, this.$2L)", arg.getName(), Helpers.formatName(arg))
                        .endControlFlow()
                        .build())
            .toList();
    MethodSpec toArguments =
        MethodSpec.methodBuilder("toArguments")
            .returns(registry().runtime("Arguments"))
            .addStatement("$1T.Builder builder = $1T.newBuilder()", registry().runtime("Arguments"))
            .addCode(CodeBlock.join(blocks, "\n"))
            .addStatement("\nreturn builder.build()")
            .build();
    fieldArgumentsClassBuilder.addMethod(toArguments);
    fieldArgumentsClassBuilder.addJavadoc(
        "Optional arguments for {@link $L#$L}\n\n",
        registry().forType(type.getName()).simpleName(),
        Helpers.formatName(field));
    classBuilder.addType(fieldArgumentsClassBuilder.build());
  }

  /** The nested class holding a field's optional arguments, as the enclosing class names it. */
  private ClassName argumentsClass(Field field, Entry entry) {
    String prefix = entry == null ? "" : entry.argumentsPrefix();
    return ClassName.bestGuess(prefix + capitalize(Helpers.formatName(field)) + "Arguments");
  }
}
