package io.github.marishasahay.agentic.agents.llm;

/** How model calls are served. */
public enum LlmMode {
  /** Call the model; nothing is stored. */
  LIVE,
  /** Call the model and store every exchange as a cassette. */
  RECORD,
  /** Serve stored cassettes; no network, no API key, deterministic. */
  REPLAY
}
