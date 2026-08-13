# Worldmind game-context API v0.1

Worldmind 0.1 exposes one supported API for an external Fabric mod that has
small, structured, public game-context data to contribute to a conversation.
The API artifact and all supported types live in
`io.github.melswg.worldmind.api.gamecontext.v1`.

This is an extension-context API, not an LLM-provider registry. It exposes no
Worldmind core/Fabric implementation classes, LanguageModel or HTTP transport,
SQLite or memory access, credentials, commands, player/world handles, client
hooks, or configuration objects.

## Dependency and compatibility

Compile only against `game-context-api` and declare a Fabric dependency on
Worldmind `>=0.1.0 <0.2.0`. The externally registered entrypoint key is
`worldmind-game-context-v1`; `GameContextApi.VERSION` is `0.1`.

Within Worldmind 0.1.x, existing public signatures remain source and binary
compatible. Patch releases can add types, constants, and default interface
methods only. A breaking API change creates another versioned package and
entrypoint key, with temporary side-by-side support where needed. Neither API
version is coupled to Worldmind profile or database schemas.

```json
"entrypoints": {
  "worldmind-game-context-v1": ["example.mod.ExampleEntrypoint"]
},
"depends": {"worldmind": ">=0.1.0 <0.2.0"}
```

```java
public final class ExampleEntrypoint implements WorldmindGameContextEntrypoint {
    @Override public void register(GameContextRegistrar registrar) {
        registrar.register(new MyContextProvider());
    }
}
```

The standalone reference mod is
[`examples/game-context-provider`](../examples/game-context-provider). Its
compile classpath contains Worldmind only through `game-context-api`.

## Identity and registration

`GameContextProvider.source()` returns immutable
`GameContextSource(namespace, path)`. Both parts are canonical lowercase
identifiers; the namespace must exactly equal the Fabric mod id that owns the
custom entrypoint. Worldmind sorts providers by `namespace:path`, rejects
duplicates rather than selecting an arbitrary provider, and allows at most 32
providers.

Entrypoint discovery and registration occur while the logical server is
starting. Registration itself must only create/register a provider. It must not
block or call Minecraft APIs. `GameContextRegistration.close()` immediately
excludes that provider from new batches and triggers its one-time cleanup path.

## Lifecycle and threading

Worldmind invokes callbacks on Worldmind-owned daemon workers, never on the
Minecraft server thread:

1. `onServerStart`, then any already-loaded worlds in dimension-id order;
2. `onWorldLoad` / `onWorldUnload` as worlds change;
3. `onReload` after a successful Worldmind configuration reload;
4. on shutdown: stop new requests, cancel active requests, synthesize missing
   unloads, call `onServerShutdown`, then `onCleanup`.

Callback and `provide` work each has a 500 ms timeout. Providers in a sealed
batch start concurrently; a batch waits one timeout window, not one window per
provider. A timeout or lifecycle callback failure quarantines that provider
until the next logical-server start; a configuration reload does not reactivate
it. Synchronous exceptions, exceptional stages, null stages/results, malformed
and oversized results are isolated to that one invocation.

Cancellation and interruption are best-effort. Java cannot forcibly terminate
in-process code that ignores interruption. Worldmind permits at most one
abandoned daemon worker for a provider, quarantines it, and never invokes it
again during that server session. Extensions must cooperate with cancellation,
avoid blocking, and not make their own server-thread calls.

## Request and result model

`GameContextRequest` contains only an opaque session context, opaque world save
identity, first/last batch sequence, message count, and request time. It does
not contain chat text, player names or UUIDs, Minecraft/Fabric objects, profile
or configuration data, memory, credentials, queue state, or a participation
decision.

`provide` returns `CompletionStage<GameContextResult>`. A result has at most
eight `GameContextEntry(key, value)` values. Keys are normalized lowercase
`[a-z0-9][a-z0-9._-]*` and sorted deterministically. Worldmind NFC-normalizes
text, converts CRLF to LF, strips controls/bidi characters and invalid
surrogates, and rejects the whole result if a key/value is blank or invalid,
duplicated, or exceeds a limit:

- key: 64 Unicode code points;
- value: 512 Unicode code points;
- entire result: 1,024 Unicode code points.

Empty results are valid.

## Trust and security boundary

Worldmind keeps vanilla game context first, then appends extension entries in
provider/source-key order. An entry is labelled
`extension-game-context:<namespace:path>#<entry-key>` and becomes only a
`CURRENT_GAME_CONTEXT` fragment with `UNTRUSTED_DATA` trust.

Extension text cannot create/reorder trusted layers, modify the participation
protocol, persona, administrator rules, memory scope, batching, provider
transport, or delivery semantics. Prompt budgeting first reserves the trusted
floor; it drops extension context before vanilla context. Treat returned text as
hostile data, even when it resembles an instruction.

Safe status/diagnostics expose only a canonical provider identity and a fixed
diagnostic code. They never include returned context, exception class/message,
secret material, player identity, batch sequence range, or filesystem paths.
