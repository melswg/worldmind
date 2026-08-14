package io.github.melswg.worldmind.releaseverification;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Deterministic, value-redacting release verification entry point. */
public final class ReleaseAuditMain {
    private static final List<Rule> SECRET_RULES = List.of(
        new Rule("provider-token", Pattern.compile("(?i)\\b(?:s" + "k-|rk_|ghp_|github_pat_)[a-z0-9_-]{12,}\\b")),
        new Rule("bearer-value", Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._-]{12,}")),
        new Rule("private-key", Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
        new Rule("credential-assignment", Pattern.compile("(?i)\\b(?:api[_-]?key|access[_-]?token|password)\\s*[:=]\\s*[\\\"'][^\\\"']{8,}")),
        new Rule("credential-uri", Pattern.compile("(?i)https?://[^\\s/@:]+:[^\\s/@]+@"))
    );
    private static final Pattern PERSONAL_PATH = Pattern.compile("(?:/" + "Users" + "/[^/\\s]+|[A-Za-z]:\\\\Users\\\\[^\\\\\\s]+)");
    private static final Set<AllowedFinding> SOURCE_ALLOWLIST = Set.of(
        new AllowedFinding("personal-absolute-path", "fabric-1.20.1/src/test/java/io/github/melswg/worldmind/fabric/WorldmindCommandTextTest.java")
    );
    private static final List<String> EXPECTED_NATIVE_DIRECTORIES = List.of(
        "org/sqlite/native/Windows/aarch64/", "org/sqlite/native/Windows/armv7/",
        "org/sqlite/native/Windows/x86/", "org/sqlite/native/Windows/x86_64/",
        "org/sqlite/native/Mac/aarch64/", "org/sqlite/native/Mac/x86_64/",
        "org/sqlite/native/Linux/aarch64/", "org/sqlite/native/Linux/arm/",
        "org/sqlite/native/Linux/armv6/", "org/sqlite/native/Linux/armv7/",
        "org/sqlite/native/Linux/ppc64/", "org/sqlite/native/Linux/riscv64/",
        "org/sqlite/native/Linux/x86/", "org/sqlite/native/Linux/x86_64/"
    );

    private ReleaseAuditMain() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 3) throw new IllegalArgumentException("Expected root, mode, and report path.");
        Path root = Path.of(arguments[0]).toRealPath();
        String mode = arguments[1];
        Path report = Path.of(arguments[2]).toAbsolutePath().normalize();
        List<Violation> violations = switch (mode) {
            case "source" -> scanSource(root);
            case "generated" -> scanGeneratedOutputs(root);
            case "artifact" -> {
                if (arguments.length != 5) throw new IllegalArgumentException("Artifact audit requires artifact path and version.");
                yield auditArtifact(Path.of(arguments[3]), arguments[4]);
            }
            default -> throw new IllegalArgumentException("Unknown release audit mode.");
        };
        writeReport(report, mode, violations);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Release " + mode + " audit failed with " + violations.size()
                + " finding(s); matched values are intentionally withheld.");
        }
    }

    private static List<Violation> scanSource(Path root) throws IOException, InterruptedException {
        Set<Path> paths = gitCandidateFiles(root);
        List<Violation> violations = new ArrayList<>();
        for (Path path : paths) {
            if (!Files.isRegularFile(path) || isBinary(path)) continue;
            String relative = normalize(root.relativize(path));
            String text = Files.readString(path, StandardCharsets.UTF_8);
            violations.addAll(secretViolations(relative, text));
            if (PERSONAL_PATH.matcher(text).find() && !SOURCE_ALLOWLIST.contains(new AllowedFinding("personal-absolute-path", relative))) {
                violations.add(new Violation("personal-absolute-path", relative));
            }
        }
        return distinct(violations);
    }

    private static List<Violation> scanGeneratedOutputs(Path root) throws IOException {
        List<Violation> violations = new ArrayList<>();
        try (var paths = Files.find(root, 8, (path, attributes) -> attributes.isRegularFile() && isGeneratedAuditTarget(root, path))) {
            for (Path path : paths.sorted().toList()) {
                if (isBinary(path)) continue;
                violations.addAll(secretViolations(normalize(root.relativize(path)), Files.readString(path, StandardCharsets.UTF_8)));
            }
        }
        return distinct(violations);
    }

    private static boolean isGeneratedAuditTarget(Path root, Path path) {
        Path relative = root.relativize(path);
        List<String> names = new ArrayList<>();
        for (Path segment : relative) names.add(segment.toString());
        return names.contains("build") && (names.contains("reports") || names.contains("release-candidate") || names.contains("release"));
    }

    private static List<Violation> auditArtifact(Path artifact, String version) throws IOException {
        List<Violation> violations = new ArrayList<>();
        if (!Files.isRegularFile(artifact)) return List.of(new Violation("missing-remapped-artifact", normalize(artifact)));
        try (ZipFile outer = new ZipFile(artifact.toFile())) {
            verifyOuterEntry(outer, "fabric.mod.json", violations);
            verifyOuterEntry(outer, "META-INF/LICENSE-worldmind", violations);
            List<String> firstParty = List.of("core", "game-context-api", "game-context-runtime", "sqlite-storage");
            for (String module : firstParty) {
                String path = "META-INF/jars/" + module + "-" + version + ".jar";
                ZipEntry nested = outer.getEntry(path);
                if (nested == null) {
                    violations.add(new Violation("missing-nested-first-party-jar", path));
                } else if (!nestedContainsLicense(outer, nested)) {
                    violations.add(new Violation("missing-nested-first-party-license", path));
                }
            }
            ZipEntry jdbc = outer.getEntry("META-INF/jars/sqlite-jdbc-3.53.1.0.jar");
            if (jdbc == null) {
                violations.add(new Violation("missing-sqlite-jdbc", "META-INF/jars/sqlite-jdbc-3.53.1.0.jar"));
            } else {
                Set<String> sqliteEntries = nestedEntries(outer, jdbc);
                for (String directory : EXPECTED_NATIVE_DIRECTORIES) {
                    if (sqliteEntries.stream().noneMatch(entry -> entry.startsWith(directory))) {
                        violations.add(new Violation("missing-sqlite-native", directory));
                    }
                }
                if (!sqliteEntries.contains("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE")
                    || !sqliteEntries.contains("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus")) {
                    violations.add(new Violation("missing-sqlite-license", jdbc.getName()));
                }
            }

            String metadata = readUtf8(outer, outer.getEntry("fabric.mod.json"));
            requireMetadata(metadata, "\"id\": \"worldmind\"", "metadata-id", violations);
            requireMetadata(metadata, "\"license\": \"Apache-2.0\"", "metadata-license", violations);
            requireMetadata(metadata, "\"environment\": \"*\"", "metadata-environment", violations);
            requireMetadata(metadata, "\"main\"", "metadata-main-entrypoint", violations);
            if (metadata.contains("\"client\"")) violations.add(new Violation("client-entrypoint", "fabric.mod.json"));

            Enumeration<? extends ZipEntry> entries = outer.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.contains("..") || name.startsWith("/")) violations.add(new Violation("unsafe-archive-path", name));
                if (name.startsWith("META-INF/jars/") && (name.contains("example") || name.contains("-dev") || name.contains("-test"))) {
                    violations.add(new Violation("forbidden-nested-artifact", name));
                }
                if (name.startsWith("io/github/melswg/worldmind/") && name.endsWith(".class")) {
                    verifyWorldmindClassBoundary(name, readBytes(outer, entry), violations);
                }
            }
        }
        return distinct(violations);
    }

    private static void verifyWorldmindClassBoundary(String path, byte[] bytes, List<Violation> violations) {
        String constantPool = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> forbidden = List.of(
            "net/minecraft/client", "ClientModInitializer", "EntityType", "Pathfinder", "WebSearch",
            "Embedding", "VectorDatabase", "text_to_speech", "speech_to_text", "tool_choice", "\\\"tools\\\""
        );
        for (String value : forbidden) {
            if (constantPool.contains(value)) violations.add(new Violation("out-of-scope-bytecode", path));
        }
    }

    private static boolean nestedContainsLicense(ZipFile outer, ZipEntry nested) throws IOException {
        return nestedEntries(outer, nested).contains("META-INF/LICENSE-worldmind");
    }

    private static Set<String> nestedEntries(ZipFile outer, ZipEntry nested) throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        try (InputStream input = outer.getInputStream(nested); ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) entries.add(entry.getName());
        }
        return entries;
    }

    private static void verifyOuterEntry(ZipFile zip, String path, List<Violation> violations) {
        if (zip.getEntry(path) == null) violations.add(new Violation("missing-required-artifact-entry", path));
    }

    private static void requireMetadata(String metadata, String required, String rule, List<Violation> violations) {
        if (!metadata.contains(required)) violations.add(new Violation(rule, "fabric.mod.json"));
    }

    private static Set<Path> gitCandidateFiles(Path root) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "ls-files", "-co", "--exclude-standard", "-z")
            .directory(root.toFile()).redirectErrorStream(true).start();
        byte[] bytes = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IOException("git ls-files did not complete successfully.");
        Set<Path> paths = new LinkedHashSet<>();
        for (String part : new String(bytes, StandardCharsets.UTF_8).split("\\u0000")) {
            if (!part.isEmpty()) paths.add(root.resolve(part).normalize());
        }
        return paths;
    }

    private static List<Violation> secretViolations(String path, String text) {
        List<Violation> violations = new ArrayList<>();
        for (Rule rule : SECRET_RULES) if (rule.pattern().matcher(text).find()) violations.add(new Violation(rule.id(), path));
        return violations;
    }

    static Set<String> matchingSecretRuleIds(String text) {
        Set<String> matching = new LinkedHashSet<>();
        for (Rule rule : SECRET_RULES) if (rule.pattern().matcher(text).find()) matching.add(rule.id());
        return Set.copyOf(matching);
    }

    private static boolean isBinary(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] sample = input.readNBytes(8_192);
            for (byte value : sample) if (value == 0) return true;
            return false;
        }
    }

    private static String readUtf8(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry == null) return "";
        return new String(readBytes(zip, entry), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private static List<Violation> distinct(List<Violation> violations) {
        return violations.stream().distinct().sorted(Comparator.comparing(Violation::rule).thenComparing(Violation::path)).toList();
    }

    private static void writeReport(Path report, String mode, List<Violation> violations) throws IOException {
        Files.createDirectories(report.getParent());
        StringBuilder json = new StringBuilder("{\"mode\":\"").append(mode).append("\",\"findingCount\":")
            .append(violations.size()).append(",\"findings\":[");
        for (int index = 0; index < violations.size(); index++) {
            Violation violation = violations.get(index);
            if (index > 0) json.append(',');
            json.append("{\"rule\":\"").append(escape(violation.rule())).append("\",\"path\":\"")
                .append(escape(violation.path())).append("\"}");
        }
        json.append("]}");
        Files.writeString(report, json, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }

    private record Rule(String id, Pattern pattern) { }
    private record Violation(String rule, String path) { }
    private record AllowedFinding(String rule, String path) { }
}
