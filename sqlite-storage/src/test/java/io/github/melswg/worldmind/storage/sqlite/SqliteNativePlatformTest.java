package io.github.melswg.worldmind.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteNativePlatformTest {
    @TempDir Path temporaryDirectory;

    @Test
    void bundledSqliteDriverCreatesWritesReadsAndReopensDatabaseOnThisPlatform() throws Exception {
        Path database = temporaryDirectory.resolve("platform-native.sqlite3");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE native_probe (value TEXT NOT NULL)");
            statement.execute("INSERT INTO native_probe(value) VALUES ('opened-by-bundled-sqlite')");
        }

        try (Connection reopened = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = reopened.createStatement();
             ResultSet result = statement.executeQuery("SELECT value FROM native_probe")) {
            assertFalse(result.isClosed());
            result.next();
            assertEquals("opened-by-bundled-sqlite", result.getString(1));
        }
    }
}
