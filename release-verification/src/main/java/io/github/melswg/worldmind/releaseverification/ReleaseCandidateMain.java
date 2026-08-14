package io.github.melswg.worldmind.releaseverification;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates and verifies a path-independent manifest for one staged release candidate. */
public final class ReleaseCandidateMain {
    static final String BASELINE = "2e2e6643bf597d7e816e581e6e6e398afeedbfb0";
    private static final Pattern RELEASE_VERSION = Pattern.compile("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)");
    private static final Pattern SHA256 = Pattern.compile("\\\"sha256\\\":\\\"([0-9a-f]{64})\\\"");
    private static final List<String> EXPECTED_RELEASE_COMMITS = List.of(
        "ci: add Worldmind Java 17 release matrix",
        "security: scan and audit Worldmind release artifacts",
        "docs: publish Worldmind v0.1 operator and developer guides",
        "test: verify Worldmind v0.1 release candidate",
        "ci: gate Worldmind releases on version tags"
    );
    private static final List<Acceptance> ACCEPTANCE = List.of(
        new Acceptance("artifact", "releaseCandidateArtifactAudit"),
        new Acceptance("logical-server", "dedicatedServerSmoke,localIntegratedSmokeRecordCheck"),
        new Acceptance("providers-and-chat", "fabric-1.20.1:test,testkit:test"),
        new Acceptance("memory-privacy-migrations", "sqlite-storage:test,fabric-1.20.1:test"),
        new Acceptance("resilience-and-delivery", "core:test,testkit:test,fabric-1.20.1:test"),
        new Acceptance("extensions", "game-context-runtime:test,example-game-context-provider:build"),
        new Acceptance("release-controls", "releaseAudit,releaseCandidateAuthorship")
    );

    private ReleaseCandidateMain() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && "authorship".equals(arguments[1])) {
            verifyAuthorship(Path.of(arguments[0]).toRealPath());
            return;
        }
        if (arguments.length != 5) {
            throw new IllegalArgumentException("Expected root, create|verify, artifact, manifest, and release version.");
        }
        Path root = Path.of(arguments[0]).toRealPath();
        String mode = arguments[1];
        Path artifact = Path.of(arguments[2]).toAbsolutePath().normalize();
        Path manifest = Path.of(arguments[3]).toAbsolutePath().normalize();
        String version = arguments[4];
        if (!RELEASE_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Release version must be a stable MAJOR.MINOR.PATCH value.");
        }
        if (!Files.isRegularFile(artifact)) throw new IllegalStateException("Missing staged release artifact.");
        switch (mode) {
            case "create" -> create(root, artifact, manifest, version);
            case "verify" -> verify(artifact, manifest);
            default -> throw new IllegalArgumentException("Unknown release-candidate manifest mode.");
        }
    }

    private static void create(Path root, Path artifact, Path manifest, String version) throws Exception {
        verifyAuthorship(root);
        String commit = git(root, "rev-parse", "--verify", "HEAD").trim();
        String hash = sha256(artifact);
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"version\":\"")
            .append(version).append("\",\"commit\":\"").append(commit)
            .append("\",\"artifact\":\"").append(escape(artifact.getFileName().toString()))
            .append("\",\"sha256\":\"").append(hash).append("\",\"acceptance\":[");
        for (int index = 0; index < ACCEPTANCE.size(); index++) {
            Acceptance acceptance = ACCEPTANCE.get(index);
            if (index > 0) json.append(',');
            json.append("{\"id\":\"").append(acceptance.id()).append("\",\"evidence\":\"")
                .append(acceptance.evidence()).append("\"}");
        }
        json.append("]}\n");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, json, StandardCharsets.UTF_8);
    }

    private static void verify(Path artifact, Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) throw new IllegalStateException("Missing release-candidate manifest.");
        Matcher matcher = SHA256.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
        if (!matcher.find()) throw new IllegalStateException("Release-candidate manifest has no SHA-256.");
        if (!matcher.group(1).equals(sha256(artifact))) {
            throw new IllegalStateException("The staged release artifact changed after its manifest was created.");
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[32 * 1024];
                for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 17 must provide SHA-256.", exception);
        }
    }

    private static void verifyAuthorship(Path root) throws Exception {
        String output = git(root, "log", "--reverse", "--format=%an%x1f%ae%x1f%cn%x1f%ce%x1f%s", BASELINE + "..HEAD");
        List<String> subjects = new ArrayList<>();
        for (String row : output.split("\\R")) {
            if (row.isBlank()) continue;
            String[] parts = row.split("\\u001f", 5);
            if (parts.length != 5 || !"melswg".equals(parts[0]) || !"amkhadov.dev@gmail.com".equals(parts[1])
                || !"melswg".equals(parts[2]) || !"amkhadov.dev@gmail.com".equals(parts[3])) {
                throw new IllegalStateException("Release-candidate commits must retain repository-local author and committer identity.");
            }
            subjects.add(parts[4]);
        }
        if (subjects.size() < 3 || subjects.size() > EXPECTED_RELEASE_COMMITS.size()) {
            throw new IllegalStateException("Unexpected number of release-hardening commits since the approved baseline.");
        }
        for (int index = 0; index < subjects.size(); index++) {
            if (!EXPECTED_RELEASE_COMMITS.get(index).equals(subjects.get(index))) {
                throw new IllegalStateException("Release-hardening commit order or subject changed.");
            }
        }
    }

    private static String git(Path root, String... command) throws IOException, InterruptedException {
        List<String> invocation = new ArrayList<>(List.of("git"));
        invocation.addAll(List.of(command));
        Process process = new ProcessBuilder(invocation).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("Git release-candidate inspection failed.");
        return output;
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    private record Acceptance(String id, String evidence) { }
}
