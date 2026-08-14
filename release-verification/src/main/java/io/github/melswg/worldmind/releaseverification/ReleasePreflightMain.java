package io.github.melswg.worldmind.releaseverification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic release tag preflight and dry-run tag contract.
 *
 * <p>Mode {@code contract} validates everything that does not require a real
 * pushed tag: tag format, tag/version consistency, release notes presence and
 * rebuilt Fabric metadata. Mode {@code preflight} additionally requires the
 * tag to be annotated, to resolve to the checked-out commit and to be an
 * ancestor of {@code origin/main}. No local tag is ever created by this class.
 */
public final class ReleasePreflightMain {
    static final String VERSION_PATTERN = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)";
    static final String TAG_PATTERN = "v" + VERSION_PATTERN;
    private static final Pattern TAG = Pattern.compile("^" + TAG_PATTERN + "$");
    private static final Pattern VERSION = Pattern.compile("^" + VERSION_PATTERN + "$");

    private ReleasePreflightMain() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 5) {
            throw new IllegalArgumentException("Expected root, mode, tag, version, and report path.");
        }
        Path root = Path.of(arguments[0]).toRealPath();
        String mode = arguments[1];
        String tag = arguments[2];
        String version = arguments[3];
        Path report = Path.of(arguments[4]).toAbsolutePath().normalize();
        List<String> findings = new ArrayList<>();
        run(root, mode, tag, version, findings);
        writeReport(report, mode, tag, version, findings);
        if (!findings.isEmpty()) {
            throw new IllegalStateException("Release tag preflight failed with " + findings.size() + " finding(s).");
        }
    }

    static void run(Path root, String mode, String tag, String version, List<String> findings) throws Exception {
        if (!TAG.matcher(tag).matches()) findings.add("tag does not match " + TAG_PATTERN);
        if (!VERSION.matcher(version).matches()) findings.add("version does not match " + VERSION_PATTERN);
        if (!("v" + version).equals(tag)) findings.add("tag version does not match the Gradle release build property");
        checkReleaseNotes(root, version, findings);
        checkFabricMetadata(root, version, findings);
        if ("preflight".equals(mode)) {
            checkAnnotated(root, tag, findings);
            checkResolvesToCheckedOutCommit(root, tag, findings);
            checkAncestorOfOriginMain(root, findings);
        } else if (!"contract".equals(mode)) {
            findings.add("unknown preflight mode " + mode);
        }
    }

    private static void checkReleaseNotes(Path root, String version, List<String> findings) throws IOException {
        Path releaseNotes = root.resolve("docs/releases/v" + version + ".md");
        if (!Files.isRegularFile(releaseNotes)) {
            findings.add("missing docs/releases/v" + version + ".md");
            return;
        }
        if (!Files.readString(releaseNotes, StandardCharsets.UTF_8).contains("Worldmind v" + version)) {
            findings.add("docs/releases/v" + version + ".md does not match the tag version");
        }
    }

    private static void checkFabricMetadata(Path root, String version, List<String> findings) throws IOException {
        Path metadata = root.resolve("fabric-1.20.1/build/resources/main/fabric.mod.json");
        if (!Files.isRegularFile(metadata)) {
            findings.add("rebuilt Fabric metadata is missing; run processResources first");
            return;
        }
        if (!Files.readString(metadata, StandardCharsets.UTF_8).contains("\"version\": \"" + version + "\"")) {
            findings.add("rebuilt Fabric metadata version does not match " + version);
        }
    }

    private static void checkAnnotated(Path root, String tag, List<String> findings) throws Exception {
        String type = git(root, "cat-file", "-t", tag).trim();
        if (!"tag".equals(type)) findings.add("tag is not annotated");
    }

    private static void checkResolvesToCheckedOutCommit(Path root, String tag, List<String> findings) throws Exception {
        String tagCommit = git(root, "rev-parse", tag + "^{commit}").trim();
        String head = git(root, "rev-parse", "--verify", "HEAD").trim();
        if (!tagCommit.equals(head)) findings.add("tag does not resolve to the checked-out commit");
    }

    private static void checkAncestorOfOriginMain(Path root, List<String> findings) throws Exception {
        if (!gitSucceeds(root, "merge-base", "--is-ancestor", "HEAD", "origin/main")) {
            findings.add("checked-out commit is not an ancestor of origin/main");
        }
    }

    private static String git(Path root, String... command) throws IOException, InterruptedException {
        List<String> invocation = new ArrayList<>(List.of("git"));
        invocation.addAll(List.of(command));
        Process process = new ProcessBuilder(invocation).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("Git preflight inspection failed: " + command[0]);
        return output;
    }

    private static boolean gitSucceeds(Path root, String... command) throws IOException, InterruptedException {
        List<String> invocation = new ArrayList<>(List.of("git"));
        invocation.addAll(List.of(command));
        Process process = new ProcessBuilder(invocation).directory(root.toFile()).redirectErrorStream(true).start();
        return process.waitFor() == 0;
    }

    private static void writeReport(Path report, String mode, String tag, String version, List<String> findings) throws IOException {
        Files.createDirectories(report.getParent());
        StringBuilder json = new StringBuilder("{\"mode\":\"").append(mode).append("\",\"tag\":\"").append(escape(tag))
            .append("\",\"version\":\"").append(escape(version)).append("\",\"findingCount\":").append(findings.size())
            .append(",\"findings\":[");
        for (int index = 0; index < findings.size(); index++) {
            if (index > 0) json.append(',');
            json.append('"').append(escape(findings.get(index))).append('"');
        }
        json.append("]}\n");
        Files.writeString(report, json, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
