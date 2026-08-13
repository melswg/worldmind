# 13 — Persist events and summaries with provenance

**Stage:** 2 — Long-term memory

**Parent:** Worldmind v0.1 specification; its complete scope remains authoritative.

**What to build:** Compact sealed portions of a long-running world's raw journal into auditable events, chunk summaries, and a bounded current-situation view without making lossy compression the source of truth.

**Blocked by:** 12 — Recall player and world facts and relationships.

**Status:** completed

- [x] Memory stores events, immutable chunk summaries, and the replaceable current-situation view independently from raw dialogue and assigns each one world/player scope plus visibility.
- [x] Each event and summary retains timestamps, confidence, importance, and an exact contiguous source sequence range or explicit source references back to the raw material it condenses.
- [x] Compaction seals older completed ranges according to a bounded context policy, keeps a recent working window unsummarized, and never deletes or rewrites raw journal rows as part of summarization.
- [x] Repeated compaction creates a new version rather than recursively overwriting the only prior summary; a summary can be audited or rebuilt from its raw range.
- [x] Summarization never converts player text, lore, dialogue, or retrieved memory into a trusted instruction.
- [x] Summary/event generation and persistence run outside the Minecraft server thread, and failure leaves the raw journal readable and the server available.
- [x] A storage restart preserves events, summary versions, range coverage, scope, visibility, and provenance.
- [x] Later conversation assembly can include the current-situation view plus applicable summaries/events in the untrusted memory layer within a defined size limit.
- [x] Tests prove that compaction does not erase, duplicate, recursively drift, or misattribute the underlying sequence ranges and that unrelated UUID/world scopes and private visibility remain isolated.
