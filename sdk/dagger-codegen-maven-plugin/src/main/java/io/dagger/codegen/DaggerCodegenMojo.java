package io.dagger.codegen;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@Mojo(
    name = "codegen",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true)
public class DaggerCodegenMojo extends AbstractMojo {

  /** specify output file encoding; defaults to source encoding */
  @Parameter(property = "project.build.sourceEncoding")
  protected String outputEncoding;

  /** The current Maven project. */
  @Parameter(property = "project", required = true, readonly = true)
  protected MavenProject project;

  @Parameter(property = "dagger.bin")
  protected String bin;

  @Parameter(property = "dagger.version", required = true)
  protected String version;

  @Parameter(property = "dagger.introspectionJson")
  protected String introspectionJson;

  /** Specify output directory where the Java files are generated. */
  @Parameter(defaultValue = "${project.build.directory}/generated-sources/dagger")
  private File outputDirectory;

  /** A generation plan directory (see {@link GenerationPlan}); overrides the single schema. */
  @Parameter(property = "dagger.plan")
  protected String plan;

  /**
   * A module whose already generated client package a full plan leaves in place (see {@link
   * Generator#generate}).
   */
  @Parameter(property = "dagger.keep")
  protected String keep;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    outputEncoding = validateEncoding(outputEncoding);

    File outputDir = getOutputDirectory();
    if (!outputDir.exists()) {
      outputDir.mkdirs();
    }
    Path dest = outputDir.toPath();

    try {
      List<GenerationPlan.Entry> entries;
      if (plan != null && !plan.isBlank() && Files.isDirectory(Path.of(plan))) {
        entries = GenerationPlan.read(Path.of(plan));
      } else {
        entries = GenerationPlan.core(schemaFile());
      }
      Generator.generate(
          entries, version, dest, Charset.forName(outputEncoding), keep, getLog()::info);
    } catch (IOException | InterruptedException e) {
      throw new MojoFailureException(e);
    }

    if (project != null) {
      // Tell Maven that there are some new source files underneath the output directory.
      project.addCompileSourceRoot(getOutputDirectory().getPath());
    }
  }

  /** The schema to generate core from: the configured file, else the local CLI's own. */
  private Path schemaFile() throws IOException, MojoFailureException, InterruptedException {
    if (this.introspectionJson != null && !this.introspectionJson.isEmpty()) {
      File f = new File(this.introspectionJson);
      if (f.exists()) {
        return f.toPath();
      }
    }
    this.bin = DaggerCLIUtils.getBinary(this.bin);
    String actualVersion = DaggerCLIUtils.getVersion(this.bin);
    getLog()
        .info(String.format("Querying local dagger CLI for schema (version=%s)", actualVersion));
    this.version = actualVersion;
    Path schema = Files.createTempFile("dagger-schema", ".json");
    schema.toFile().deleteOnExit();
    try (InputStream in =
        DaggerCLIUtils.query(DaggerCLIUtils.introspectionQuery(getClass()), this.bin)) {
      Files.copy(in, schema, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    return schema;
  }

  public File getOutputDirectory() {
    return outputDirectory;
  }

  /**
   * Validates the given encoding.
   *
   * @return the validated encoding. If {@code null} was provided, returns the platform default
   *     encoding.
   */
  private String validateEncoding(String encoding) {
    return (encoding == null)
        ? Charset.defaultCharset().name()
        : Charset.forName(encoding.trim()).name();
  }
}
