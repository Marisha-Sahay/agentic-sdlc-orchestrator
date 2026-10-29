package io.github.marishasahay.agentic.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLockTest {

  @TempDir
  Path runDir;

  @Test
  void aSecondOwnerOfTheSameRunIsRefusedUntilTheFirstReleasesIt() {
    try (RunLock first = RunLock.acquire(runDir)) {
      assertThat(first).isNotNull();
      assertThatThrownBy(() -> RunLock.acquire(runDir))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("is in use by another agentic process (pid "
              + ProcessHandle.current().pid() + ")");
    }

    try (RunLock again = RunLock.acquire(runDir)) {
      assertThat(again).isNotNull();
    }
  }
}
