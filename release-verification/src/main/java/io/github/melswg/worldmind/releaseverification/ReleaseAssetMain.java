package io.github.melswg.worldmind.releaseverification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Constructs and verifies the exact public release asset allowlist.
 *
 * <p>The remapped Fabric JAR consumed here is the same immutable staged
 * candidate that release-candidate verification audited; its SHA-256 must
 * match the release-candidate manifest. The task writes
 * {@code worldmind-v<version>-release-metadata.json} and {@code SHA256SUMS},
 * then rejects any stale or extra file and any mismatched hash. No second
 * artifact is ever rebuilt downstream.
 */
public final class ReleaseAssetMain {
    private static final Pattern SHA256 = Pattern.compile("\\\"sha256\\\":\\\"([0-9a-f]{64})\\\"");
    private static final Pattern RELEASE_VERSION = Pattern.compile("^" + ReleasePreflightMain.VERSION_PATTERN + "$");

    private ReleaseAssetMain() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 10) {
            throw new IllegalArgumentException("Expected root, release directory, candidate manifest, version, tag,"
                + " java, minecraft, loader, yarn, and report path.");
        }
        Path root = Path.of(arguments[0]).toRealPath();
        Path releaseDirectory = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path candidateManifest = Path.of(arguments[2]).toAbsolutePath().normalize();
        String version = arguments[3];
        String tag = arguments[4];
        String javaVersion = arguments[5];
        String minecraft = arguments[6];
        String loader = arguments[7];
        String yarn = arguments[8];
        Path report = Path.of(arguments[9]).toAbsolutePath().normalize();
        if (!RELEASE_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Release version must be a stable MAJOR.MINOR.PATCH value.");
        }
        if (!("v" + version).equals(tag)) {
            throw new IllegalArgumentException("Release tag must match the release version.");
        }
        List<String> findings = new ArrayList<>();
        stageAndVerify(root, releaseDirectory, candidateManifest, version, tag, javaVersion, minecraft, loader, yarn, findings);
        writeReport(report, version, findings);
        if (!findings.isEmpty()) {
            throw new IllegalStateException("Release asset verification failed with " + findings.size() + " finding(s).");
        }
    }

    static void stageAndVerify(Path root, Path releaseDirectory, Path candidateManifest, String version, String tag,
                               String javaVersion, String minecraft, String loader, String yarn,
                               List<String> findings) throws Exception {
        if (!Files.isDirectory(releaseDirectory)) {
            findings.add("missing release directory " + releaseDirectory);
            return;
        }
        Map<String, Path> assets = listRegularFiles(releaseDirectory);
        List<String> expected = new ArrayList<>(List.of(
            "worldmind-fabric-1.20.1-" + version + ".jar",
            "worldmind-game-context-api-" + version + ".jar",
            "worldmind-game-context-api-" + version + "-sources.jar"
        ));
        if (!assets.keySet().equals(new LinkedHashSet<>(expected))) {
            findings.add("release directory must contain exactly the three public JAR assets, found: " + String.join(", ", assets.keySet()));
            return;
        }
        String stagedFabricHash = ReleaseCandidateMain.sha256(assets.get(expected.get(0)));
        if (!manifestSha256(candidateManifest).equals(stagedFabricHash)) {
            findings.add("staged Fabric JAR hash does not match the release-candidate manifest");
        }
        String commit = git(root, "rev-parse", "--verify", "HEAD").trim();
        String buildRun = System.getenv("GITHUB_RUN_ID");
        if (buildRun == null || buildRun.isBlank()) buildRun = "local";

        Map<String, String> hashes = new LinkedHashMap<>();
        for (String asset : expected) hashes.put(asset, ReleaseCandidateMain.sha256(assets.get(asset)));

        String metadataName = "worldmind-v" + version + "-release-metadata.json";
        String metadata = metadataJson(version, tag, commit, buildRun, javaVersion, minecraft, loader, yarn, hashes);
        Path metadataPath = releaseDirectory.resolve(metadataName);
        Files.writeString(metadataPath, metadata, StandardCharsets.UTF_8);

        List<String> checksumEntries = new ArrayList<>();
        for (String asset : expected) checksumEntries.add(hashes.get(asset) + "  " + asset);
        checksumEntries.add(ReleaseCandidateMain.sha256(metadataPath) + "  " + metadataName);
        Files.writeString(releaseDirectory.resolve("SHA256SUMS"), String.join("\n", checksumEntries) + "\n",
            StandardCharsets.UTF_8);

        Map<String, Path> finalAssets = listRegularFiles(releaseDirectory);
        if (!finalAssets.keySet().equals(new LinkedHashSet<>(List.of(
            expected.get(0), expected.get(1), expected.get(2), metadataName, "SHA256SUMS")))) {
            findings.add("release directory must contain exactly the five public assets after metadata generation, found: "
                + String.join(", ", finalAssets.keySet()));
            return;
        }
        for (Map.Entry<String, Path> entry : finalAssets.entrySet()) {
            if ("SHA256SUMS".equals(entry.getKey())) continue;
            String recorded = null;
            for (String line : checksumEntries) {
                if (line.endsWith("  " + entry.getKey())) {
                    recorded = line.substring(0, 64);
                    break;
                }
            }
            if (recorded == null || !recorded.equals(ReleaseCandidateMain.sha256(entry.getValue()))) {
                findings.add("hash mismatch for " + entry.getKey());
            }
        }
    }

    private static String metadataJson(String version, String tag, String commit, String buildRun,
                                       String javaVersion, String minecraft, String loader, String yarn,
                                       Map<String, String> hashes) {
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"tag\":\"").append(escape(tag))
            .append("\",\"commit\":\"").append(escape(commit)).append("\",\"buildRun\":\"").append(escape(buildRun))
            .append("\",\"java\":\"").append(escape(javaVersion)).append("\",\"minecraft\":\"").append(escape(minecraft))
            .append("\",\"fabricLoader\":\"").append(escape(loader)).append("\",\"yarn\":\"").append(escape(yarn))
            .append("\",\"version\":\"").append(escape(version)).append("\",\"assets\":[");
        List<String> names = new ArrayList<>(hashes.keySet());
        names.sort(Comparator.naturalOrder());
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) json.append(',');
            json.append("{\"filename\":\"").append(escape(names.get(index))).append("\",\"sha256\":\"")
                .append(hashes.get(names.get(index))).append("\"}");
        }
        json.append("],\"checksums\":\"SHA256SUMS\",\"checksumsSelfHashExcluded\":true}\n");
        return json.toString();
    }

    private static String manifestSha256(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) throw new IllegalStateException("Missing release-candidate manifest.");
        Matcher matcher = SHA256.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
        if (!matcher.find()) throw new IllegalStateException("Release-candidate manifest has no SHA-256.");
        return matcher.group(1);
    }

    private static Map<String, Path> listRegularFiles(Path directory) throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path path : stream.sorted().toList()) {
                if (Files.isRegularFile(path)) files.put(path.getFileName().toString(), path);
            }
        }
        return files;
    }

    private static String git(Path root, String... command) throws IOException, InterruptedException {
        List<String> invocation = new ArrayList<>(List.of("git"));
        invocation.addAll(List.of(command));
        Process process = new ProcessBuilder(invocation).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("Git release-asset inspection failed.");
        return output;
    }

    private static void writeReport(Path report, String version, List<String> findings) throws IOException {
        Files.createDirectories(report.getParent());
        StringBuilder json = new StringBuilder("{\"version\":\"").append(escape(version))
            .append("\",\"findingCount\":").append(findings.size()).append(",\"findings\":[");
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
