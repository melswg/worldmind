# 14 — Retrieve relevant memory with FTS/BM25

**Stage:** 2 — Long-term memory

**Parent:** Worldmind v0.1 specification; its complete scope remains authoritative.

**What to build:** Compile a bounded memory context for each chat batch from the recent working window, current situation, and a small relevant set of older world records using only local storage.

**Blocked by:** 13 — Persist events and summaries with provenance.

**Status:** completed

- [x] The recent unsummarized public chat window and current-situation view are selected explicitly; older dialogue, facts, relationships, events, and summaries are candidates for SQLite FTS/BM25 retrieval without embeddings, a vector database, or external network calls.
- [x] Retrieval enforces world/player scope and visibility before ranking and cannot place another world or any player-private memory into a public provider request.
- [x] A deterministic ranking combines BM25 relevance with bounded recency and importance signals without allowing either to bypass scope, visibility, or size policy.
- [x] Deterministic count and size limits prevent retrieved memory from exhausting the prompt budget.
- [x] Returned entries preserve type, source sequence/range, time, confidence, importance, scope, and visibility and remain marked as untrusted memory.
- [x] Irrelevant or lower-ranked entries are omitted before any protected prompt layer is shortened.
- [x] The acceptance test covers the full cycle: multiple reply/silent batches, raw persistence, compaction, restart, relevant retrieval, provider request containing only allowed memory, and safe reply or deliberate silence.
