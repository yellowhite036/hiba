package org.gslab.fileProtection.database.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.gslab.fileProtection.database.DatabaseManager;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.util.HashUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for PostgresDatabaseManagerImpl
 * <p>
 * Note: These tests require a PostgreSQL database to be running. Set the environment variable
 * POSTGRES_TEST_ENABLED=true to run these tests.
 * <p>
 * Default test database connection: - Host: localhost - Port: 5432 - Database: blockchain_manager_test - Username:
 * postgres - Password: password
 * <p>
 * You can override these by setting environment variables: - POSTGRES_TEST_URL - POSTGRES_TEST_USERNAME -
 * POSTGRES_TEST_PASSWORD
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_ENABLED", matches = "true")
public class PostgresDatabaseManagerImplTest {

    @TempDir
    File tempDir;

    private DatabaseManager databaseManager;
    private String testDatabaseUrl;
    private String testUsername;
    private String testPassword;

    @BeforeEach
    public void setUp() throws SQLException {
        // Get test database connection parameters from environment or use defaults
        testDatabaseUrl = System.getenv()
                .getOrDefault("POSTGRES_TEST_URL", "jdbc:postgresql://localhost:5432/blockchain_manager");
        testUsername = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "user");
        testPassword = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "qqq123");

        // Create test database manager
        databaseManager = new PostgresDatabaseManagerImpl(testDatabaseUrl, testUsername, testPassword);

        // Clear any existing test data
        databaseManager.clearAllData();

        System.out.println("PostgreSQL DatabaseManagerTest setup completed with test database");
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            try {
                // Clear test data
                databaseManager.clearAllData();
                System.out.println("Cleared PostgreSQL test database data");
            } catch (Exception e) {
                System.err.println("Failed to clear PostgreSQL test database data: " + e.getMessage());
            }

            try {
                // Close database connection
                databaseManager.close();
                System.out.println("Closed PostgreSQL test database connection");
            } catch (SQLException e) {
                System.err.println("Failed to close PostgreSQL test database connection: " + e.getMessage());
            }
        }
    }

    @Test
    public void testSaveAndRetrieveFileMetadata() throws IOException {
        // Create test file
        File testFile = new File(tempDir, "test_file.txt");
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content for PostgreSQL persistence");
        }

        // Create file metadata
        FileMetadata metadata = new FileMetadata();
        metadata.setId("test-file-id-001");
        metadata.setFileName("test_file.txt");
        metadata.setFileHash("dummy-hash-123");
        metadata.setUploadTime(LocalDateTime.now());
        metadata.setUploader("TestUser");
        metadata.setFileSize(testFile.length());
        metadata.setFilePath(testFile.getAbsolutePath());
        metadata.setDescription("Test file for PostgreSQL persistence");

        // Save to database
        databaseManager.saveFileMetadata(metadata);

        // Retrieve and verify
        FileMetadata retrieved = databaseManager.findFileMetadataById("test-file-id-001");
        assertNotNull(retrieved);
        assertEquals("test_file.txt", retrieved.getFileName());
        assertEquals("TestUser", retrieved.getUploader());
        assertEquals("Test file for PostgreSQL persistence", retrieved.getDescription());
    }

    @Test
    public void testGetAllFileMetadata() throws IOException {
        // Create multiple test files
        for (int i = 1; i <= 3; i++) {
            File testFile = new File(tempDir, "test_file_" + i + ".txt");
            try (FileWriter writer = new FileWriter(testFile)) {
                writer.write("Test content " + i);
            }

            FileMetadata metadata = new FileMetadata();
            metadata.setId("test-file-id-" + String.format("%03d", i));
            metadata.setFileName("test_file_" + i + ".txt");
            metadata.setFileHash("dummy-hash-" + i);
            metadata.setUploadTime(LocalDateTime.now());
            metadata.setUploader("TestUser" + i);
            metadata.setFileSize(testFile.length());
            metadata.setFilePath(testFile.getAbsolutePath());
            metadata.setDescription("Test file " + i);

            databaseManager.saveFileMetadata(metadata);
        }

        // Get all metadata
        Collection<FileMetadata> allMetadata = databaseManager.getAllFileMetadata();
        assertTrue(allMetadata.size() >= 3);
    }

    @Test
    public void testFindFileMetadataByUploader() throws IOException {
        // Create test files for different uploaders
        String[] uploaders = {"Alice", "Bob", "Alice"};

        for (int i = 0; i < uploaders.length; i++) {
            File testFile = new File(tempDir, "uploader_test_" + i + ".txt");
            try (FileWriter writer = new FileWriter(testFile)) {
                writer.write("Test content for " + uploaders[i]);
            }

            FileMetadata metadata = new FileMetadata();
            metadata.setId("uploader-test-" + i);
            metadata.setFileName("uploader_test_" + i + ".txt");
            metadata.setFileHash("hash-" + i);
            metadata.setUploadTime(LocalDateTime.now());
            metadata.setUploader(uploaders[i]);
            metadata.setFileSize(testFile.length());
            metadata.setFilePath(testFile.getAbsolutePath());
            metadata.setDescription("Test file for " + uploaders[i]);

            databaseManager.saveFileMetadata(metadata);
        }

        // Find Alice's files
        List<FileMetadata> aliceFiles = databaseManager.findFileMetadataByUploader("Alice");
        assertEquals(2, aliceFiles.size());

        // Find Bob's files
        List<FileMetadata> bobFiles = databaseManager.findFileMetadataByUploader("Bob");
        assertEquals(1, bobFiles.size());
    }

    @Test
    public void testIsFileExists() throws IOException, NoSuchAlgorithmException {
        // Create test file
        File testFile = new File(tempDir, "existence_test.txt");
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content");
        }

        FileMetadata metadata = createTestFileMetadata(testFile, "TestUser", "Test file existence");

        // File doesn't exist in database yet
        assertFalse(databaseManager.isFileExists(metadata));

        // Save metadata to database
        databaseManager.saveFileMetadata(metadata);

        // Test file exists
        assertTrue(databaseManager.isFileExists(metadata));

        // Test file doesn't exist in database (but metadata object is valid)
        File anotherFile = new File(tempDir, "another_test.txt");
        try (FileWriter writer = new FileWriter(anotherFile)) {
            writer.write("Another test content");
        }
        FileMetadata nonExistentMetadata = createTestFileMetadata(anotherFile, "TestUser", "Non-existent");
        // This file exists physically but not saved to database, so should return false
        assertFalse(databaseManager.isFileExists(nonExistentMetadata));
    }

    @Test
    public void testGetStatistics() throws IOException {
        // Create test data
        File testFile = new File(tempDir, "stats_test.txt");
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content for statistics");
        }

        // Create file metadata for stats_test.txt
        FileMetadata metadata = new FileMetadata();
        metadata.setId("stats-test-001");
        metadata.setFileName("stats_test.txt");
        metadata.setFileHash("stats-hash");
        metadata.setUploadTime(LocalDateTime.now());
        metadata.setUploader("StatsUser");
        metadata.setFileSize(testFile.length());
        metadata.setFilePath(testFile.getAbsolutePath());
        metadata.setDescription("Test file for statistics");

        databaseManager.saveFileMetadata(metadata);

        // Get statistics
        Map<String, Object> stats = databaseManager.getStatistics();

        // Verify statistics contain expected keys
        assertTrue(stats.containsKey("totalFiles"));

        // Verify values are reasonable
        assertTrue((Integer) stats.get("totalFiles") >= 1);
    }

    @Test
    public void testRemoveFile() throws IOException, NoSuchAlgorithmException {
        // Create test file
        File testFile = new File(tempDir, "remove_test.txt");
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content for removal");
        }

        FileMetadata metadata = createTestFileMetadata(testFile, "RemoveUser", "Test file for removal");
        metadata.setId("remove-test-001"); // Override ID for consistent testing

        databaseManager.saveFileMetadata(metadata);

        // Verify file exists
        assertTrue(databaseManager.isFileExists(metadata));

        // Remove file
        boolean removed = databaseManager.removeFile("remove-test-001");
        assertTrue(removed);

        // Verify file is removed
        assertFalse(databaseManager.isFileExists(metadata));
    }

    /**
     * Helper method to create FileMetadata for testing
     */
    private FileMetadata createTestFileMetadata(File file, String uploader, String description)
            throws IOException, NoSuchAlgorithmException {
        FileMetadata metadata = new FileMetadata();
        metadata.setId(HashUtil.generateUniqueId());
        metadata.setFileName(file.getName());
        metadata.setFileHash(HashUtil.calculateFileHash(file));
        metadata.setUploadTime(LocalDateTime.now());
        metadata.setUploader(uploader);
        metadata.setDescription(description);
        metadata.setFileSize(file.length());
        metadata.setFilePath(file.getAbsolutePath());
        return metadata;
    }
}
