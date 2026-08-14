# Modpack authoring

A profile is portable character material, not a copy of a modpack's private
knowledge. Distribute `profile.json`, persona, administrator rules, and selected
lore files with a pack only when their authors allow redistribution.

- Set a recognizable `characterName`; exact and likely addressing are only fast
  batching signals, not a command interface.
- Put trusted server behavior in `rules.md`; persona controls tone and ambient
  sociability, not a fixed reply quota.
- Keep lore curated, bounded, and separate from secrets or player-private data.
- Set style, response length, and optional chat-name color in `profile.json`.
- Keep `worldmind.json` deployment-specific: it selects a profile and provider
  reference, but must not contain credential material.

Validate the copied tree with `/worldmind validate` before enabling it. MRPACK
may contain the portable profile and this public mod, but never API keys, private
world data, logs, exports, memory DBs, personal paths, or unlicensed lore.
