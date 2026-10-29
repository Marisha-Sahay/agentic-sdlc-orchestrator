# ADR-0010: LangChain4j as the model client, with JSON-mode structured output

Status: accepted, 2026-10-28 (revised after the first live recording)

## Context
Agents need a model client for Java with structured output, and the team's model provider is
Gemini.

## Decision
Use LangChain4j (`langchain4j-google-ai-gemini` 1.22) behind a one-method `LlmClient` interface.
Agents use the low-level `ChatModel` API rather than `AiServices` proxies so every call can be
keyed, recorded and attributed to a node and attempt.

Structured output uses **JSON mode with the expected shape in the prompt**: `JsonShape` renders
the agent's response record as a JSON skeleton (field names and types, nested lists and records)
and the request asks for `application/json`. The reply is parsed into the record; a reply that
does not parse fails the attempt with the parse error as feedback, and contract gates check the
content.

## Why not provider-side schema enforcement
The first version sent a JSON schema derived from the record (`JsonSchemas.jsonSchemaFrom`) so
Gemini would constrain decoding. Measured against `gemini-2.5-flash` with the requirements
contract:

| Request | Result |
|---|---|
| Plain text | 28 s, 2.6k output tokens |
| JSON mode + provider schema | no response after 100 s (the first recording stalled on its first call) |
| JSON mode + shape in the prompt | 23–30 s, valid object with every field |

Constrained decoding against nested schemas is a known slow path; since parsing and gates already
enforce the contract, the provider schema bought nothing but latency. It remains available per
client (`new LangChain4jLlmClient(model, name, true)`) for providers where it performs well.

## Consequences
- Agents are provider-agnostic; switching provider is a change to `ModelSettings` only.
- Model and key are configuration (`GEMINI_API_KEY`, `AGENTIC_MODEL`, `AGENTIC_FALLBACK_MODEL`);
  the key is never logged or stored, and live mode fails fast when they are placeholders.
- Fallback strategies can use a second model, so a provider-side problem with one model does not
  stop the run.
- A model can still return off-contract JSON; that is caught by parsing and gates and retried with
  the error, exactly like any other rejected attempt.
