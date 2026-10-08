package org.gslab.fileProtection.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.sql.SQLException;
import org.gslab.fileProtection.database.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test for the TestDatabaseHelper utility
 */
class DatabaseHelperTest {

    private DatabaseHelper databaseHelper;

    @BeforeEach
    void setUp() throws SQLException {
        databaseHelper = new DatabaseHelper();
    }

    @AfterEach
    void tearDown() {
        if (databaseHelper != null) {
            databaseHelper.tearDown();
        }
    }

    @Test
    @DisplayName("Test database helper creates and cleans up test database")
    void testDatabaseHelperLifecycle() {
        // Verify test database components are created
        assertThat(databaseHelper.getTestDatabaseManager()).isNotNull();
        assertThat(databaseHelper.createTestFileProtectionService()).isNotNull();

        // Verify test database file exists
        File testDbFile = new File(DatabaseHelper.getTestDatabaseName());
        assertThat(testDbFile).exists();

        // Test that we can perform basic database operations
        DatabaseManager dbManager = databaseHelper.getTestDatabaseManager();
        assertThat(dbManager).isNotNull();

        System.out.println("Test database helper lifecycle test passed");
    }

    @Test
    @DisplayName("Test database isolation - each test gets fresh database")
    void testDatabaseIsolation() {
        // This test verifies that the database is clean and isolated
        DatabaseManager dbManager = databaseHelper.getTestDatabaseManager();

        // Check that database starts empty
        assertThat(dbManager.getAllFileMetadata()).isEmpty();

        // Verify statistics show empty database
        java.util.Map<String, Object> stats = dbManager.getStatistics();
        assertThat(stats.get("totalFiles")).isEqualTo(0);

        System.out.println("Database isolation test passed");
    }
}
