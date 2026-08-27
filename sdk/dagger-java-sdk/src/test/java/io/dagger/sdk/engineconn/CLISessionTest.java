package io.dagger.sdk.engineconn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CLISessionTest {

  @TempDir Path dir;

  @Test
  void parsesTheSessionAnnouncementAndStopsTheProcessOnClose() throws Exception {
    Path cli =
        fakeCli(
            "echo \"$@\" > \"$PWD/args\"\n"
                + "echo 'noise before the announcement'\n"
                + "echo '{\"port\":54321,\"session_token\":\"tok\"}'\n"
                + "exec sleep 30\n");
    CLISession session = CLISession.start(cli.toString(), dir, true);
    assertThat(session.port()).isEqualTo(54321);
    assertThat(session.sessionToken()).isEqualTo("tok");
    assertThat(session.isAlive()).isTrue();
    assertThat(Files.readString(dir.resolve("args")))
        .contains("session")
        .contains("--label dagger.io/sdk.name:java")
        .contains("--load-workspace-modules");

    session.close();
    assertThat(session.isAlive()).isFalse();
    session.close();
    assertThat(session.isAlive()).isFalse();
  }

  @Test
  void anAnnouncementThisSdkCannotReadLeavesNoProcessBehind() throws Exception {
    Path cli =
        fakeCli(
            "echo $$ > \"$PWD/pid\"\n" + "echo '{\"session_token\" oops}'\n" + "exec sleep 30\n");
    assertThatThrownBy(() -> CLISession.start(cli.toString(), dir, false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("cannot read");
    assertThat(isRunning(Files.readString(dir.resolve("pid")).trim())).isFalse();
  }

  @Test
  void anAnnouncementWithoutAPortIsAnError() throws Exception {
    Path cli = fakeCli("echo '{\"session_token\":\"tok\"}'\nexec sleep 30\n");
    assertThatThrownBy(() -> CLISession.start(cli.toString(), dir, false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no port and session token");
  }

  private static boolean isRunning(String pid) {
    return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false);
  }

  @Test
  void aCliThatExitsWithoutAnnouncingIsAnError() throws Exception {
    Path cli = fakeCli("echo 'starting' \nexit 3\n");
    assertThatThrownBy(() -> CLISession.start(cli.toString(), dir, false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exited with code 3");
  }

  @Test
  void aMissingCliIsExplained() {
    assertThatThrownBy(() -> CLISession.start(dir.resolve("no-such-dagger").toString(), dir, false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no dagger CLI found")
        .hasMessageContaining("_EXPERIMENTAL_DAGGER_CLI_BIN");
  }

  private Path fakeCli(String body) throws IOException {
    Path cli = dir.resolve("dagger");
    Files.writeString(cli, "#!/bin/sh\n" + body);
    Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
    return cli;
  }
}
