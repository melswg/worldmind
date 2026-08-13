package io.github.melswg.worldmind.core.memory;

/** Only non-model authorities can promote a proposed record. */
public enum MemoryConfirmationAuthority {
    DETERMINISTIC_POLICY,
    AUTHORIZED_OPERATOR
}
