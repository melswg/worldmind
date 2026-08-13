package io.github.melswg.worldmind.storage.sqlite;

/** A database schema that this Worldmind version must not open or rewrite. */
public final class SqliteJournalSchemaException extends IllegalStateException {
    public SqliteJournalSchemaException(String message) {
        super(message);
    }
}
