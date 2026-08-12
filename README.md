# Worldmind

Worldmind is a planned server-first Fabric mod that brings a configurable AI
character to Minecraft server chat.

## Project status

This repository contains the server-first bootstrap, strict v1 profile loading,
provider-neutral core conversation assembly, and a custom OpenAI-compatible
Chat Completions transport. It does not yet implement chat delivery or memory.

## Target platform

- Minecraft 1.20.1
- Fabric
- Java 17

Worldmind is designed to keep its authoritative logic on the logical Minecraft
server, so the same path can serve dedicated servers and single-player worlds.

## Modules

- `core` — Minecraft-independent authoritative bootstrap and future domain code.
- `fabric-1.20.1` — Fabric lifecycle adapter and the distributable mod artifact.
- `testkit` — deterministic acceptance seam with a fake LLM, controlled clock,
  controllable server scheduler, and synthetic vanilla game context. It records
  stable provider requests without HTTP, JSON, or a Minecraft client.

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
  "provider": {
    "id": "custom-openai-compatible",
    "endpoint": "https://provider.example/v1/chat/completions",
    "model": "example-model",
    "secretReference": "env:WORLDMIND_API_KEY",
    "generation": {"temperature": 0.4, "maxOutputTokens": 120}
  }
}
```

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
  "responseLengthLimit": 280
}
```

`secretReference` belongs only in the global configuration; portable profiles
cannot contain it. v1 accepts only `env:NAME`, read from the server process
environment. A missing or unreadable secret disables only the LLM integration
and emits field-specific diagnostics; Minecraft keeps running. The profile,
provider request, diagnostics, and example configuration never contain its
value. The configured endpoint is the full Chat Completions URI; HTTPS is
required except for local loopback HTTP used in tests or local development.

## Build

Run `./gradlew build` with Java 17. The build runs ordinary core tests and
server-side Fabric smoke tests; no client-side Worldmind entrypoint is present.

## License

Worldmind is licensed under the [Apache License 2.0](LICENSE).
