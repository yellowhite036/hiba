package org.gslab.fileProtection.util;

import java.io.File;
import java.sql.SQLException;
import org.gslab.fileProtection.BlockchainFileProtectManager;
import org.gslab.fileProtection.database.DatabaseManager;
import org.gslab.fileProtection.database.impl.SQLiteDatabaseManagerImpl;
import org.gslab.fileProtection.service.FileProtectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test utility class for managing test database lifecycle
 */
public class DatabaseHelper {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseHelper.class);
    private static final String TEST_DB_NAME = "test.db";
    private static final String TEST_DB_URL =
            ConfigUtil.getProperty("blockchain.manager.db.url", "jdbc:sqlite:" + TEST_DB_NAME);

    private final DatabaseManager databaseManager;

    public DatabaseHelper() throws SQLException {
        databaseManager = new SQLiteDatabaseManagerImpl(TEST_DB_URL);
    }

    /**
     * Clean up test database after each test
     */
    public void tearDown() {
        try {
            // Clear all data from database
            databaseManager.clearAllData();
            logger.debug("Cleared test database data");
        } catch (Exception e) {
            logger.warn("Failed to clear test database data: {}", e.getMessage());
        }

        try {
            // Close database connection
            databaseManager.close();
            logger.debug("Closed test database connection");
        } catch (Exception e) {
            logger.warn("Failed to close test database connection: {}", e.getMessage());
        }

        // Delete test database file
        File testDbFile = new File(TEST_DB_NAME);
        if (testDbFile.exists()) {
            boolean deleted = testDbFile.delete();
            if (deleted) {
                logger.debug("Deleted test database file: {}", TEST_DB_NAME);
            } else {
                logger.warn("Failed to delete test database file: {}", TEST_DB_NAME);
            }
        }

        // Clean up WAL and SHM files
        String[] extensions = {"-wal", "-shm"};
        for (String ext : extensions) {
            File walFile = new File(TEST_DB_NAME + ext);
            if (walFile.exists()) {
                boolean deleted = walFile.delete();
                if (deleted) {
                    logger.debug("Deleted test database file: {}", walFile.getName());
                } else {
                    logger.warn("Failed to delete test database file: {}", walFile.getName());
                }
            }
        }
    }

    /**
     * Get test DatabaseManager instance
     */
    public DatabaseManager getTestDatabaseManager() {
        return this.databaseManager;
    }

    /**
     * Create FileProtectionService with test database
     */
    public FileProtectionService createTestFileProtectionService() {
        return BlockchainFileProtectManager.getFileProtectionService(this.databaseManager);
    }

    /**
     * Get test database file name
     */
    public static String getTestDatabaseName() {
        return TEST_DB_NAME;
    }

    /**
     * Get test database URL
     */
    public static String getTestDatabaseUrl() {
        return TEST_DB_URL;
    }
}
