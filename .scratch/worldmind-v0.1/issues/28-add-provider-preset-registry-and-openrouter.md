# 28 — Add the provider preset registry and OpenRouter

**Stage:** 5 — LLM provider expansion

**Parent:** Worldmind v0.1 specification; its complete scope remains authoritative.

**What to build:** Let an operator select OpenRouter as a first-class preset while preserving the working custom OpenAI-compatible path and all established safety and reliability behavior.

**Blocked by:** 27 — Verify the administrative upgrade scenario.

**Status:** completed

- [ ] A provider-preset registry selects provider-specific transport configuration without adding Minecraft types or branching provider logic in the application service.
- [ ] The OpenRouter preset supplies its compatible endpoint and required request mapping while model and generation parameters remain operator-configurable.
- [ ] OpenRouter credentials use the existing external secret boundary and never become profile content or diagnostics.
- [ ] Chat-batch mapping, participation decisions, timeout, queue, retry, circuit-breaker, redaction, and direct-versus-ambient typed-failure policies behave exactly as they do for the custom endpoint.
- [ ] Validated status output identifies the selected preset and readiness without revealing credential material.
- [ ] Fake HTTP contract tests prove the OpenRouter mapping and demonstrate that the existing custom provider behavior remains unchanged.
