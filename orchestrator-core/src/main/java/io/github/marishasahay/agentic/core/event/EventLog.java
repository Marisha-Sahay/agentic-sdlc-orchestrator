package io.github.marishasahay.agentic.core.event;

import io.github.marishasahay.agentic.core.util.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Append-only, hash-chained event log, optionally persisted as JSON Lines. Appends are
 * serialised so sequence numbers and the hash chain are total-ordered even with parallel nodes.
 * Subscribers (console progress, live report) are notified synchronously but can never break
 * execution: their exceptions are swallowed.
 */
public final class EventLog {

  static final String GENESIS = "GENESIS";

  private final String runId;
  private final Path file;
  private final Clock clock;
  private final List<EventEnvelope> events = new ArrayList<>();
  private final List<Consumer<EventEnvelope>> subscribers = new CopyOnWriteArrayList<>();
  private Throwable writeFailure;

  /**
   * Creates a log, loading any events already in {@code file}.
   *
   * @param runId run id
   * @param file JSONL file, or null for memory only
   * @param clock time source
   */
  public EventLog(String runId, Path file, Clock clock) {
    this.runId = runId;
    this.file = file;
    this.clock = clock;
    if (file != null && Files.exists(file)) {
      events.addAll(readFile(file));
    }
  }

  public static EventLog inMemory(String runId, Clock clock) {
    return new EventLog(runId, null, clock);
  }

  /**
   * Reads a persisted log without opening it for writing.
   *
   * @param file JSONL file
   * @return envelopes in order
   */
  public static List<EventEnvelope> readFile(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .filter(line -> !line.isBlank())
          .map(line -> Json.read(line, EventEnvelope.class))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String runId() {
    return runId;
  }

  /**
   * Appends an event, extending the hash chain and persisting it before returning. Fail-stop:
   * once a write has failed, every later append fails too, so the log can never contain a gap
   * that later events paper over.
   *
   * @param event payload
   * @return the stored envelope
   * @throws AuditUnavailableException if the event could not be persisted
   */
  public synchronized EventEnvelope append(RunEvent event) {
    if (writeFailure != null) {
      throw new AuditUnavailableException("Audit log unavailable since an earlier write failed; "
          + "refusing to record " + event.getClass().getSimpleName(), writeFailure);
    }
    long seq = events.size() + 1L;
    String prev = events.isEmpty() ? GENESIS : events.getLast().hash();
    EventEnvelope unsigned = new EventEnvelope(seq, runId, clock.instant(), event, prev, null);
    EventEnvelope envelope = new EventEnvelope(seq, runId, unsigned.at(), event, prev,
        hashOf(unsigned));
    if (file != null) {
      try {
        Files.writeString(file, Json.write(envelope) + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException e) {
        writeFailure = e;
        throw new AuditUnavailableException("Audit log write failed (" + e + "); refusing to "
            + "continue unaudited", e);
      }
    }
    events.add(envelope);
    for (Consumer<EventEnvelope> subscriber : subscribers) {
      try {
        subscriber.accept(envelope);
      } catch (RuntimeException ignored) {
        // Observability must never break execution.
      }
    }
    return envelope;
  }

  /**
   * Whether an earlier write failed (the log is then read-only and the run must stop).
   *
   * @return true once a write has failed
   */
  public synchronized boolean unavailable() {
    return writeFailure != null;
  }

  public synchronized List<EventEnvelope> events() {
    return List.copyOf(events);
  }

  public void subscribe(Consumer<EventEnvelope> subscriber) {
    subscribers.add(subscriber);
  }

  /**
   * Recomputes the hash chain.
   *
   * @param envelopes log contents
   * @return sequence number of the first broken link, if any
   */
  public static Optional<Long> verifyChain(List<EventEnvelope> envelopes) {
    String prev = GENESIS;
    for (EventEnvelope envelope : envelopes) {
      if (!envelope.prevHash().equals(prev) || !envelope.hash().equals(hashOf(envelope))) {
        return Optional.of(envelope.seq());
      }
      prev = envelope.hash();
    }
    return Optional.empty();
  }

  private static String hashOf(EventEnvelope envelope) {
    return Hashing.sha256(envelope.prevHash() + "\n" + envelope.seq() + "\n" + envelope.runId()
        + "\n" + envelope.at() + "\n" + Json.write(envelope.event()));
  }
}
