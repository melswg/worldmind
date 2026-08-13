# Worldmind

Worldmind is a planned server-first Fabric mod that brings a configurable AI
character to Minecraft server chat.

## Project status

This repository contains the server-first bootstrap, strict v1 profile loading,
provider-neutral core conversation assembly, a custom OpenAI-compatible Chat
Completions transport, bounded observation of accepted public server chat,
typed participation decisions, prompt safety limits, and selective literal
server-chat delivery for sealed chat batches. It does not yet implement
long-term memory.

## Target platform

- Minecraft 1.20.1
- Fabric
- Java 17

Worldmind is designed to keep its authoritative logic on the logical Minecraft
server, so the same path can serve dedicated servers and single-player worlds.

## Modules

- `core` — Minecraft-independent authoritative bootstrap, public-chat batching,
  participation-decision protocol, and future domain code.
- `fabric-1.20.1` — Fabric lifecycle adapter and the distributable mod artifact.
- `testkit` — deterministic acceptance seam with a fake LLM, controlled clock,
  controllable server scheduler, and synthetic vanilla game context. It records
  stable provider requests and sealed chat batches without a Minecraft client.

## Configuration v1

At logical-server startup, Worldmind reads
`config/worldmind/worldmind.json` and the selected portable profile from
`config/worldmind/profiles/<profile-id>/`. Both documents must declare
`"schemaVersion": 1`. The v1 policy is strict: unknown fields and any schema
version other than `1` are rejected, and startup never migrates or rewrites a
file.

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "activeProfile": "oracle",
  "chatBatching": {
    "maxMessages": 8,
    "maxWaitMillis": 5000,
    "maxEstimatedInputCharacters": 4000
  },
  "requestQueue": {
    "capacity": 16,
    "maxConcurrency": 2
  },
  "provider": {
    "id": "custom-openai-compatible",
    "endpoint": "https://provider.example/v1/chat/completions",
    "model": "example-model",
    "secretReference": "env:WORLDMIND_API_KEY",
    "timeouts": {"connectMillis": 5000, "responseCompletionMillis": 30000},
    "retry": {"maximumAttempts": 3, "initialBackoffMillis": 250, "maximumBackoffMillis": 4000, "jitterRatio": 0.2},
    "generation": {"temperature": 0.4, "maxOutputTokens": 120}
  }
}
```

`chatBatching` is required in v1. It bounds a per-save public-chat batch by
message count, elapsed time from its first message, and a stable early Unicode
character estimate. Reaching a limit includes the triggering message and
seals the batch; it does not impose a reply quota.

`requestQueue` is also required in v1. `capacity` is the maximum number of
waiting conversation or memory-compaction jobs; `maxConcurrency` bounds active
jobs. Both must be positive integers, so the total owned work cannot exceed
their sum. Invalid or missing values disable the integration before it accepts
chat.

The selected profile contains only portable character material in
`profile.json`, plus the referenced Markdown files:

```json
{
  "schemaVersion": 1,
  "characterName": "Aster",
  "personaFile": "persona.md",
  "administratorRulesFile": "rules.md",
  "loreFiles": ["lore/world.md"],
  "responseStyle": "calm and concise",
  "responseLengthLimit": 280,
  "chatNameColor": "light_purple"
}
```

`chatNameColor` is optional and controls only the Worldmind name used in
server-chat delivery. It defaults to `light_purple` and accepts one exact
vanilla palette name: `black`, `dark_blue`, `dark_green`, `dark_aqua`,
`dark_red`, `dark_purple`, `gold`, `gray`, `dark_gray`, `blue`, `green`,
`aqua`, `red`, `light_purple`, `yellow`, or `white`.

`secretReference` belongs only in the global configuration; portable profiles
cannot contain it. v1 accepts only `env:NAME`, read from the server process
environment. A missing or unreadable secret disables only the LLM integration
and emits field-specific diagnostics; Minecraft keeps running. The profile,
provider request, diagnostics, and example configuration never contain its
value. The configured endpoint is the full Chat Completions URI; HTTPS is
required except for local loopback HTTP used in tests or local development.

`timeouts` is required in v1. `connectMillis` bounds a new TCP/TLS connection;
`responseCompletionMillis` bounds the complete HTTP exchange and response body.

`retry` is required in v1. Only connection loss, timeouts, HTTP 429, and 5xx
responses retry; the first attempt is included in `maximumAttempts`.

## Prompt and chat safety

The provider-visible conversation always has this fixed order: built-in safety
policy and participation protocol, administrator rules, persona, lore, memory,
current game context, then the current public-chat batch. Lore, memory, game
context, and every player message remain source-attributed untrusted data;
they cannot create another prompt layer, system message, tool call, or chat
delivery path.

v1 uses documented internal, provider-neutral Unicode code-point estimates:
12,000 for total input, 2,400 per untrusted layer, and 900 per serialized chat
fragment. They are not token counts and are intentionally not configuration
fields. When input is excessive, future memory, lore, game context, and then
older chat data are removed first. The newest triggering chat fragment keeps
its stable source attribution and carries a truncation marker when shortened.
If the mandatory trusted prompt cannot fit, Worldmind makes no provider request
and returns a controlled failure to the existing delivery path.

Provider output is decoded once by the participation protocol. Reply text is
then normalized, stripped of formatting/control and directional characters, and
limited by the configured `responseLengthLimit` using Unicode code points. The
Fabric adapter always constructs literal components, so command-looking text,
URLs, JSON, or click-event-looking output has no executable or interactive
meaning. No configuration changes are needed for these safety boundaries.

## Build

Run `./gradlew build` with Java 17. The build runs ordinary core tests and
server-side Fabric smoke tests; no client-side Worldmind entrypoint is present.

## License

Worldmind is licensed under the [Apache License 2.0](LICENSE).
