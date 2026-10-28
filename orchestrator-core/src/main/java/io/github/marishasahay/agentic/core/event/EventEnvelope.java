package io.github.marishasahay.agentic.core.event;

import java.time.Instant;

/**
 * A logged event with ordering and tamper-evidence. {@code hash} covers this envelope's fields
 * plus the previous hash, so editing or deleting any earlier line breaks every later hash.
 *
 * @param seq 1-based sequence number
 * @param runId run id
 * @param at timestamp
 * @param event payload
 * @param prevHash hash of the previous envelope ({@code GENESIS} for the first)
 * @param hash hash of this envelope
 */
public record EventEnvelope(
    long seq, String runId, Instant at, RunEvent event, String prevHash, String hash) {}
