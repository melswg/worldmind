package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class FabricArtifactPackagingTest {
    @Test
    void remappedFabricArtifactNestsSqliteJdbcWithWindowsMacAndLinuxNativeLibraries() throws Exception {
        String artifactPath = System.getenv("WORLDMIND_REMAPPED_JAR");
        assumeTrue(artifactPath != null && !artifactPath.isBlank(),
            "Set WORLDMIND_REMAPPED_JAR to verify the built Fabric artifact.");
        try (ZipFile outer = new ZipFile(Path.of(artifactPath).toFile())) {
            ZipEntry nestedJdbc = outer.getEntry("META-INF/jars/sqlite-jdbc-3.53.1.0.jar");
            assertNotNull(nestedJdbc, "The Fabric artifact must nest sqlite-jdbc.");
            Set<String> entries = new HashSet<>();
            try (InputStream nestedStream = outer.getInputStream(nestedJdbc); ZipInputStream nested = new ZipInputStream(nestedStream)) {
                for (ZipEntry entry = nested.getNextEntry(); entry != null; entry = nested.getNextEntry()) {
                    entries.add(entry.getName());
                }
            }
            assertTrue(entries.stream().anyMatch(name -> name.startsWith("org/sqlite/native/Windows/")));
            assertTrue(entries.stream().anyMatch(name -> name.startsWith("org/sqlite/native/Mac/")));
            assertTrue(entries.stream().anyMatch(name -> name.startsWith("org/sqlite/native/Linux/")));
        }
    }
}
