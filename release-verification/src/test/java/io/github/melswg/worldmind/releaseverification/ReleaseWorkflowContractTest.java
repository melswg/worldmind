package io.github.melswg.worldmind.releaseverification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Parser-based contract test for the tag-only release workflow.
 *
 * <p>The test parses the actual {@code .github/workflows/release.yml}, verifies
 * the immutable trigger/permission/pin structure, and simulates every
 * publication context. It proves that only a valid pushed annotated version
 * tag combined with the release enable variable, an approved protected
 * {@code release} environment and a successful verify job can publish.
 */
class ReleaseWorkflowContractTest {
    private static final Pattern ACTION_PIN = Pattern.compile("^[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)+@[0-9a-f]{40}$");
    private static final String ATTEST_PIN = "a2bbfa25375fe432b6a289bc6b6cd05ecd0c4c32";
    private static final String VARIABLE = "WORLDMIND_RELEASES_ENABLED";

    @TempDir Path temporaryDirectory;

    @Test
    void triggersAreOnlyVersionTagsAndInputlessDispatch() throws Exception {
        Map<?, ?> workflow = workflow();
        Map<?, ?> triggers = triggers(workflow);
        assertTrue(triggers.containsKey("push"), "push trigger must exist");
        assertFalse(triggers.containsKey("pull_request"), "pull_request trigger is forbidden");
        assertFalse(triggers.containsKey("schedule"), "schedule trigger is forbidden");
        assertTrue(triggers.containsKey("workflow_dispatch"), "workflow_dispatch dry-run trigger must exist");

        Map<?, ?> push = cast(triggers.get("push"));
        assertNull(push.get("branches"), "branch push must not trigger the release workflow");
        assertNotNull(push.get("tags"), "tag push must trigger the release workflow");
        assertEquals(List.of("v*.*.*"), push.get("tags"));

        Object dispatch = triggers.get("workflow_dispatch");
        if (dispatch instanceof Map<?, ?> dispatchMap) {
            assertNull(dispatchMap.get("inputs"), "manual dispatch must have no publish inputs");
        }
    }

    @Test
    void workflowLevelPermissionsAreReadOnlyAndEveryActionIsPinned() throws Exception {
        Map<?, ?> workflow = workflow();
        Map<?, ?> permissions = cast(workflow.get("permissions"));
        assertEquals(1, permissions.size());
        assertEquals("read", permissions.get("contents"));
        for (Object jobObject : jobs(workflow).values()) {
            Map<?, ?> job = cast(jobObject);
            for (Object stepObject : (List<?>) job.get("steps")) {
                Object uses = ((Map<?, ?>) stepObject).get("uses");
                if (uses != null) {
                    assertTrue(ACTION_PIN.matcher(uses.toString()).matches(),
                        "Every GitHub Action must be pinned to an immutable 40-hex commit SHA: " + uses);
                }
            }
        }
    }

    @Test
    void preflightRunsReleaseTagPreflightForPushedTagsOnly() throws Exception {
        Map<?, ?> preflight = cast(jobs(workflow()).get("preflight"));
        assertEquals("github.event_name == 'push'", preflight.get("if"));
        String run = stepsText(preflight);
        assertTrue(run.contains("releaseTagPreflight"), "preflight must invoke releaseTagPreflight");
        assertTrue(run.contains("-PreleaseTag"), "preflight must pass the tag");
        assertTrue(run.contains("-PreleaseVersion"), "preflight must pass the version");
    }

    @Test
    void verifyJobRunsTemurin17CleanDryRunAndContractWithoutSecrets() throws Exception {
        Map<?, ?> verify = cast(jobs(workflow()).get("verify"));
        assertEquals("ubuntu-latest", verify.get("runs-on"));
        assertNull(verify.get("permissions"), "verify job must inherit read-only workflow permissions");
        String verifyIf = String.valueOf(verify.get("if")).replaceAll("\\s+", " ").trim();
        assertTrue(verifyIf.contains("always()"),
            "verify job must not be skipped when its dependency is skipped on workflow_dispatch");
        assertTrue(verifyIf.contains("github.event_name == 'workflow_dispatch'"),
            "manual dispatch must still run the dry-run verify job");
        assertTrue(verifyIf.contains("needs.preflight.result == 'success'"),
            "tag pushes must gate verify on preflight success");
        assertTrue(evaluate(verifyIf, context("workflow_dispatch", "refs/heads/main", "skipped", "success", "true")),
            "dispatch context must run verify despite skipped preflight");
        assertTrue(evaluate(verifyIf, context("push", "refs/tags/v0.1.0", "success", "success", "true")),
            "green preflight must run verify");
        assertFalse(evaluate(verifyIf, context("push", "refs/tags/v0.1.0", "failure", "success", "true")),
            "failed preflight must skip verify");
        Map<?, ?> temurinStep = stepByUses(verify, "actions/setup-java@");
        Map<?, ?> temurinWith = cast(temurinStep.get("with"));
        assertEquals("temurin", temurinWith.get("distribution"));
        assertEquals("17", temurinWith.get("java-version"));
        String run = stepsText(verify);
        assertTrue(run.contains("clean releaseDryRun"), "verify job must run the clean release dry run");
        assertTrue(run.contains("releaseWorkflowContract"), "verify job must run the workflow contract test");
        assertFalse(run.contains("${{ secrets"), "verify job must not receive repository secrets");
        assertFalse(run.contains("${{ github.token }}"), "verify job must not receive a write token");
        Map<?, ?> uploadStep = stepByUses(verify, "actions/upload-artifact@");
        Map<?, ?> uploadWith = cast(uploadStep.get("with"));
        assertEquals(1, uploadWith.get("retention-days"), "dry-run assets must be short-lived");
        assertEquals("error", uploadWith.get("if-no-files-found"), "missing dry-run assets must fail");
        assertTrue(String.valueOf(uploadWith.get("name")).startsWith("worldmind-release-assets"),
            "dry-run assets must be uploaded under a stable name");
    }

    @Test
    void publishJobIsGatedOnTagVariableApprovalAndGreenGates() throws Exception {
        Map<?, ?> publish = cast(jobs(workflow()).get("publish"));
        assertEquals(List.of("preflight", "verify"), publish.get("needs"));
        assertEquals("release", publish.get("environment"), "publish job must require the protected release environment");
        Map<?, ?> permissions = cast(publish.get("permissions"));
        assertEquals(3, permissions.size());
        assertEquals("write", permissions.get("contents"));
        assertEquals("write", permissions.get("id-token"));
        assertEquals("write", permissions.get("attestations"));

        String publishIf = String.valueOf(publish.get("if")).replaceAll("\\s+", " ").trim();
        assertTrue(publishIf.contains("github.event_name == 'push'"));
        assertTrue(publishIf.contains("startsWith(github.ref, 'refs/tags/')"));
        assertTrue(publishIf.contains("needs.preflight.result == 'success'"));
        assertTrue(publishIf.contains("needs.verify.result == 'success'"));
        assertTrue(publishIf.contains("vars.WORLDMIND_RELEASES_ENABLED == 'true'"));

        List<Map<?, ?>> steps = cast(publish.get("steps"));
        assertNotNull(stepByUses(publish, "actions/checkout@"), "publish job must check out the tagged commit");
        assertTrue(stepsText(publish).contains("gh release create"), "publish job must create a GitHub Release");
        assertTrue(stepsText(publish).contains("--draft"), "publish job must create a draft first");
        assertTrue(stepsText(publish).contains("gh release upload"), "publish job must upload allowlisted assets");
        assertTrue(stepsText(publish).contains("gh release edit"), "publish job must finalize the release");
        assertTrue(stepsText(publish).contains("--draft=false"), "publish job must make the release public as the final step");
        assertTrue(stepsText(publish).contains("--notes-file \"docs/releases/v${RELEASE_VERSION}.md\""),
            "release body must come from the matching docs/releases notes file");
        assertFalse(stepsText(publish).contains("<(echo"), "release body must not be a placeholder echo");
        Map<?, ?> attestStep = stepByUses(publish, "actions/attest-build-provenance@");
        assertTrue(String.valueOf(attestStep.get("uses")).startsWith("actions/attest-build-provenance@" + ATTEST_PIN),
            "attestation action must be pinned to the confirmed immutable SHA");
        assertTrue(stepsText(publish).contains("${{ github.token }}"), "publish job must use only the ephemeral GITHUB_TOKEN");
        assertFalse(stepsText(publish).contains("${{ secrets"), "publish job must not use repository secrets");

        int draftIndex = stepIndex(publish, "--draft");
        int uploadIndex = stepIndex(publish, "gh release upload");
        int attestIndex = stepIndex(publish, "attest-build-provenance@" + ATTEST_PIN);
        int finalIndex = stepIndex(publish, "--draft=false");
        assertTrue(draftIndex < uploadIndex && uploadIndex < attestIndex && attestIndex < finalIndex,
            "draft must be created before uploads and made public only as the final step");
        assertEquals(steps.size() - 1, finalIndex, "making the release public must be the final step");
    }

    @Test
    void simulatedContextsProveOnlyOnePublicationPath() throws Exception {
        Map<?, ?> publish = cast(jobs(workflow()).get("publish"));
        String publishIf = String.valueOf(publish.get("if")).replaceAll("\\s+", " ").trim();

        assertPublish(publishIf, context("push", "refs/heads/main", "skipped", "skipped", "true"), true, false);
        assertPublish(publishIf, context("pull_request", "refs/pull/1/merge", "skipped", "skipped", "true"), true, false);
        assertPublish(publishIf, context("schedule", "refs/heads/main", "skipped", "skipped", "true"), true, false);
        assertPublish(publishIf, context("push", "refs/tags/v1.2.3-rc1", "failure", "success", "true"), true, false);
        assertPublish(publishIf, context("push", "refs/tags/v1.2", "failure", "success", "true"), true, false);
        assertPublish(publishIf, context("push", "refs/tags/v0.1.0", "success", "success", "true"), true, true);
        assertPublish(publishIf, context("push", "refs/tags/v0.1.0", "success", "success", "false"), true, false);
        assertPublish(publishIf, context("push", "refs/tags/v0.1.0", "success", "failure", "true"), true, false);
        assertPublish(publishIf, context("push", "refs/tags/v0.1.0", "failure", "success", "true"), true, false);
        assertPublish(publishIf, context("push", "refs/tags/v0.1.0", "success", "success", "true"), false, false);
        assertPublish(publishIf, context("workflow_dispatch", "refs/heads/main", "skipped", "success", "true"), true, false);

        assertFalse(tagPatternMatches("v1.2.3-rc1"));
        assertFalse(tagPatternMatches("v1.2"));
        assertFalse(tagPatternMatches("1.2.3"));
        assertFalse(tagPatternMatches("v0.1.0-beta"));
        assertFalse(tagPatternMatches("v01.2.3"));
        assertTrue(tagPatternMatches("v0.1.0"));
    }

    @Test
    void tagContractAcceptsOnlyMatchingStableVersions() throws Exception {
        Path root = temporaryDirectory.resolve("project");
        Files.createDirectories(root.resolve("docs/releases"));
        Files.createDirectories(root.resolve("fabric-1.20.1/build/resources/main"));
        Files.writeString(root.resolve("docs/releases/v0.1.0.md"), "# Worldmind v0.1.0\nRelease notes.\n");
        Files.writeString(root.resolve("fabric-1.20.1/build/resources/main/fabric.mod.json"),
            "{\"version\": \"0.1.0\"}\n");

        List<String> findings = new ArrayList<>();
        ReleasePreflightMain.run(root, "contract", "v0.1.0", "0.1.0", findings);
        assertTrue(findings.isEmpty(), "valid tag/version must pass the pure contract checks: " + findings);

        findings.clear();
        ReleasePreflightMain.run(root, "contract", "v0.1.0", "0.1.1", findings);
        assertFalse(findings.isEmpty(), "tag/version mismatch must fail the contract");

        findings.clear();
        ReleasePreflightMain.run(root, "contract", "v1.2", "1.2.0", findings);
        assertFalse(findings.isEmpty(), "malformed tag must fail the contract");

        findings.clear();
        ReleasePreflightMain.run(root, "contract", "v0.1.0", "0.1.0", findings);
        Files.delete(root.resolve("docs/releases/v0.1.0.md"));
        ReleasePreflightMain.run(root, "contract", "v0.1.0", "0.1.0", findings);
        assertTrue(findings.stream().anyMatch(finding -> finding.contains("docs/releases")),
            "missing release notes must fail the contract: " + findings);
    }

    private static boolean tagPatternMatches(String tag) {
        return Pattern.compile("^" + ReleasePreflightMain.TAG_PATTERN + "$").matcher(tag).matches();
    }

    private static void assertPublish(String publishIf, Map<String, String> context, boolean environmentApproved,
                                      boolean expected) {
        boolean gated = evaluate(publishIf, context);
        assertEquals(expected, gated && environmentApproved,
            "simulated context must publish exactly when every gate is satisfied: " + context);
    }

    private static Map<String, String> context(String event, String ref, String preflight, String verify, String variable) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("github.event_name", event);
        context.put("github.ref", ref);
        context.put("needs.preflight.result", preflight);
        context.put("needs.verify.result", verify);
        context.put("vars." + VARIABLE, variable);
        return context;
    }

    private static boolean evaluate(String expression, Map<String, String> context) {
        return parseOr(expression.trim(), context);
    }

    private static boolean parseOr(String expression, Map<String, String> context) {
        for (String part : splitTopLevel(expression, "||")) {
            if (parseAnd(part, context)) return true;
        }
        return false;
    }

    private static boolean parseAnd(String expression, Map<String, String> context) {
        for (String part : splitTopLevel(expression, "&&")) {
            if (!parseAtom(part.trim(), context)) return false;
        }
        return true;
    }

    private static boolean parseAtom(String atom, Map<String, String> context) {
        if (atom.startsWith("(") && atom.endsWith(")")) {
            return parseOr(atom.substring(1, atom.length() - 1), context);
        }
        if ("always()".equals(atom)) return true;
        if (atom.startsWith("startsWith(")) {
            int comma = atom.indexOf(',');
            String subject = atom.substring("startsWith(".length(), comma).trim();
            String prefix = atom.substring(comma + 1).trim().replace(")", "").replace("'", "");
            return String.valueOf(context.get(subject)).startsWith(prefix);
        }
        if (atom.contains("==")) {
            String[] parts = atom.split("==", 2);
            return parts[1].trim().replace("'", "").equals(context.get(parts[0].trim()));
        }
        if (atom.contains("!=")) {
            String[] parts = atom.split("!=", 2);
            return !parts[1].trim().replace("'", "").equals(context.get(parts[0].trim()));
        }
        throw new IllegalStateException("Unsupported if clause: " + atom);
    }

    private static List<String> splitTopLevel(String expression, String operator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (depth == 0 && expression.startsWith(operator, index)) {
                parts.add(expression.substring(start, index));
                index += operator.length() - 1;
                start = index + 1;
            }
        }
        parts.add(expression.substring(start));
        return parts;
    }

    private static Map<?, ?> workflow() throws IOException {
        Path root = Path.of(System.getProperty("worldmind.project.root", "."));
        Path workflow = root.resolve(".github/workflows/release.yml");
        assertTrue(Files.isRegularFile(workflow), "release.yml must exist at " + workflow);
        return cast(new Yaml().load(Files.newInputStream(workflow)));
    }

    private static Map<?, ?> triggers(Map<?, ?> workflow) {
        if (workflow.containsKey("on")) return cast(workflow.get("on"));
        return cast(workflow.get(Boolean.TRUE));
    }

    private static Map<?, ?> jobs(Map<?, ?> workflow) {
        return cast(workflow.get("jobs"));
    }

    private static Map<?, ?> stepByUses(Map<?, ?> job, String usesPrefix) {
        for (Object stepObject : (List<?>) job.get("steps")) {
            Map<?, ?> step = cast(stepObject);
            String uses = String.valueOf(step.get("uses"));
            if (uses.startsWith(usesPrefix)) return step;
        }
        throw new AssertionError("No step using " + usesPrefix + " in job");
    }

    private static String stepsText(Map<?, ?> job) {
        StringBuilder text = new StringBuilder();
        for (Object step : (List<?>) job.get("steps")) {
            Map<?, ?> map = cast(step);
            for (Object value : map.values()) text.append(value).append('\n');
        }
        return text.toString();
    }

    private static int stepIndex(Map<?, ?> job, String marker) {
        List<?> steps = (List<?>) job.get("steps");
        for (int index = 0; index < steps.size(); index++) {
            if (stepsText(Map.of("steps", List.of(steps.get(index)))).contains(marker)) return index;
        }
        throw new AssertionError("No step contains " + marker);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
