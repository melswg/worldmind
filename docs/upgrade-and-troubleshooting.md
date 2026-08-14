# Upgrade and troubleshooting

## Supported migrations

Global configuration v1 and v2 migrate to v3 only after validation and after a
new backup bundle is safely published. Profiles remain independently versioned at
v1. Storage v1 migrates to v2 transactionally with a snapshot-aware SQLite
backup. Never overwrite a backup to force an upgrade.

If a global/profile schema is newer than Worldmind supports, leave it unchanged
and install a compatible mod version. If a migration fails, retain the original
configuration/database and inspect the safe status/validation category.

## Common operator outcomes

- `SECRET_MISSING` or `SECRET_UNREADABLE`: set the external secret value for the
  configured reference; do not edit a credential into JSON.
- Invalid endpoint/model/profile: run `/worldmind validate`, correct the named
  field, then `/worldmind reload`.
- Queue saturation: reduce chat volume/input size, increase only bounded limits
  intentionally, and investigate provider latency.
- Timeout/retry/circuit state: inspect `/worldmind status`; resolve provider
  availability before repeatedly reloading.
- SQLite startup error: confirm Java 17 and a supported OS/architecture, restore
  from a verified backup if storage is damaged, and do not copy a live WAL DB
  while the server is running.

Worldmind does not require a graphical client for dedicated-server operation.
