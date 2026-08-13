# 31 — Publish the game-context provider API

**Stage:** 6 — Public extension API

**Parent:** Worldmind v0.1 specification; its complete scope remains authoritative.

**What to build:** Allow another mod to register a stable source of structured game context without depending on Worldmind internals or making Worldmind depend on a particular modpack.

**Blocked by:** 30 — Verify all three provider contracts.

**Status:** completed

- [x] The public API defines registration, server lifecycle, batch request context, structured result, and source identity without exposing internal application, provider, batching, or persistence classes.
- [x] Registration and cleanup behave predictably across server start, world load/unload, and server shutdown.
- [x] A context provider receives only the minimum server/batch information needed by the public contract and receives no raw player UUID list unless explicitly required, provider credential, private memory, or unrestricted memory-database access.
- [x] Returned context joins the same normalized current-context layer used by vanilla context and is never treated as administrator rules or persona.
- [x] Multiple providers can coexist with deterministic source attribution and without hard-coded knowledge of any specific mod or pack.
- [x] The public API declares a compatibility/version contract that can be documented and tested for v0.1 consumers.
