# 33 — Add extension contract tests and an example integration

**Stage:** 6 — Public extension API

**Parent:** Worldmind v0.1 specification; its complete scope remains authoritative.

**What to build:** Give third-party mod developers a compilable example and executable contract proving how to add game context without relying on Worldmind internals.

**Blocked by:** 32 — Bound and isolate extension context.

**Status:** completed

- [x] An example external integration registers context, observes lifecycle, provides structured source-attributed data, and cleans up using only public API types.
- [x] The example compiles independently of Worldmind's internal implementation packages and requires no direct provider or SQLite access.
- [x] Contract tests prove that a compliant provider contributes bounded context for a sealed chat batch to the expected untrusted prompt layer.
- [x] Negative contract cases demonstrate isolation of timeout, exception, oversize, and instruction-like content.
- [x] API compatibility expectations, lifecycle, threading, limits, source attribution, and security boundary are documented for v0.1.
- [x] The example contains no assumptions or content tied to a particular personal modpack.
