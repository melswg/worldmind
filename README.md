# Worldmind

Worldmind v0.1 is a server-first Fabric mod for Minecraft 1.20.1. It observes
accepted public server chat, batches ambient discussion, and lets one configured
character reply or remain silent. Authoritative work runs on the logical server:
dedicated-server players do not need a Worldmind client mod.

## Compatibility

- Minecraft 1.20.1 and Fabric Loader 0.15.11 or newer
- Java 17
- Linux, macOS, and Windows, with bundled SQLite JDBC natives
- Apache-2.0

An integrated single-player world is also a logical server. Put the common
Worldmind JAR in that instance when hosting such a world; Worldmind still has no
client entrypoint, screen, packet protocol, or required client-side feature.

## Install safely

1. Put the remapped `worldmind-fabric-1.20.1-<version>.jar` in the server's
   `mods/` directory with its required Fabric API modules.
2. Start once, then create `config/worldmind/worldmind.json` and a portable
   profile below `config/worldmind/profiles/<profile-id>/` from a synthetic
   example in [`docs/examples/`](docs/examples/).
3. Store an API credential outside the profile, Git, MRPACK, logs, memory, and
   shared LLM Wiki. Use an environment reference such as
   `env:WORLDMIND_API_KEY`, not a literal key.
4. Run `/worldmind validate` as an operator before enabling the integration.

Worldmind is safe to leave disabled: Minecraft keeps running when configuration
or a secret is invalid. It never discovers credentials automatically and the
repository test suite uses loopback fake providers only.

## Guides

- [Operator guide](docs/operator-guide.md)
- [Configuration reference](docs/configuration.md)
- [Provider presets](docs/providers.md)
- [Modpack authoring](docs/modpack-authoring.md)
- [Memory and privacy](docs/memory-and-privacy.md)
- [Extension API v0.1](docs/game-context-api-v0.1.md) and [developer guide](docs/development.md)
- [Upgrade and troubleshooting](docs/upgrade-and-troubleshooting.md)
- [Compatibility](docs/compatibility.md) and [v0.1.0 release notes](docs/releases/v0.1.0.md)

## Development

```text
./gradlew clean build
./gradlew sourceSecretScan runtimeCanaryAcceptance releaseArtifactAudit
```

The build is Java 17. Run a remapped-artifact check by setting
`WORLDMIND_REMAPPED_JAR` to the locally built Fabric JAR and executing
`./gradlew :fabric-1.20.1:test --tests io.github.melswg.worldmind.fabric.FabricArtifactPackagingTest`.

Worldmind v0.1 does not add an AI entity, pathfinding, world or inventory
actions, Minecraft command/tool execution, TTS/STT, web search, embeddings,
vector databases, a client mod, GUI, or support for Minecraft versions other
than 1.20.1.
