# Memory and privacy

Worldmind stores data per world in `save/worldmind/worldmind.sqlite3`. It records
accepted public observations and batch outcomes first, then maintains derived
facts, UUID-scoped relationships, events, current-situation versions, summaries,
and local FTS5/BM25 search documents. It does not use embeddings or an external
vector database.

Raw sequences retain provenance. Working context is bounded; compaction creates
derived summaries without silently rewriting the raw source. World/player scope
and visibility rules control inspection, retrieval, export, and deletion.

`dialogueRetention` controls whether raw observations persist and whether they
participate in recent context, compaction, and retrieval. Operators can inspect
bounded pages, view an allowed detail, export a portable JSON v1 record stream,
delete an exact record/player scope, or request a confirmed full-world reset.

Exports use `save/worldmind/exports/` and atomic publication. SQLite schema v1
migrates to v2 with a snapshot-aware `VACUUM INTO` backup under
`save/worldmind/backups/storage/`. Global config v1/v2 migrates to v3 with an
atomic backup bundle under `config/worldmind/backups/config/`. Too-new or failing
inputs are not rewritten. See [upgrade and troubleshooting](upgrade-and-troubleshooting.md).

Deletion is logical erasure from Worldmind's active database surfaces. Operators
remain responsible for server backups, filesystem copies, host snapshots, and
legal/privacy obligations. Registered credential values are redacted from unsafe
free-form persistence and export surfaces, but secrets must never be deliberately
sent in chat or stored as memory in the first place.
