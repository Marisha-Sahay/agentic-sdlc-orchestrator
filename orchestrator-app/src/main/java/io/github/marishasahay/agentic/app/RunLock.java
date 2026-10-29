package io.github.marishasahay.agentic.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Exclusive ownership of a run directory for one process. Two processes running or resuming the
 * same run would interleave the hash-chained log and roll back each other's workspace, so the
 * second one refuses to start. The OS releases the lock when the owning process exits, however
 * it exits.
 */
final class RunLock implements AutoCloseable {

  static final String FILE = ".lock";

  private final FileChannel channel;
  private final FileLock lock;

  private RunLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  /**
   * Takes the lock or fails.
   *
   * @param runDir run directory
   * @return the held lock
   * @throws IllegalStateException if another process (or this one) holds it
   */
  static RunLock acquire(Path runDir) {
    try {
      Files.createDirectories(runDir);
      Path file = runDir.resolve(FILE);
      FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
          StandardOpenOption.READ, StandardOpenOption.WRITE);
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        lock = null;
      }
      if (lock == null) {
        channel.close();
        throw new IllegalStateException("Run " + runDir.getFileName() + " is in use by another "
            + "agentic process (" + Files.readString(file, StandardCharsets.UTF_8).strip()
            + "). Wait for it to exit; a stopping run finishes its running attempts first.");
      }
      channel.truncate(0);
      channel.write(ByteBuffer.wrap(("pid " + ProcessHandle.current().pid())
          .getBytes(StandardCharsets.UTF_8)));
      return new RunLock(channel, lock);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    try {
      lock.release();
      channel.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
