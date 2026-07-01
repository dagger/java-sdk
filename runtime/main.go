// Runtime module for the Java SDK.
//
// This runtime only builds and packages a module's already-vendored, committed
// sources. Code generation (vendoring the SDK as source and producing the
// entrypoint) is owned by the Java SDK's `generate` step and runs at `dagger
// generate` time, not here. New-style modules set codegen.automaticGitignore=false,
// which commits the generated files and makes the engine skip codegen at module
// load, so this runtime never regenerates them.

package main

import (
	"context"
	"fmt"
	"path/filepath"
	"strings"

	_ "embed"

	"java-sdk-runtime/internal/dagger"
)

const (
	ModSourceDirPath = "/src"
	ModDirPath       = "/opt/module"
)

type JavaSdkRuntime struct {
	// Deprecated: unused by the build/package-only runtime. Retained because the
	// committed dagger.gen.go dispatch and the engine still construct the runtime
	// with it; it is regenerated away when the runtime is wired into the engine.
	SDKSourceDir *dagger.Directory
	// If true, -e flag will be added to the maven command to display execution error messages
	MavenErrors bool
	// If true, -X flag will be added to the maven command to enable full debug logging
	MavenDebugLogging bool

	moduleConfig moduleConfig
}

type moduleConfig struct {
	subPath string
}

func (c *moduleConfig) modulePath() string {
	return filepath.Join(ModSourceDirPath, c.subPath)
}

func New(
	// Deprecated: unused by the build/package-only runtime; accepted for
	// compatibility with the committed dagger.gen.go and the engine, which may
	// still pass the SDK source directory.
	// +optional
	sdkSourceDir *dagger.Directory,
) (*JavaSdkRuntime, error) {
	return &JavaSdkRuntime{
		SDKSourceDir: sdkSourceDir,
		MavenErrors:  false,
	}, nil
}

func (m *JavaSdkRuntime) WithConfig(
	// +default=false
	mavenErrors bool,
	// +default=false
	mavenDebugLogging bool,
) *JavaSdkRuntime {
	m.MavenErrors = mavenErrors
	m.MavenDebugLogging = mavenDebugLogging
	return m
}

// Codegen is intentionally a no-op for this build/package-only runtime: code
// generation is owned by the Java SDK's `generate` step (`dagger generate`), and
// new-style modules (codegen.automaticGitignore=false) commit the generated
// files, so the engine skips codegen at module load. Returning the module source
// unchanged keeps the SDK contract satisfied without generating anything. This
// method is removed/regenerated when the runtime is wired into the engine.
func (m *JavaSdkRuntime) Codegen(
	ctx context.Context,
	modSource *dagger.ModuleSource,
	introspectionJSON *dagger.File,
) (*dagger.GeneratedCode, error) {
	return dag.GeneratedCode(modSource.ContextDirectory()), nil
}

// ModuleRuntime builds and packages the module from its committed sources and
// returns a container that runs the resulting jar. The module is self-contained:
// the Java SDK is vendored as source under sdk/ and the entrypoint is committed
// under src/generated/java, so a plain `mvn package` (dagger.proc defaults to
// "none") compiles them together and only fetches third-party dependencies. No
// codegen, introspection, templating, or version rewriting happens here.
func (m *JavaSdkRuntime) ModuleRuntime(
	ctx context.Context,
	modSource *dagger.ModuleSource,
	introspectionJSON *dagger.File,
) (*dagger.Container, error) {
	if err := m.setModuleConfig(ctx, modSource); err != nil {
		return nil, err
	}

	mvnCtr, err := m.mvnContainer(ctx)
	if err != nil {
		return nil, err
	}
	mvnCtr = mvnCtr.
		WithMountedCache("/root/.m2", dag.CacheVolume("sdk-java-maven-m2"), dagger.ContainerWithMountedCacheOpts{Sharing: dagger.CacheSharingModeLocked}).
		WithDirectory(ModSourceDirPath, modSource.ContextDirectory()).
		WithWorkdir(m.moduleConfig.modulePath()).
		WithExec(m.mavenCommand("mvn", "package", "-DskipTests"))

	jar, err := m.finalJar(ctx, mvnCtr)
	if err != nil {
		return nil, err
	}

	javaCtr, err := m.jreContainer(ctx)
	if err != nil {
		return nil, err
	}
	return javaCtr.
		WithFile(filepath.Join(ModDirPath, "module.jar"), jar).
		WithWorkdir(ModDirPath).
		WithEntrypoint([]string{"java", "-jar", filepath.Join(ModDirPath, "module.jar")}), nil
}

// finalJar returns the packaged jar built from the user module. Rather than
// shelling out to maven (a fresh JVM per query) to compute the
// <artifactId>-<version>.jar name, it reads the already-built target directory
// and picks the packaged jar, skipping shade's "original-" backup of the
// pre-shaded artifact.
func (m *JavaSdkRuntime) finalJar(
	ctx context.Context,
	ctr *dagger.Container,
) (*dagger.File, error) {
	targetPath := filepath.Join(m.moduleConfig.modulePath(), "target")
	entries, err := ctr.Directory(targetPath).Entries(ctx)
	if err != nil {
		return nil, err
	}
	for _, name := range entries {
		if strings.HasSuffix(name, ".jar") && !strings.HasPrefix(name, "original-") {
			return ctr.File(filepath.Join(targetPath, name)), nil
		}
	}
	return nil, fmt.Errorf("no packaged jar found in %s", targetPath)
}

func (m *JavaSdkRuntime) mvnContainer(ctx context.Context) (*dagger.Container, error) {
	return disableSVEOnArm64(ctx, m.MavenImage())
}

func (m *JavaSdkRuntime) jreContainer(ctx context.Context) (*dagger.Container, error) {
	return disableSVEOnArm64(ctx, m.JavaImage())
}

func disableSVEOnArm64(ctx context.Context, ctr *dagger.Container) (*dagger.Container, error) {
	if platform, err := ctr.Platform(ctx); err != nil {
		return nil, err
	} else if strings.Contains(string(platform), "arm64") {
		return ctr.WithEnvVariable("_JAVA_OPTIONS", "-XX:UseSVE=0"), nil
	}
	return ctr, nil
}

func (m *JavaSdkRuntime) setModuleConfig(ctx context.Context, modSource *dagger.ModuleSource) error {
	subPath, err := modSource.SourceSubpath(ctx)
	if err != nil {
		return err
	}
	m.moduleConfig = moduleConfig{subPath: subPath}
	return nil
}

func (m *JavaSdkRuntime) mavenCommand(args ...string) []string {
	if m.MavenErrors {
		args = append(args, "-e")
	}
	if m.MavenDebugLogging {
		args = append(args, "-X")
	}
	args = append(args, "--threads", "1C", "--no-transfer-progress")
	return args
}

//go:embed images/maven/Dockerfile
var mavenImage string

func (m *JavaSdkRuntime) MavenImage() *dagger.Container {
	return dag.Directory().WithNewFile("Dockerfile", mavenImage).DockerBuild()
}

//go:embed images/java/Dockerfile
var javaImage string

func (m *JavaSdkRuntime) JavaImage() *dagger.Container {
	return dag.Directory().WithNewFile("Dockerfile", javaImage).DockerBuild()
}
