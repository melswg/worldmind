# Release-candidate acceptance

Run the automated candidate gate on Java 17 with
`./gradlew clean releaseCandidateVerification -PreleaseVersion=0.1.0`. It stages
one remapped JAR beneath `build/release-candidate/v0.1.0/`, audits that exact
file, records its SHA-256 in `release-candidate-manifest.json`, then fails if a
later check changes it. Provider contracts use loopback fake HTTP and synthetic
credential references only.

The gate starts an isolated headless Fabric dedicated server with the staged JAR
and the pinned official Fabric launcher. It creates a temporary EULA fixture,
has no real provider configuration, and uses no graphical Minecraft client.

## Manual integrated-server smoke

Hosted CI cannot honestly drive a graphical Minecraft client. Before a public
release, an operator must use the exact staged JAR and its manifest SHA-256 in
an isolated Fabric 1.20.1 Java 17 single-player instance with a loopback fake
provider. Generate the test-only files with:

```text
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :release-verification:prepareManualIntegratedSmokeFixture
```

Copy only the generated `build/manual-integrated-smoke-fixture/config/worldmind/`
tree into the isolated instance's `config/` directory; do not overwrite a real
modpack configuration. In a second terminal run:

```text
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :release-verification:manualIntegratedFakeProvider
```

The provider is fixed to `127.0.0.1:38481`, does not make external requests,
does not log prompts or Authorization values, and returns only the synthetic
reply `Loopback smoke reply.`. Before launching the isolated instance, set the
synthetic—not real—environment value with `launchctl setenv WORLDMIND_API_KEY
worldmind-loopback-only`.
On macOS, use `launchctl setenv` and fully quit/reopen the launcher; after the
smoke, close the fake-provider terminal and run `launchctl unsetenv
WORLDMIND_API_KEY`.

Create a world, run `/worldmind status`, send `Aster, smoke test`, confirm the
fake reply and `integration=ENABLED`, then leave and reopen the same world.
Confirm that status remains enabled and storage is ready. Also confirm that no
client entrypoint, UI, or client protocol exists.

Record only version, commit, artifact SHA-256, Java/Minecraft/Fabric versions,
and pass/fail. Do not record a personal path, world name, player content, or
credential. `localIntegratedSmokeRecordCheck` supplies deterministic
logical-server parity evidence; it does not pretend to automate this graphical
operator step.

## Tag-only publication gate

Publication is gated by `.github/workflows/release.yml`, which triggers only on
pushed `v*.*.*` tags and on an inputless `workflow_dispatch` dry run. The
workflow keeps `contents: read` permissions for preflight and verify; only the
publish job receives `contents: write`, `id-token: write`, and `attestations:
write`, and it runs only when every gate is green and the protected `release`
environment approves.

Before a tag push is allowed to reach the publish job, `releaseTagPreflight`
requires the tag to be an annotated semantic version, to resolve to the
checked-out commit, and that commit to be an ancestor of `origin/main`; the tag
version must match the Gradle release build property, the rebuilt Fabric
metadata, and `docs/releases/v<version>.md`. The verify job then reruns the
full deterministic dry run:

```text
./gradlew clean releaseDryRun -PreleaseVersion=0.1.0 -PreleaseTag=v0.1.0
./gradlew releaseWorkflowContract
```

`releaseDryRun` rebuilds everything from the same committed HEAD, reruns every
scan/audit/candidate gate and the dedicated-server smoke, and stages only the
five public assets (`worldmind-fabric-1.20.1-0.1.0.jar`,
`worldmind-game-context-api-0.1.0.jar`,
`worldmind-game-context-api-0.1.0-sources.jar`,
`worldmind-v0.1.0-release-metadata.json`, `SHA256SUMS`) beneath
`build/release/v0.1.0/`. `releaseWorkflowContract` parses `release.yml` and
simulates branch, PR, schedule, dispatch, invalid-tag, disabled-variable,
unapproved-environment, and failed-gate contexts, proving that only a valid
pushed tag with the release enable variable, environment approval and a green
verify job can publish. No dry run creates a tag, a GitHub Release, a package
publication, or any real provider traffic.
