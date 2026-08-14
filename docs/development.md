# Developer guide

Worldmind has a dependency-free public extension boundary in `game-context-api`.
External Fabric mods implement the `worldmind-game-context-v1` entrypoint and
compile only against that artifact. See the complete [game-context API v0.1](game-context-api-v0.1.md)
and [`examples/game-context-provider`](../examples/game-context-provider).

The extension runtime validates the owning Fabric mod identity, invokes callbacks
on bounded daemon workers, gives each callback a 500 ms window, normalizes
results, and quarantines hostile/slow/failing extensions. Results are
source-attributed untrusted `CURRENT_GAME_CONTEXT`; they cannot alter trusted
rules, persona, memory, provider transport, participation, or delivery.

The public API exposes no Minecraft server/player/world handles, credentials,
LLM transport, SQLite/memory access, command execution, or client hooks. v0.1 is
patch-compatible: patches may add types/constants/default methods but do not
change supported contracts incompatibly.

Use `./gradlew clean build` for all Java 17 tests. Use fake language models,
controlled clock/scheduler, loopback HTTP, synthetic secret references, and real
temporary SQLite files. Do not add real-provider smoke tests to the required
suite.
