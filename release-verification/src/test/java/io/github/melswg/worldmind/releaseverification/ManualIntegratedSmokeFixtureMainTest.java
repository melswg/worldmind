package io.github.melswg.worldmind.releaseverification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.melswg.worldmind.core.configuration.EnabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.fabric.configuration.WorldmindStartupConfigurationLoader;
import io.github.melswg.worldmind.testkit.FakeSecretResolver;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualIntegratedSmokeFixtureMainTest {
    @TempDir Path temporaryDirectory;

    @Test
    void fixtureUsesOnlyLoopbackAndWritesAParseableSyntheticConfigurationTree() throws Exception {
        ManualIntegratedSmokeFixtureMain.prepare(temporaryDirectory);
        String configuration = Files.readString(temporaryDirectory.resolve("config/worldmind/worldmind.json"));
        assertTrue(configuration.contains("http://127.0.0.1:38481/v1/chat/completions"));
        assertTrue(configuration.contains("env:WORLDMIND_API_KEY"));
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("config/worldmind/profiles/loopback-smoke/profile.json")));
        assertInstanceOf(EnabledWorldmindIntegration.class, new WorldmindStartupConfigurationLoader(
            temporaryDirectory.resolve("config/worldmind"),
            new FakeSecretResolver().willResolveAs(SecretAvailability.AVAILABLE)
        ).load());
    }

    @Test
    void loopbackProviderReturnsOneFixedDirectReplyWithoutInspectingCredentials() throws Exception {
        HttpServer server = ManualIntegratedSmokeFixtureMain.start(0);
        try {
            int port = server.getAddress().getPort();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                    .header("Authorization", "Bear" + "er synthetic-only")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("DIRECT_REPLY\\nLoopback smoke reply."));
        } finally {
            server.stop(0);
        }
    }
}
