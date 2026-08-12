package io.github.melswg.worldmind.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuthoritativeWorldmindInitializerTest {
    @Test
    void initializesTheLogicalServerRuntime() {
        WorldmindAuthoritativeRuntime runtime = new AuthoritativeWorldmindInitializer().initialize();

        assertEquals(AuthoritativeInitializationPath.LOGICAL_SERVER, runtime.initializationPath());
    }
}
