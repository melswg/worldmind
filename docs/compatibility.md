# Compatibility and scope

Worldmind v0.1 supports Fabric on Minecraft **1.20.1** with **Java 17** on
Linux, macOS, and Windows. Its SQLite JDBC artifact bundles native libraries for
the supported platform families.

Dedicated-server players need no Worldmind client mod. The common mod loads in an
integrated host because that host runs the logical server, but there is no client
entrypoint, GUI, client packet protocol, renderer, or client-only API.

Global configuration is schema v3, portable profiles schema v1, storage schema
v2, and memory export schema v1; these versions evolve independently.

Out of scope for v0.1: AI entities, pathfinding, construction, world/block or
inventory actions, Minecraft command/function execution, arbitrary tools,
TTS/STT, web search, streaming/provider browsing, embeddings, vector databases,
client mods/GUI, non-Fabric loaders, versions other than Minecraft 1.20.1, and
embedded personal modpack knowledge.
