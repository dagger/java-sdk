package io.dagger.codegen.introspection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where a client's target lives, as the caller resolved it at generation time: the address the
 * engine serves it from, and for a git target the commit that address resolved to.
 *
 * <p>No name: the engine names a served module itself, and the bindings select the {@code Query}
 * field the target's schema already carries.
 *
 * @param pin the commit a git address resolved to, or null for a local target
 */
public record TargetDescriptor(String address, String pin) {

  private static final String LOCAL = "local";
  private static final String GIT = "git";

  public TargetDescriptor {
    if (address == null || address.isBlank()) {
      throw new IllegalArgumentException("a target descriptor needs an address");
    }
    if (pin != null && pin.isBlank()) {
      throw new IllegalArgumentException("git target " + address + " has an empty pin");
    }
    if (pin == null && !address.startsWith("/")) {
      throw new IllegalArgumentException(
          "local target " + address + " is not a workspace-absolute path");
    }
  }

  public static TargetDescriptor local(String path) {
    return new TargetDescriptor(path, null);
  }

  public static TargetDescriptor git(String address, String pin) {
    if (pin == null) {
      throw new IllegalArgumentException("git target " + address + " has no pin");
    }
    return new TargetDescriptor(address, pin);
  }

  public boolean isGit() {
    return pin != null;
  }

  /**
   * Read a descriptor from a plan entry: a kind on the first line, its fields on the lines after.
   * One field per line rather than a delimiter, because a path or a git address may legally hold
   * whatever separator would otherwise be chosen.
   */
  public static TargetDescriptor read(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    String kind = lines.isEmpty() ? "" : lines.get(0);
    try {
      if (kind.equals(LOCAL) && lines.size() == 2) {
        return local(lines.get(1));
      }
      if (kind.equals(GIT) && lines.size() == 3) {
        return git(lines.get(1), lines.get(2));
      }
    } catch (IllegalArgumentException e) {
      throw new IOException(file + ": " + e.getMessage(), e);
    }
    throw new IOException(
        file
            + " is not a target descriptor: expected \""
            + LOCAL
            + "\" and a path, or \""
            + GIT
            + "\", an address and a commit; got "
            + lines);
  }
}
