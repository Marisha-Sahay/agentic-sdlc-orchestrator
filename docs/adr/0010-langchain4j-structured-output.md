# ADR-0010: LangChain4j as the model client, with schema-constrained output

Status: accepted, 2026-10-28

## Context
Agents need a model client for Java with structured output, and the team's model provider is
Gemini.

## Decision
Use LangChain4j (`langchain4j-google-ai-gemini` 1.22) behind a one-method `LlmClient` interface.
Each call sends a JSON schema derived from the agent's response record
(`JsonSchemas.jsonSchemaFrom`), so the provider constrains decoding; replies are parsed into the
record and a parse failure fails the attempt with the error as feedback. Agents use the low-level
`ChatModel` API rather than `AiServices` proxies so every call can be keyed, recorded and
attributed to a node and attempt.

## Consequences
- Agents are provider-agnostic; switching provider is a change to `ModelSettings` only.
- Model and key are configuration (`GEMINI_API_KEY`, `AGENTIC_MODEL`, `AGENTIC_FALLBACK_MODEL`);
  the key is never logged or stored, and live mode fails fast when they are placeholders.
- Fallback strategies use a second model, so a provider-side problem with one model does not stop
  the run.
