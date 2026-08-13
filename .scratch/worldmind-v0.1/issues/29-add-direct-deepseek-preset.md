# 29 — Add the direct DeepSeek preset

**Stage:** 5 — LLM provider expansion

**Parent:** Worldmind v0.1 specification; its complete scope remains authoritative.

**What to build:** Let an operator select the direct DeepSeek API through the same provider contract, configuration model, and operational policies as the other supported endpoints.

**Blocked by:** 28 — Add the provider preset registry and OpenRouter.

**Status:** completed

- [ ] The DeepSeek preset supplies its compatible endpoint and provider-specific request/response mapping through the preset registry.
- [ ] Model and compatible generation parameters are validated and passed without adding DeepSeek concerns to the application service.
- [ ] DeepSeek credentials resolve through the existing secret boundary and remain absent from profiles, memory, exports, diagnostics, and fixtures.
- [ ] Queue, timeout, retry classification, circuit breaker, redaction, and player-facing failures apply consistently.
- [ ] Fake HTTP tests cover successful completion, provider error mapping, malformed output, and missing/invalid credential state.
- [ ] Adding DeepSeek does not alter custom endpoint or OpenRouter contract-test results.
