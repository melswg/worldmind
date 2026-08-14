package io.github.melswg.worldmind.fabric.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.EnabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.testkit.FakeSecretResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentationContractTest {
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]*]\\(([^)]+)\\)");
    private static final List<String> REQUIRED_DOCUMENTS = List.of(
        "README.md", "docs/operator-guide.md", "docs/configuration.md", "docs/providers.md",
        "docs/modpack-authoring.md", "docs/memory-and-privacy.md", "docs/development.md",
        "docs/upgrade-and-troubleshooting.md", "docs/compatibility.md", "docs/releases/v0.1.0.md"
    );

    @TempDir Path temporaryDirectory;

    @Test
    void guidesLinkToExistingLocalDocumentsAndStateTheNoClientBoundary() throws Exception {
        Path root = projectRoot();
        for (String relative : REQUIRED_DOCUMENTS) {
            Path document = root.resolve(relative);
            assertTrue(Files.isRegularFile(document), () -> "Missing required document " + relative);
            verifyLocalLinks(root, document);
        }
        String compatibility = Files.readString(root.resolve("docs/compatibility.md"));
        String releaseNotes = Files.readString(root.resolve("docs/releases/v0.1.0.md"));
        assertTrue(compatibility.contains("Minecraft **1.20.1**"));
        assertTrue(compatibility.contains("**Java 17**"));
        assertTrue(compatibility.contains("no Worldmind client mod"));
        assertTrue(compatibility.contains("AI entities"));
        assertTrue(releaseNotes.contains("Apache-2.0"));
        assertTrue(releaseNotes.contains("vector DB"));
        assertFalse(Files.readString(root.resolve("README.md")).contains("/" + "Users/"));
    }

    @Test
    void allBuiltInProviderExamplesLoadThroughTheActualStrictConfigurationLoader() throws Exception {
        for (String preset : List.of("custom-openai-compatible", "openrouter", "deepseek-direct")) {
            Path configuration = temporaryDirectory.resolve(preset);
            Files.createDirectories(configuration);
            copy(projectRoot().resolve("docs/examples/").resolve(preset).resolve("worldmind.json"),
                configuration.resolve("worldmind.json"));
            copyTree(projectRoot().resolve("docs/examples/profile"), configuration.resolve("profiles/oracle"));
            FakeSecretResolver secrets = new FakeSecretResolver().willResolveAs(SecretAvailability.AVAILABLE);
            assertTrue(new WorldmindStartupConfigurationLoader(configuration, secrets).load() instanceof EnabledWorldmindIntegration,
                () -> "Documentation example must load: " + preset);
        }
    }

    private static void verifyLocalLinks(Path root, Path document) throws IOException {
        Matcher matcher = MARKDOWN_LINK.matcher(Files.readString(document));
        while (matcher.find()) {
            String target = matcher.group(1);
            if (target.startsWith("https://") || target.startsWith("http://") || target.startsWith("mailto:")) continue;
            String local = target.split("#", 2)[0];
            if (local.isBlank()) continue;
            assertTrue(Files.exists(document.getParent().resolve(local).normalize()) || Files.exists(root.resolve(local).normalize()),
                () -> "Broken local documentation link " + target + " in " + root.relativize(document));
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else copy(path, destination);
            }
        }
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path projectRoot() { return Path.of(System.getProperty("worldmind.project.root")).toAbsolutePath().normalize(); }
}
