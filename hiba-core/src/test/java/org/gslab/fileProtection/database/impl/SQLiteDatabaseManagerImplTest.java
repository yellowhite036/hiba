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
import org.gslab.fileProtection.util.DatabaseHelper;
import org.gslab.fileProtection.util.HashUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for DatabaseManager with isolated test database
 */
public class SQLiteDatabaseManagerImplTest {

    @TempDir
    File tempDir;

    private DatabaseManager databaseManager;
    private DatabaseHelper databaseHelper;

    @BeforeEach
    public void setUp() throws SQLException {
        this.databaseHelper = new DatabaseHelper();
        this.databaseManager = databaseHelper.getTestDatabaseManager();
        System.out.println("DatabaseManagerTest setup completed with test database");
    }

    @AfterEach
    void tearDown() {
        if (databaseHelper != null) {
            databaseHelper.tearDown();
            System.out.println("DatabaseManagerTest database cleaned up");
        }
    }

    @Test
    public void testSaveAndRetrieveFileMetadata() throws IOException {
        // Create test file
        File testFile = new File(tempDir, "test_file.txt");
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content for SQLite persistence");
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
        metadata.setDescription("Test file for SQLite persistence");

        // Save to database
        this.databaseManager.saveFileMetadata(metadata);

        // Retrieve and verify
        FileMetadata retrieved = this.databaseManager.findFileMetadataById("test-file-id-001");
        assertNotNull(retrieved);
        assertEquals("test_file.txt", retrieved.getFileName());
        assertEquals("TestUser", retrieved.getUploader());
        assertEquals("Test file for SQLite persistence", retrieved.getDescription());
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

            this.databaseManager.saveFileMetadata(metadata);
        }

        // Get all metadata
        Collection<FileMetadata> allMetadata = this.databaseManager.getAllFileMetadata();
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

            this.databaseManager.saveFileMetadata(metadata);
        }

        // Find Alice's files
        List<FileMetadata> aliceFiles = this.databaseManager.findFileMetadataByUploader("Alice");
        assertEquals(2, aliceFiles.size());

        // Find Bob's files
        List<FileMetadata> bobFiles = this.databaseManager.findFileMetadataByUploader("Bob");
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
        assertFalse(this.databaseManager.isFileExists(metadata));

        // Save metadata to database
        this.databaseManager.saveFileMetadata(metadata);

        // Test file exists
        assertTrue(this.databaseManager.isFileExists(metadata));

        // Test file doesn't exist in database (but metadata object is valid)
        File anotherFile = new File(tempDir, "another_test.txt");
        try (FileWriter writer = new FileWriter(anotherFile)) {
            writer.write("Another test content");
        }
        FileMetadata nonExistentMetadata = createTestFileMetadata(anotherFile, "TestUser", "Non-existent");
        // This file exists physically but not saved to database, so should return false
        assertFalse(this.databaseManager.isFileExists(nonExistentMetadata));
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

        this.databaseManager.saveFileMetadata(metadata);

        // Get statistics
        Map<String, Object> stats = this.databaseManager.getStatistics();

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

        this.databaseManager.saveFileMetadata(metadata);

        // Verify file exists
        assertTrue(this.databaseManager.isFileExists(metadata));

        // Remove file
        boolean removed = this.databaseManager.removeFile("remove-test-001");
        assertTrue(removed);

        // Verify file is removed
        assertFalse(this.databaseManager.isFileExists(metadata));
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
