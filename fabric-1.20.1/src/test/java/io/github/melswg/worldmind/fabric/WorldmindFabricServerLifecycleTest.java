package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.melswg.worldmind.core.AuthoritativeInitializationPath;
import io.github.melswg.worldmind.core.WorldmindAuthoritativeRuntime;
import org.junit.jupiter.api.Test;

class WorldmindFabricServerLifecycleTest {
    @Test
    void dedicatedAndIntegratedServersUseTheSameAuthoritativeInitializationPath() {
        WorldmindAuthoritativeRuntime dedicatedRuntime = startLogicalServer();
        WorldmindAuthoritativeRuntime integratedRuntime = startLogicalServer();

        assertEquals(AuthoritativeInitializationPath.LOGICAL_SERVER, dedicatedRuntime.initializationPath());
        assertEquals(dedicatedRuntime.initializationPath(), integratedRuntime.initializationPath());
    }

    private WorldmindAuthoritativeRuntime startLogicalServer() {
        WorldmindFabricServerLifecycle lifecycle = new WorldmindFabricServerLifecycle();
        lifecycle.onServerStarted(null);
        return lifecycle.runtime();
    }
}
