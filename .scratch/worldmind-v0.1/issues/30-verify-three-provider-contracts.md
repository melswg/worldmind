# 30 — Verify all three provider contracts

**Stage:** 5 — LLM provider expansion

**Parent:** Worldmind v0.1 specification; its complete scope remains authoritative.

**What to build:** Prove that custom OpenAI-compatible, OpenRouter, and direct DeepSeek providers are interchangeable from the Worldmind conversation path while retaining provider-specific transport behavior.

**Blocked by:** 29 — Add the direct DeepSeek preset.

**Status:** completed

- [x] One reusable contract suite verifies chat-batch mapping, `DIRECT_REPLY`/`AMBIENT_REPLY`/`SILENT`, model parameters, typed failures, timeout, retry, circuit breaker, and redaction for all three provider variants.
- [x] Switching provider configuration changes only the selected transport adapter and does not change batching, prompt hierarchy, participation, memory, chat delivery, or administration contracts.
- [x] Invalid preset/model/endpoint combinations fail validation before a paid request and leave the server in a diagnosable safe state.
- [x] Fake HTTP tests require no internet access, user key, or paid account and run as part of the normal suite.
- [x] No optional real-provider smoke test is included in v0.1; any future task must be explicit opt-in, excluded from required CI, safely skipped without its external secret, and must not persist bodies or credentials.
- [x] Documentation inputs clearly support the recommendation to use a separate spending-limited key per deployment without Worldmind creating or managing keys.
