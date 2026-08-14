# Configuration reference

Global configuration is `config/worldmind/worldmind.json`. Its current schema
is **3**. The selected portable profile is
`config/worldmind/profiles/<profile-id>/profile.json`, currently schema **1**.
Unknown fields are rejected before secret resolution or HTTP.

## Global schema v3

```json
{
  "schemaVersion": 3,
  "enabled": true,
  "activeProfile": "oracle",
  "chatBatching": {
    "maxMessages": 8,
    "maxWaitMillis": 5000,
    "maxEstimatedInputCharacters": 4000
  },
  "requestQueue": {"capacity": 16, "maxConcurrency": 2},
  "dialogueRetention": {
    "persistRawObservations": true,
    "maximumRawAgeDays": 0,
    "useInRecentContext": true,
    "useInCompaction": true,
    "useInRetrieval": true
  },
  "provider": {
    "id": "custom-openai-compatible",
    "endpoint": "https://provider.example/v1/chat/completions",
    "model": "example-model",
    "secretReference": "env:WORLDMIND_API_KEY",
    "timeouts": {"connectMillis": 5000, "responseCompletionMillis": 30000},
    "retry": {"maximumAttempts": 3, "initialBackoffMillis": 250, "maximumBackoffMillis": 4000, "jitterRatio": 0.2},
    "circuitBreaker": {"failureThreshold": 5, "cooldownMillis": 30000},
    "generation": {"temperature": 0.4, "maxOutputTokens": 120}
  }
}
```

`enabled: false` cleanly disables integration. `chatBatching` and
`requestQueue` are required positive bounds. `maximumRawAgeDays: 0` retains raw
observations until an operator changes retention. `temperature` and `topP` are
mutually exclusive; `maxOutputTokens` is optional and provider-validated.

`secretReference` is an opaque external reference, currently supported as
`env:NAME`. It is not credential material. Do not put an API value in this JSON.

## Portable profile schema v1

```json
{
  "schemaVersion": 1,
  "characterName": "Aster",
  "personaFile": "persona.md",
  "administratorRulesFile": "rules.md",
  "loreFiles": ["lore/world.md"],
  "responseStyle": "calm and concise",
  "responseLengthLimit": 280,
  "chatNameColor": "light_purple"
}
```

`characterName`, persona, administrator rules, at least one lore file,
response style, and a positive response length are required. `chatNameColor`
defaults to `light_purple` and accepts one exact vanilla palette name. Profile
IDs are lowercase letters/digits/hyphens and are selected only by
`activeProfile`.

Use the parseable synthetic trees in [examples](examples/) as starting points.
Validation never contacts a provider.
