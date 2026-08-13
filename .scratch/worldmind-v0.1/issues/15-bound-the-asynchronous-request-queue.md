# 15 — Bound the asynchronous request queue

**Stage:** 3 — Resilience and secrets

**Parent:** Worldmind v0.1 specification; its complete scope remains authoritative.

**What to build:** Prevent chat bursts, sealed batches, memory work, or a slow provider from creating unlimited background work while preserving per-world ordering and direct-address behavior.

**Blocked by:** 14 — Retrieve relevant memory with FTS/BM25.

**Status:** completed

- [x] Server configuration defines finite request-queue capacity and concurrency limits compatible with the earlier finite pending-batch policy.
- [x] Sealed batches retain per-world sequence order across pending, queued, in-flight, and completed states; a later direct-address batch cannot be delivered ahead of an earlier owned batch or cross world identity.
- [x] Submitting work returns control to the Minecraft server thread without waiting for HTTP, SQLite persistence/retrieval, compaction, or another queued batch.
- [x] Work arriving after capacity is exhausted returns a controlled domain result. Confirmed direct address receives one short player-facing unavailable message; ambient overflow produces no public chat output and remains safely diagnosable.
- [x] Raw messages already accepted by the journal are not fabricated as provider-processed merely because their batch was rejected or cancelled.
- [x] Queue accounting releases capacity after success, failure, and cancellation and does not leak permits across repeated requests.
- [x] Server shutdown cancels or drains owned work predictably without invoking server state after shutdown.
- [x] A concurrent acceptance test proves bounded work, per-world ordering, responsive server scheduling, in-flight/new-message separation, and deterministic direct-versus-ambient overflow behavior.
