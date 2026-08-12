# Worldmind

Worldmind is a planned server-first Fabric mod that brings a configurable AI
character to Minecraft server chat.

## Project status

This repository contains the initial server-first multi-module skeleton. It
does not yet implement chat, configuration, memory, or LLM providers.

## Target platform

- Minecraft 1.20.1
- Fabric
- Java 17

Worldmind is designed to keep its authoritative logic on the logical Minecraft
server, so the same path can serve dedicated servers and single-player worlds.

## Modules

- `core` — Minecraft-independent authoritative bootstrap and future domain code.
- `fabric-1.20.1` — Fabric lifecycle adapter and the distributable mod artifact.
- `testkit` — reserved namespace for deterministic test support.

## Build

Run `./gradlew build` with Java 17. The build runs ordinary core tests and
server-side Fabric smoke tests; no client-side Worldmind entrypoint is present.

## License

Worldmind is licensed under the [Apache License 2.0](LICENSE).
