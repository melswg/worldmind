package io.github.melswg.worldmind.core.administration;

/** Safe lifecycle state of one logical-server Worldmind owner. */
public enum RuntimeLifecycleState {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED
}
