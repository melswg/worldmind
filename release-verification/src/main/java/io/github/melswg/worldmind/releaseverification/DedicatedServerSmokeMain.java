package io.github.melswg.worldmind.releaseverification;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts a disposable headless Fabric server with only the staged candidate and required Fabric API modules. */
public final class DedicatedServerSmokeMain {
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration START_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private DedicatedServerSmokeMain() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 6) {
            throw new IllegalArgumentException("Expected staged artifact, launcher cache, server directory, report, version, and Fabric API modules.");
        }
        Path artifact = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path launcher = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path serverDirectory = Path.of(arguments[2]).toAbsolutePath().normalize();
        Path report = Path.of(arguments[3]).toAbsolutePath().normalize();
        String version = arguments[4];
        if (!Files.isRegularFile(artifact)) throw new IllegalStateException("Missing staged remapped candidate.");
        Properties launcherPin = launcherPin();
        acquirePinnedLauncher(launcher, URI.create(required(launcherPin, "url")), required(launcherPin, "sha256"));
        prepareServerDirectory(artifact, serverDirectory, List.of(arguments).subList(5, arguments.length));
        try {
            startAndStop(serverDirectory, launcher);
            Files.createDirectories(report.getParent());
            Files.writeString(report, "{\"schemaVersion\":1,\"version\":\"" + escape(version)
                + "\",\"artifact\":\"" + escape(artifact.getFileName().toString()) + "\",\"sha256\":\""
                + ReleaseCandidateMain.sha256(artifact) + "\",\"status\":\"passed\"}\n", StandardCharsets.UTF_8);
        } finally {
            deleteRecursively(serverDirectory);
        }
    }

    private static Properties launcherPin() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = DedicatedServerSmokeMain.class.getResourceAsStream("/fabric-server-launcher.properties")) {
            if (input == null) throw new IOException("Missing checked-in Fabric server launcher pin.");
            properties.load(input);
        }
        return properties;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Incomplete Fabric server launcher pin.");
        return value.trim();
    }

    private static void acquirePinnedLauncher(Path launcher, URI source, String expectedHash) throws Exception {
        if (Files.isRegularFile(launcher) && expectedHash.equals(ReleaseCandidateMain.sha256(launcher))) return;
        Files.createDirectories(launcher.getParent());
        Path temporary = launcher.resolveSibling(launcher.getFileName() + ".download");
        try {
            HttpResponse<Path> response = HttpClient.newBuilder().connectTimeout(DOWNLOAD_TIMEOUT).build().send(
                HttpRequest.newBuilder(source).timeout(DOWNLOAD_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofFile(temporary)
            );
            if (response.statusCode() != 200 || !expectedHash.equals(ReleaseCandidateMain.sha256(temporary))) {
                throw new IOException("The official Fabric server launcher did not match its checked-in SHA-256 pin.");
            }
            Files.move(temporary, launcher, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void prepareServerDirectory(Path artifact, Path serverDirectory, List<String> fabricApiModules) throws IOException {
        Files.createDirectories(serverDirectory.resolve("mods"));
        Files.writeString(serverDirectory.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(serverDirectory.resolve("server.properties"), "server-port=0\nonline-mode=false\nenable-rcon=false\n"
            + "level-name=release-smoke\nmotd=Worldmind release smoke\n", StandardCharsets.UTF_8);
        copy(artifact, serverDirectory.resolve("mods").resolve(artifact.getFileName()));
        for (String module : fabricApiModules) {
            Path source = Path.of(module);
            if (!Files.isRegularFile(source)) throw new IOException("Missing resolved Fabric API module for smoke test.");
            copy(source, serverDirectory.resolve("mods").resolve(source.getFileName()));
        }
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void startAndStop(Path serverDirectory, Path cachedLauncher) throws Exception {
        Path launcher = serverDirectory.resolve("fabric-server-launch.jar");
        copy(cachedLauncher, launcher);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process process = new ProcessBuilder(java.toString(), "-jar", launcher.getFileName().toString(), "nogui")
            .directory(serverDirectory.toFile()).redirectErrorStream(true).start();
        AtomicBoolean started = new AtomicBoolean();
        List<String> output = new CopyOnWriteArrayList<>();
        Thread outputReader = new Thread(() -> readOutput(process, started, output), "worldmind-dedicated-smoke-output");
        outputReader.setDaemon(true);
        outputReader.start();
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (!started.get() && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
        if (!started.get()) {
            process.destroyForcibly();
            process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            throw new IllegalStateException("Dedicated Fabric server did not reach its ready state: " + safeFailureSummary(output));
        }
        process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("Dedicated Fabric server did not stop cleanly.");
        }
        outputReader.join(STOP_TIMEOUT.toMillis());
    }

    private static void readOutput(Process process, AtomicBoolean started, List<String> output) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (output.size() < 8) output.add(line);
                if (line.contains("Done (") && line.contains("For help")) started.set(true);
            }
        } catch (IOException ignored) {
            // The process exit status remains authoritative; never echo server output into CI diagnostics.
        }
    }

    private static String safeFailureSummary(List<String> output) {
        if (output.isEmpty()) return "server exited without startup output";
        String summary = String.join(" | ", output).replaceAll("(?i)(?:token|secret|key|authorization)=[^\\s]+", "[REDACTED]")
            .replaceAll("/(?:" + "Users" + "|home)/[^\\s/]+", "<path>");
        return summary.length() <= 800 ? summary : summary.substring(0, 800);
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
