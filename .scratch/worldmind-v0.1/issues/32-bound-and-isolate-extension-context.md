# 32 — Bound and isolate extension context

**Stage:** 6 — Public extension API

**Parent:** Worldmind v0.1 specification; its complete scope remains authoritative.

**What to build:** Prevent a slow, failing, oversized, or hostile context provider from delaying the conversation, exhausting the prompt, or changing Worldmind's trusted instructions.

**Blocked by:** 31 — Publish the game-context provider API.

**Status:** completed

- [x] Each context-provider invocation for a sealed chat batch has bounded completion time and output size and does not block the Minecraft server thread or delay unrelated chat observation.
- [x] Timeout, exception, malformed result, and oversized result from one provider are isolated; remaining context and the main conversation can still complete.
- [x] Every accepted fragment retains provider source identity, normalization, and an explicit untrusted-data marker.
- [x] Extension context participates in global prompt budgeting only after protected policy, rules, and persona requirements are preserved.
- [x] Instruction-like content returned by an extension remains data and cannot reorder or replace trusted prompt layers.
- [x] Diagnostics identify the failing provider safely without exposing secrets, private memory, or unrestricted context content.
- [x] Deterministic tests cover correct, slow, failing, oversized, and prompt-injection providers in the high-level conversation seam.
