package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class FabricArtifactPackagingTest {
    @Test
    void remappedFabricArtifactContainsOnlyExpectedFirstPartyNestedArtifactsAndSqliteNatives() throws Exception {
        String artifactPath = System.getenv("WORLDMIND_REMAPPED_JAR");
        assumeTrue(artifactPath != null && !artifactPath.isBlank(),
            "Set WORLDMIND_REMAPPED_JAR to verify the built Fabric artifact.");
        try (ZipFile outer = new ZipFile(Path.of(artifactPath).toFile())) {
            String version = System.getProperty("worldmind.project.version", "0.1.0-SNAPSHOT");
            assertNotNull(outer.getEntry("META-INF/jars/core-" + version + ".jar"),
                "The Fabric artifact must nest the authoritative core.");
            assertNotNull(outer.getEntry("META-INF/jars/game-context-api-" + version + ".jar"),
                "The Fabric artifact must nest the public game-context API.");
            assertNotNull(outer.getEntry("META-INF/jars/game-context-runtime-" + version + ".jar"),
                "The Fabric artifact must nest the internal game-context runtime.");
            assertNotNull(outer.getEntry("META-INF/jars/sqlite-storage-" + version + ".jar"),
                "The Fabric artifact must nest SQLite storage.");
            assertNull(outer.stream().filter(entry -> entry.getName().contains("game-context-provider-example")).findFirst().orElse(null),
                "Worldmind must not bundle its independently built external-mod example.");
            assertNull(outer.stream().filter(entry -> entry.getName().contains("-dev.jar") || entry.getName().contains("-test")).findFirst().orElse(null),
                "Worldmind must not bundle development or test artifacts.");
            ZipEntry nestedJdbc = outer.getEntry("META-INF/jars/sqlite-jdbc-3.53.1.0.jar");
            assertNotNull(nestedJdbc, "The Fabric artifact must nest sqlite-jdbc.");
            Set<String> entries = new HashSet<>();
            try (InputStream nestedStream = outer.getInputStream(nestedJdbc); ZipInputStream nested = new ZipInputStream(nestedStream)) {
                for (ZipEntry entry = nested.getNextEntry(); entry != null; entry = nested.getNextEntry()) {
                    entries.add(entry.getName());
                }
            }
            assertNativeDirectories(entries, List.of(
                "org/sqlite/native/Windows/aarch64/",
                "org/sqlite/native/Windows/armv7/",
                "org/sqlite/native/Windows/x86/",
                "org/sqlite/native/Windows/x86_64/",
                "org/sqlite/native/Mac/aarch64/",
                "org/sqlite/native/Mac/x86_64/",
                "org/sqlite/native/Linux/aarch64/",
                "org/sqlite/native/Linux/arm/",
                "org/sqlite/native/Linux/armv6/",
                "org/sqlite/native/Linux/armv7/",
                "org/sqlite/native/Linux/ppc64/",
                "org/sqlite/native/Linux/riscv64/",
                "org/sqlite/native/Linux/x86/",
                "org/sqlite/native/Linux/x86_64/"
            ));
        }
    }

    private static void assertNativeDirectories(Set<String> entries, List<String> expectedDirectories) {
        for (String expectedDirectory : expectedDirectories) {
            assertTrue(entries.stream().anyMatch(name -> name.startsWith(expectedDirectory)),
                () -> "sqlite-jdbc must include native files under " + expectedDirectory);
        }
    }
}
