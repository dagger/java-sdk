package io.dagger.sdk.engineconn;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@code dagger session} started by this process, for code that runs with no session in its
 * environment: a standalone client, a test, an application.
 *
 * <p>The CLI comes from {@code _EXPERIMENTAL_DAGGER_CLI_BIN} or, failing that, {@code dagger} on
 * the {@code PATH}. Nothing is downloaded: like a test framework using whatever Docker the host
 * has, this uses whatever Dagger the host has, and says so clearly when there is none. The Go and
 * TypeScript SDKs do the same, plus a download when nothing is found; that is deliberately not
 * reproduced here.
 */
public final class CLISession implements AutoCloseable {

  static final Logger LOG = LoggerFactory.getLogger(CLISession.class);

  private final Process process;
  private final int port;
  private final String sessionToken;
  private volatile Thread shutdownHook;

  private CLISession(Process process, int port, String sessionToken) {
    this.process = process;
    this.port = port;
    this.sessionToken = sessionToken;
  }

  /** The CLI to run: {@code _EXPERIMENTAL_DAGGER_CLI_BIN}, else {@code dagger} on the PATH. */
  public static String resolveCLI() {
    String bin = System.getenv("_EXPERIMENTAL_DAGGER_CLI_BIN");
    return bin == null || bin.isBlank() ? "dagger" : bin;
  }

  /**
   * Start a session with the given CLI, rooted at {@code workingDir}, and wait for it to announce
   * its port and token.
   */
  public static CLISession start(String cli, Path workingDir, boolean loadWorkspaceModules)
      throws IOException {
    List<String> command = new ArrayList<>();
    command.add(cli);
    command.add("session");
    command.add("--label");
    command.add("dagger.io/sdk.name:java");
    command.add("--label");
    command.add("dagger.io/sdk.version:" + sdkVersion());
    if (loadWorkspaceModules) {
      command.add("--load-workspace-modules");
    }
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new IOException(
          "could not run `"
              + cli
              + " session`: no Dagger session in the environment (DAGGER_SESSION_PORT and"
              + " DAGGER_SESSION_TOKEN) and no dagger CLI found; install one, or point"
              + " _EXPERIMENTAL_DAGGER_CLI_BIN at it",
          e);
    }
    LOG.debug("opening session: {}", command);
    BufferedReader stdout =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    try {
      String line;
      while ((line = stdout.readLine()) != null) {
        if (line.contains("session_token")) {
          CLISession session = announced(process, line);
          session.drain(stdout);
          return session;
        }
        LOG.info(line);
      }
    } catch (IOException | RuntimeException e) {
      // Nothing owns the process until a session is handed back, so it would outlive the failure.
      stop(process);
      throw e;
    }
    int exit = waitForExit(process);
    throw new IOException(
        "`" + cli + " session` exited with code " + exit + " before announcing a session");
  }

  /** The session a {@code {"port":…,"session_token":…}} line announces. */
  private static CLISession announced(Process process, String line) throws IOException {
    try (JsonReader reader = Json.createReader(new StringReader(line))) {
      JsonObject params = reader.readObject();
      if (!params.containsKey("port") || !params.containsKey("session_token")) {
        throw new IOException("`dagger session` announced no port and session token: " + line);
      }
      return new CLISession(process, params.getInt("port"), params.getString("session_token"));
    } catch (RuntimeException e) {
      throw new IOException("`dagger session` announced a line this SDK cannot read: " + line, e);
    }
  }

  public int port() {
    return port;
  }

  public String sessionToken() {
    return sessionToken;
  }

  /** Whether the session process is still running. */
  boolean isAlive() {
    return process.isAlive();
  }

  /** Stop the session. Idempotent; also runs at JVM exit so a session never outlives its owner. */
  @Override
  public void close() {
    removeShutdownHook();
    if (process.isAlive()) {
      stop(process);
    }
  }

  /** Stop a session process, waiting for it so it is reaped rather than left as a zombie. */
  private static void stop(Process process) {
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor();
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  private void removeShutdownHook() {
    Thread hook = shutdownHook;
    if (hook == null) {
      return;
    }
    shutdownHook = null;
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException shuttingDown) {
      // close() is running from the hook itself, or alongside it.
    }
  }

  // The session keeps writing to stdout after the announcement; an unread pipe would block it.
  private void drain(BufferedReader stdout) {
    Thread drain =
        new Thread(
            () -> {
              try {
                String line;
                while ((line = stdout.readLine()) != null) {
                  LOG.info(line);
                }
              } catch (IOException ignored) {
                // the process is gone
              }
            },
            "dagger-session-stdout");
    drain.setDaemon(true);
    drain.start();
    shutdownHook = new Thread(this::close, "dagger-session-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  private static int waitForExit(Process process) throws IOException {
    try {
      return process.waitFor();
    } catch (InterruptedException e) {
      stop(process);
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while waiting for the dagger session to exit", e);
    }
  }

  private static String sdkVersion() {
    String version = CLISession.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }
}
