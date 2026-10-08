package org.gslab.fileProtection.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.fileProtection.util.DatabaseHelper;
import org.gslab.fileProtection.util.HashUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * File Protection System Integration Test with isolated test database Simulates complete file protection workflow
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FileProtectionIntegrationTest {

    @TempDir
    Path tempDir;

    private FileProtectionService service;
    private File testFile;
    private FileMetadata testMetadata;
    private DatabaseHelper databaseHelper;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        databaseHelper = new DatabaseHelper();
        service = databaseHelper.createTestFileProtectionService();
        testFile = createTestFile();
        System.out.println("=== File Protection System Integration Test Initialization ===");
        System.out.println("Using test database: " + DatabaseHelper.getTestDatabaseName());
    }

    @AfterEach
    void tearDown() {
        if (databaseHelper != null) {
            databaseHelper.tearDown();
            System.out.println("=== Integration Test Database Cleaned Up ===");
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. Test File Protection Workflow")
    void testFileProtectionWorkflow() throws Exception {
        System.out.printf("1. Created test file: %s%n", testFile.getName());

        // Protect file
        System.out.println("2. Protecting file...");
        testMetadata = service.protectFile(testFile, "TestUser", "Test file protection functionality");

        // Verify protection results
        assertThat(testMetadata).isNotNull();
        assertThat(testMetadata.getId()).isNotNull();

        System.out.println("File protection successful!");
        System.out.printf("FileMetadata ID: %s%n", testMetadata.getId());

        // Verify metadata
        assertThat(testMetadata.getFileName()).isEqualTo(testFile.getName());
        assertThat(testMetadata.getUploader()).isEqualTo("TestUser");
        assertThat(testMetadata.getFileHash()).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("3. Test File Integrity Verification")
    void testFileIntegrityVerification() throws Exception {
        // Protect file first (if not already protected)
        if (testMetadata == null) {
            testMetadata = service.protectFile(testFile, "TestUser", "Test file protection functionality");
        }

        System.out.println("4. Verifying file integrity:");
        boolean isValid = service.verifyFileIntegrityWithBlockchain(testMetadata);

        // Mock blockchain service typically returns true
        assertThat(isValid).isTrue();
        System.out.println("Integrity verification result: Passed");
    }

    @Test
    @Order(4)
    @DisplayName("4. Test System Statistics")
    void testSystemStatistics() throws Exception {
        // Ensure at least one protected file exists
        if (testMetadata == null) {
            testMetadata = service.protectFile(testFile, "TestUser", "Test file protection functionality");
        }

        System.out.println("5. System statistics:");
        Map<String, Object> stats = service.getStatistics();

        assertThat(stats).isNotEmpty();
        assertThat(stats).containsKeys("totalFiles");

        stats.forEach((key, value) -> {
            System.out.printf("%s: %s%n", key, value);
            assertThat(value).isNotNull();
        });

        // Verify statistical data reasonableness
        assertThat((Integer) stats.get("totalFiles")).isGreaterThan(0);
    }

    @Test
    @Order(5)
    @DisplayName("5. Test Get All Protected Files")
    void testGetAllProtectedFiles() throws Exception {
        // Ensure at least one protected file exists
        if (testMetadata == null) {
            testMetadata = service.protectFile(testFile, "TestUser", "Test file protection functionality");
        }

        System.out.println("6. All protected files:");
        Collection<FileMetadata> allFiles = service.getAllProtectedFiles();

        assertThat(allFiles).isNotEmpty();

        allFiles.forEach(metadata -> {
            System.out.printf("- %s (ID: %s)%n", metadata.getFileName(), metadata.getId());
            assertThat(metadata.getId()).isNotNull();
            assertThat(metadata.getFileName()).isNotNull();
            assertThat(metadata.getFileHash()).isNotNull();
        });

        // Verify our test file is in the list
        boolean testFileFound =
                allFiles.stream().anyMatch(metadata -> metadata.getFileName().equals(testFile.getName()));
        assertThat(testFileFound).isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("6. Test Multiple File Protection")
    void testMultipleFileProtection() throws Exception {
        System.out.println("7. Testing multiple file protection:");

        // Create multiple test files
        File file1 = createTestFile("multi-test-1.txt", "First file content");
        File file2 = createTestFile("multi-test-2.txt", "Second file content");
        File file3 = createTestFile("multi-test-3.txt", "Third file content");

        // Protect multiple files
        FileMetadata metadata1 = service.protectFile(file1, "User1", "First file");
        FileMetadata metadata2 = service.protectFile(file2, "User2", "Second file");
        FileMetadata metadata3 = service.protectFile(file3, "User3", "Third file");

        // Verify all metadata objects are created
        assertThat(metadata1).isNotNull();
        assertThat(metadata2).isNotNull();
        assertThat(metadata3).isNotNull();

        // Files have been protected successfully
        System.out.println("All files protected with valid metadata");

        System.out.println("Multiple file protection test completed, protected 3 files in total");

        // Clean up test files
        file1.delete();
        file2.delete();
        file3.delete();
    }

    @Test
    @Order(7)
    @DisplayName("7. Test Duplicate File Protection")
    void testDuplicateFileProtection() throws Exception {
        System.out.println("8. Testing duplicate file protection:");

        File duplicateFile = createTestFile("duplicate-test.txt", "Duplicate file test");

        // First protection should succeed
        FileMetadata firstMetadata = service.protectFile(duplicateFile, "User1", "First protection");
        assertThat(firstMetadata).isNotNull();

        // Second protection should fail
        FileMetadata secondMetadata = service.protectFile(duplicateFile, "User1", "First protection");

        assertThat(secondMetadata).isNotNull();
        assertThat(secondMetadata.getId()).isEqualTo(firstMetadata.getId());
        assertThat(secondMetadata.getFileHash()).isEqualTo(firstMetadata.getFileHash());
        assertThat(secondMetadata.getFileName()).isEqualTo(firstMetadata.getFileName());
        assertThat(secondMetadata.getUploader()).isEqualTo(firstMetadata.getUploader());
        assertThat(secondMetadata.getDescription()).isEqualTo(firstMetadata.getDescription());

        System.out.println("Duplicate file protection test passed");

        // Clean up test file
        duplicateFile.delete();
    }

    /**
     * Helper method for creating test files
     */
    private File createTestFile() throws IOException {
        return createTestFile("test_file_" + System.currentTimeMillis() + ".txt", "Default test content");
    }

    private File createTestFile(String fileName, String content) throws IOException {
        File testFile = new File(tempDir.toFile(), fileName);
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write(content + "\n");
            writer.write("Used for verifying blockchain file protection system\n");
            writer.write("Creation time: " + new java.util.Date() + "\n");
            try {
                writer.write("File content hash: " + HashUtil.calculateStringHash(content));
            } catch (Exception e) {
                writer.write("File content hash: Calculation failed");
            }
        }
        return testFile;
    }
}
