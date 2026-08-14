# Built-in provider presets

Worldmind v0.1 uses bounded asynchronous Chat Completions requests. All required
tests use a loopback fake server; production operators choose and operate their
own provider account and spending limits.

| ID | Endpoint policy | Model policy |
|---|---|---|
| `custom-openai-compatible` | Required HTTPS endpoint; loopback HTTP is accepted only for tests | Provider-defined non-empty model |
| `openrouter` | Fixed `https://openrouter.ai/api/v1/chat/completions`; `endpoint` is forbidden | `author/model` or `author/model:variant`, maximum 256 characters |
| `deepseek-direct` | Fixed `https://api.deepseek.com/chat/completions`; `endpoint` is forbidden | `deepseek-v4-flash` or `deepseek-v4-pro`; maximum output 393216 |

Every preset requires `secretReference`, timeout, retry, circuit-breaker and
generation configuration. Store only a reference such as
`env:WORLDMIND_API_KEY`; set the value in the server environment or an external
secret provider. Use a separate, spending-limited deployment key. Never commit
the value, put it in a profile/MRPACK, paste it into chat, or copy it into logs,
memory, exports, or Wiki.

OpenRouter uses `max_completion_tokens` with explicit non-streaming requests.
Direct DeepSeek uses `max_tokens`, explicit non-streaming requests, and disables
thinking. Worldmind v0.1 does not enable streaming, function calling, tools, web
search, or provider-side browsing.

On connection/response failures, retry applies only to classified transient
failures. The circuit opens after its configured threshold, lets one probe run
after cooldown, and keeps Minecraft responsive throughout. Redirects are never
followed.
