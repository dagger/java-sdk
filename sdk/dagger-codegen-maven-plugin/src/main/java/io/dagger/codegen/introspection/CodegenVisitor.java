package io.dagger.codegen.introspection;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

public class CodegenVisitor implements SchemaVisitor {

  private final ScalarVisitor scalarVisitor;
  private final InputVisitor inputVisitor;
  private final EnumVisitor enumVisitor;
  private final ObjectVisitor objectVisitor;
  private final InterfaceVisitor interfaceVisitor;
  private final VersionVisitor versionVisitor;
  private final IDAbleVisitor idAbleVisitor;

  public CodegenVisitor(
      Schema schema, TypeRegistry registry, Path targetDirectory, Charset encoding) {
    this(schema, registry, null, null, targetDirectory, encoding);
  }

  /**
   * @param entryPoint how a caller enters the client package, or null when emitting core
   * @param descriptor where that client's target lives, or null when emitting core
   */
  public CodegenVisitor(
      Schema schema,
      TypeRegistry registry,
      ClientEntryPoint entryPoint,
      TargetDescriptor descriptor,
      Path targetDirectory,
      Charset encoding) {
    this.scalarVisitor = new ScalarVisitor(schema, registry, targetDirectory, encoding);
    this.inputVisitor = new InputVisitor(schema, registry, targetDirectory, encoding);
    this.enumVisitor = new EnumVisitor(schema, registry, targetDirectory, encoding);
    this.objectVisitor =
        new ObjectVisitor(schema, registry, entryPoint, descriptor, targetDirectory, encoding);
    this.interfaceVisitor = new InterfaceVisitor(schema, registry, targetDirectory, encoding);
    this.versionVisitor = new VersionVisitor(registry.targetPackage(), targetDirectory, encoding);
    this.idAbleVisitor = new IDAbleVisitor(schema, registry, targetDirectory, encoding);
  }

  @Override
  public void visitScalar(Type type) {
    try {
      scalarVisitor.visit(type);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void visitObject(Type type) {
    try {
      objectVisitor.visit(type);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void visitInterface(Type type) {
    try {
      interfaceVisitor.visit(type);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void visitInput(Type type) {
    try {
      inputVisitor.visit(type);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void visitEnum(Type type) {
    try {
      enumVisitor.visit(type);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void visitVersion(String version) {
    try {
      versionVisitor.visit(version);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void visitIDAbles(List<Type> types) {
    try {
      idAbleVisitor.visit(types);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
