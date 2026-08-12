package io.github.melswg.worldmind.core.configuration;

/**
 * Availability-only result used before a provider transport exists. No secret
 * value is carried through this Ticket 04 contract.
 */
public enum SecretAvailability {
    AVAILABLE,
    MISSING,
    UNREADABLE
}
