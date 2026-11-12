package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Gates#G7_process_1k}'s exit-code check, against stand-in launchers named by {@code jqj.launcher}: a shot whose
 * process fails must not be timed as a result. The real launcher runs in the spec gate's JMH step.
 */
class GatesTest {
  @TempDir Path dir;

  /** A shell script that exits 0 when it gets {@code -c .a <existing file>}, and 4 otherwise. */
  private static final String CHECKS_ARGS =
      "#!/bin/sh\n[ $# -eq 3 ] && [ \"$1\" = -c ] && [ \"$2\" = .a ] && [ -f \"$3\" ] && exit 0\nexit 4\n";

  private Path launcher(String name, String script) throws IOException {
    Path p = dir.resolve(name);
    Files.writeString(p, script, UTF_8);
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
    return p;
  }

  /** Runs one G7.process shot with {@code jqj.launcher} set to {@code launcher}, restoring the property after. */
  private static int shot(Path launcher) throws IOException, InterruptedException {
    String before = System.getProperty("jqj.launcher");
    System.setProperty("jqj.launcher", launcher.toString());
    Gates.ColdFile state;
    try {
      state = new Gates.ColdFile();
    } finally {
      if (before == null) {
        System.clearProperty("jqj.launcher");
      } else {
        System.setProperty("jqj.launcher", before);
      }
    }
    state.setUp();
    try {
      return new Gates().G7_process_1k(state);
    } finally {
      state.tearDown();
    }
  }

  @Test
  void aLauncherThatExitsZeroOnTheScenariosCommandLineGivesZero() throws IOException, InterruptedException {
    assertThat(shot(launcher("ok", CHECKS_ARGS))).isZero();
  }

  @Test
  void aLauncherThatExitsNonZeroFailsTheShot() throws IOException {
    Path failing = launcher("fails", "#!/bin/sh\nexit 3\n");
    assertThatThrownBy(() -> shot(failing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(failing + " exited with 3");
  }
}
