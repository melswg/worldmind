# Worldmind operator guide

## Install and start

Install the remapped Worldmind JAR and its required Fabric API modules on a
Fabric 1.20.1 server running Java 17. Dedicated-server players can use vanilla
clients: Worldmind has no client entrypoint or required client mod. For an
integrated world, the host instance contains the same common JAR because it also
hosts the logical server; this does not add a client UI or protocol.

Worldmind reads `config/worldmind/worldmind.json` and the selected profile at
`config/worldmind/profiles/<profile-id>/`. Start disabled or with a missing
secret safely; Minecraft remains available and status reports the safe disable
reason. Use [configuration](configuration.md) and run:

```text
/worldmind validate
/worldmind status
```

All `/worldmind` commands require permission level 4. `reload` validates and
atomically replaces a valid configuration; it does not turn invalid input into
a provider request.

## Chat behavior and cost controls

Worldmind observes every accepted public chat message without an invocation
command. An exact or likely address to the configured character name seals the
pending batch immediately. Ambient messages seal only at `maxMessages`,
`maxWaitMillis`, or `maxEstimatedInputCharacters`. The triggering message is
included. A direct address requires a response; ambient discussion permits the
character to reply or deliberately remain silent.

`requestQueue.capacity` bounds waiting conversation/compaction jobs and
`maxConcurrency` bounds active jobs. Timeouts, retry/backoff, and the circuit
breaker prevent provider trouble from blocking the Minecraft server thread.
Direct failures get one private fallback notification; ambient failures remain
diagnostic-only and do not spam public chat.

## Operations

- `/worldmind status` reports safe lifecycle, provider, queue, storage and
  extension status categories.
- `/worldmind validate` rechecks config and external secret availability without
  sending chat to a provider.
- `/worldmind reload` applies a newly valid config/profile using the current
  logical-server lifecycle.
- `/worldmind memory inspect`, `detail`, `export`, `delete`, and `reset` are
  operator-only. Deletion and full reset issue a short-lived confirmation token;
  reset/deletion is logical erasure, not a claim that old disk blocks are
  cryptographically wiped.

Exports are atomically published beneath `worldmind/exports/` in the world save.
Storage and configuration migrations create backups beneath their respective
Worldmind roots. Stop the server before copying a world for disaster recovery;
do not hand-edit an active SQLite DB.
