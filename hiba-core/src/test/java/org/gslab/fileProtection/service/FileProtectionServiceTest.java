package org.gslab.fileProtection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import org.gslab.fileProtection.exception.BatchFileProtectionException;
import org.gslab.fileProtection.exception.FileProtectionException;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.util.DatabaseHelper;
import org.gslab.fileProtection.util.HashUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for FileProtectionService with isolated test database
 */
class FileProtectionServiceTest {

    @TempDir
    Path tempDir;

    private FileProtectionService fileProtectionService;
    private DatabaseHelper databaseHelper;

    @BeforeEach
    void setUp() throws SQLException {
        databaseHelper = new DatabaseHelper();
        fileProtectionService = databaseHelper.createTestFileProtectionService();
        System.out.println(
                "Test environment setup completed with test database: " + DatabaseHelper.getTestDatabaseName());
    }

    @AfterEach
    void tearDown() {
        if (databaseHelper != null) {
            databaseHelper.tearDown();
            System.out.println("Test database cleaned up");
        }
    }

    @Test
    @DisplayName("Test File Protection Functionality")
    void testProtectFile() throws Exception {
        // Prepare test file
        File testFile = createTestFile("test-protect.txt", "This is a protection test file");

        // Execute file protection
        FileMetadata metadata = fileProtectionService.protectFile(testFile, "TestUser", "Test file protection");

        // Verify results
        assertThat(metadata).isNotNull();
        assertThat(metadata.getId()).isNotNull();
        assertThat(metadata.getFileName()).isEqualTo(testFile.getName());
        assertThat(metadata.getFileHash()).isNotNull();
        assertThat(metadata.getUploader()).isEqualTo("TestUser");
        assertThat(metadata.getDescription()).isEqualTo("Test file protection");

        System.out.printf("File protection test passed - Metadata ID: %s%n", metadata.getId());
    }

    @Test
    @DisplayName("Test Protection of Non-existent File")
    void testProtectNonExistentFile() {
        File nonExistentFile = new File(tempDir.toFile(), "non-existent.txt");
        assertThatThrownBy(() -> fileProtectionService.protectFile(nonExistentFile, "TestUser", "Test"))
                .isInstanceOf(FileProtectionException.class)
                .hasMessageContaining(nonExistentFile.getPath());

        System.out.println("Non-existent file protection test passed");
    }

    @Test
    @DisplayName("Test Protection of Already Protected File")
    void testProtectAlreadyProtectedFile() throws Exception {
        // First, protect a file
        File testFile = createTestFile("already-protected.txt", "Already protected content");
        FileMetadata firstMetadata = fileProtectionService.protectFile(testFile, "TestUser", "First protection");

        // Try to protect the same file again - should return existing metadata instead of throwing exception
        FileMetadata secondMetadata = fileProtectionService.protectFile(testFile, "TestUser", "Second protection");

        // Verify it returns the same metadata (same file hash)
        assertThat(secondMetadata).isNotNull();
        assertThat(secondMetadata.getFileHash()).isEqualTo(firstMetadata.getFileHash());
        assertThat(secondMetadata.getFileName()).isEqualTo("already-protected.txt");

        System.out.println("Already protected file test passed");
    }

    @Test
    @DisplayName("Test File Integrity Verification")
    void testVerifyFileIntegrityWithBlockchain() throws Exception {
        // Prepare and protect test file
        File testFile = createTestFile("test-verify.txt", "Integrity verification test file");
        FileMetadata protectMetadata =
                fileProtectionService.protectFileToBlockchain(testFile, "TestUser", "Test integrity verification");

        // Debug: Check what we got from protectFile
        System.out.printf("Protected file metadata: %s%n", protectMetadata.getId());
        System.out.printf("File name: %s%n", protectMetadata.getFileName());
        System.out.printf("File hash: %s%n", protectMetadata.getFileHash());

        // Verify file integrity
        boolean isValid = fileProtectionService.verifyFileIntegrityWithBlockchain(protectMetadata);

        // Print actual result for debugging
        System.out.printf("File integrity verification result: %s%n", isValid);

        // Verify results (mock service typically returns true)
        assertThat(isValid).isTrue();

        System.out.println("File integrity verification test passed");
    }

    @Test
    @DisplayName("Test Get All Protected Files")
    void testGetAllProtectedFiles() throws Exception {
        // Prepare multiple test files
        File file1 = createTestFile("test-all-1.txt", "File 1");
        File file2 = createTestFile("test-all-2.txt", "File 2");

        fileProtectionService.protectFile(file1, "User1", "Test file 1");
        fileProtectionService.protectFile(file2, "User2", "Test file 2");

        // Get all protected files
        Collection<FileMetadata> allFiles = fileProtectionService.getAllProtectedFiles();

        // Verify results
        assertThat(allFiles).isNotEmpty();
        assertThat(allFiles.size()).isGreaterThanOrEqualTo(2);

        System.out.printf("Get all protected files test passed, total %s files%n", allFiles.size());
    }

    // @Test
    // @DisplayName("Test Get Statistics")
    // void testGetStatistics() throws Exception {
    //    // Prepare test file
    //    File testFile = createTestFile("test-stats.txt", "Statistics test file");
    //    fileProtectionService.protectFile(testFile, "TestUser", "Test statistics");
    //
    //    // Get statistics
    //    Map<String, Object> stats = fileProtectionService.getStatistics();
    //
    //    // Verify results
    //    assertThat(stats).isNotEmpty();
    //    assertThat(stats).containsKeys("totalFiles", "totalTransactions", "successfulTransactions");
    //    assertThat((Integer) stats.get("totalFiles")).isGreaterThan(0);
    //    assertThat((Integer) stats.get("totalTransactions")).isGreaterThan(0);
    //
    //    System.out.printf("Statistics test passed - Stats: %s%n", stats);
    // }

    @Test
    @DisplayName("Test Create FileMetadata with full parameters")
    void testCreateFileMetadataWithFullParameters() throws Exception {
        // Prepare test file
        File testFile = createTestFile("test-metadata-full.txt", "Test metadata creation");

        // Create FileMetadata
        FileMetadata metadata = fileProtectionService.createFileMetadata(testFile, "TestUser", "Test description");

        // Verify results
        assertThat(metadata).isNotNull();
        assertThat(metadata.getId()).isNotNull();
        assertThat(metadata.getFileName()).isEqualTo(testFile.getName());
        assertThat(metadata.getFileHash()).isNotNull();
        assertThat(metadata.getUploader()).isEqualTo("TestUser");
        assertThat(metadata.getDescription()).isEqualTo("Test description");
        assertThat(metadata.getFileSize()).isEqualTo(testFile.length());
        assertThat(metadata.getFilePath()).isEqualTo(testFile.getAbsolutePath());
        assertThat(metadata.getUploadTime()).isNotNull();

        System.out.println("Create FileMetadata with full parameters test passed");
    }

    @Test
    @DisplayName("Test Create FileMetadata with minimal parameters")
    void testCreateFileMetadataWithMinimalParameters() throws Exception {
        // Prepare test file
        File testFile = createTestFile("test-metadata-minimal.txt", "Test minimal metadata");

        // Create FileMetadata with minimal parameters
        FileMetadata metadata = fileProtectionService.createFileMetadata(testFile, "TestUser");

        // Verify results
        assertThat(metadata).isNotNull();
        assertThat(metadata.getId()).isNotNull();
        assertThat(metadata.getFileName()).isEqualTo(testFile.getName());
        assertThat(metadata.getFileHash()).isNotNull();
        assertThat(metadata.getUploader()).isEqualTo("TestUser");
        assertThat(metadata.getDescription()).isNull();
        assertThat(metadata.getFileSize()).isEqualTo(testFile.length());
        assertThat(metadata.getFilePath()).isEqualTo(testFile.getAbsolutePath());
        assertThat(metadata.getUploadTime()).isNotNull();

        System.out.println("Create FileMetadata with minimal parameters test passed");
    }

    @Test
    @DisplayName("Test Compare FileMetadata - Name and Content (Same Name, Same Content)")
    void testCompareFileMetadataSameNameSameContent() throws Exception {
        // Create two files with same content
        File file1 = createTestFile("file1.txt", "Same content");
        File file2 = createTestFile("file1.txt", "Same content");

        // Create metadata for both files
        FileMetadata metadata1 = fileProtectionService.createFileMetadata(file1, "User1", "Description1");
        FileMetadata metadata2 = fileProtectionService.createFileMetadata(file2, "User2", "Description2");

        // Test name and content comparison (should be true as name and content are same)
        assertThat(fileProtectionService.compareFileMetadata(metadata1, metadata2))
                .isTrue();
        assertThat(metadata1.equals(metadata2)).isTrue();
    }

    @Test
    @DisplayName("Test Compare FileMetadata with Null Values")
    void testCompareFileMetadataWithNullValues() throws Exception {
        // Create valid metadata
        File testFile = createTestFile("test-null.txt", "Test content");
        FileMetadata validMetadata = fileProtectionService.createFileMetadata(testFile, "TestUser", "Description");

        // Test comparison method with null values
        assertThat(fileProtectionService.compareFileMetadata(null, validMetadata))
                .isFalse();
        assertThat(fileProtectionService.compareFileMetadata(validMetadata, null))
                .isFalse();
        assertThat(fileProtectionService.compareFileMetadata(null, null)).isFalse();

        System.out.println("Compare FileMetadata with null values test passed");
    }

    @Test
    @DisplayName("Test FileMetadata equals method directly")
    void testFileMetadataEqualsMethod() throws Exception {
        // Create two files with same content
        File file1 = createTestFile("equals1.txt", "Same content for equals test");
        File file2 = createTestFile("equals1.txt", "Same content for equals test");
        File file3 = createTestFile("equals3.txt", "Different content for equals test");

        // Create metadata
        FileMetadata metadata1 = fileProtectionService.createFileMetadata(file1, "User1", "Desc1");
        FileMetadata metadata2 = fileProtectionService.createFileMetadata(file2, "User2", "Desc2");
        FileMetadata metadata3 = fileProtectionService.createFileMetadata(file3, "User3", "Desc3");

        // Test equals method directly (should only compare hash)
        assertThat(metadata1.equals(metadata2)).isTrue(); // Same hash
        assertThat(metadata1.equals(metadata3)).isFalse(); // Different hash
        assertThat(metadata1.equals(null)).isFalse();
        assertThat(metadata1.equals(metadata1)).isTrue(); // Same object
        assertThat(metadata1.equals("not a metadata")).isFalse(); // Different class

        // Test hashCode consistency
        assertThat(metadata1.hashCode()).isEqualTo(metadata2.hashCode()); // Same hash should have same hashCode

        System.out.println("FileMetadata equals method test passed");
    }

    @Test
    @DisplayName("Test Create FileMetadata with Non-existent File")
    void testCreateFileMetadataWithNonExistentFile() {
        // Test with non-existent file
        File nonExistentFile = new File(tempDir.toFile(), "non-existent.txt");

        // Should throw FileProtectionException
        assertThatThrownBy(() -> fileProtectionService.createFileMetadata(nonExistentFile, "TestUser", "Description"))
                .isInstanceOf(FileProtectionException.class);

        assertThatThrownBy(() -> fileProtectionService.createFileMetadata(nonExistentFile, "TestUser"))
                .isInstanceOf(FileProtectionException.class);

        System.out.println("Create FileMetadata with non-existent file test passed");
    }

    @Test
    @DisplayName("Test Batch Protect Files - Success")
    void testBatchProtectFilesSuccess() throws Exception {
        // Prepare multiple test files
        File file1 = createTestFile("batch-test-1.txt", "Batch test content 1");
        File file2 = createTestFile("batch-test-2.txt", "Batch test content 2");
        File file3 = createTestFile("batch-test-3.txt", "Batch test content 3");

        List<File> fileList = Arrays.asList(file1, file2, file3);
        List<String> descriptionList = Arrays.asList("Description 1", "Description 2", "Description 3");

        // Execute batch protection
        List<FileMetadata> metadataList =
                fileProtectionService.batchProtectFile(fileList, "BatchTestUser", descriptionList);

        // Verify results
        assertThat(metadataList).isNotNull();
        assertThat(metadataList).hasSize(3);

        for (int i = 0; i < metadataList.size(); i++) {
            FileMetadata metadata = metadataList.get(i);
            File expectedFile = fileList.get(i);

            assertThat(metadata.getId()).isNotNull();
            assertThat(metadata.getFileName()).isEqualTo(expectedFile.getName());
            assertThat(metadata.getFileHash()).isNotNull();
            assertThat(metadata.getUploader()).isEqualTo("BatchTestUser");
            assertThat(metadata.getDescription()).isEqualTo(descriptionList.get(i));
        }

        System.out.printf("Batch protect files success test passed - Protected %d files%n", metadataList.size());
    }

    @Test
    @DisplayName("Test Batch Protect Files - Null File List")
    void testBatchProtectFilesNullFileList() {
        List<String> descriptionList = Arrays.asList("Description 1", "Description 2");

        assertThatThrownBy(() -> fileProtectionService.batchProtectFile(null, "TestUser", descriptionList))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File list cannot be null or empty");

        System.out.println("Batch protect files null file list test passed");
    }

    @Test
    @DisplayName("Test Batch Protect Files - Null Description List")
    void testBatchProtectFilesNullDescriptionList() throws Exception {
        File file1 = createTestFile("batch-null-desc-1.txt", "Content 1");
        List<File> fileList = List.of(file1);

        List<FileMetadata> fileMetadataList = fileProtectionService.batchProtectFile(fileList, "TestUser", null);

        assertThat(fileMetadataList).isNotNull();
        assertThat(fileMetadataList).hasSize(1);

        assertThat(fileMetadataList.get(0).getFileName()).isEqualTo("batch-null-desc-1.txt");
        assertThat(fileMetadataList.get(0).getDescription()).isEmpty();

        System.out.println("Batch protect files null description list test passed");
    }

    @Test
    @DisplayName("Test Batch Protect Files - Mismatched List Sizes")
    void testBatchProtectFilesMismatchedListSizes() throws Exception {
        File file1 = createTestFile("batch-mismatch-1.txt", "Content 1");
        File file2 = createTestFile("batch-mismatch-2.txt", "Content 2");

        List<File> fileList = Arrays.asList(file1, file2);
        List<String> descriptionList = List.of("Description 1"); // Only one description for two files

        List<FileMetadata> fileMetadataList =
                fileProtectionService.batchProtectFile(fileList, "TestUser", descriptionList);

        assertThat(fileMetadataList).isNotNull();
        assertThat(fileMetadataList).hasSize(2);

        assertThat(fileMetadataList.get(0).getFileName()).isEqualTo("batch-mismatch-1.txt");
        assertThat(fileMetadataList.get(0).getDescription()).isEqualTo("Description 1");

        assertThat(fileMetadataList.get(1).getFileName()).isEqualTo("batch-mismatch-2.txt");
        assertThat(fileMetadataList.get(1).getDescription()).isEmpty();

        System.out.println("Batch protect files mismatched list sizes test passed");
    }

    @Test
    @DisplayName("Test Batch Protect Files - Non-existent File")
    void testBatchProtectFilesNonExistentFile() throws Exception {
        File existingFile = createTestFile("batch-existing.txt", "Existing content");
        File nonExistentFile = new File(tempDir.toFile(), "non-existent-batch.txt");

        List<File> fileList = Arrays.asList(existingFile, nonExistentFile);
        List<String> descriptionList = Arrays.asList("Description 1", "Description 2");

        assertThatThrownBy(() -> fileProtectionService.batchProtectFile(fileList, "TestUser", descriptionList))
                .isInstanceOf(BatchFileProtectionException.class)
                .hasMessageContaining(nonExistentFile.getName());

        System.out.println("Batch protect files non-existent file test passed");
    }

    @Test
    @DisplayName("Test Batch Protect Files - Already Protected File")
    void testBatchProtectFilesAlreadyProtectedFile() throws Exception {
        // Create and protect first file
        File file1 = createTestFile("batch-already-1.txt", "Already protected content");
        FileMetadata firstMetadata = fileProtectionService.protectFile(file1, "TestUser", "Initial protection");

        // Create second file (not protected)
        File file2 = createTestFile("batch-already-2.txt", "New content");

        List<File> fileList = Arrays.asList(file1, file2);
        List<String> descriptionList = Arrays.asList("Description 1", "Description 2");

        // Should process both files - first one returns existing metadata, second one creates new
        List<FileMetadata> metadataList = fileProtectionService.batchProtectFile(fileList, "TestUser", descriptionList);

        assertThat(metadataList).hasSize(2);
        // First file should return existing metadata
        assertThat(metadataList.get(0).getFileHash()).isEqualTo(firstMetadata.getFileHash());
        // Second file should have new metadata
        assertThat(metadataList.get(1).getFileName()).isEqualTo("batch-already-2.txt");

        System.out.println("Batch protect files already protected file test passed");
    }

    @Test
    @DisplayName("Test Batch Protect Files - Empty Lists")
    void testBatchProtectFilesEmptyLists() {
        List<File> emptyFileList = new ArrayList<>();
        List<String> emptyDescriptionList = new ArrayList<>();

        // test batchProtectFile should raise an exception for empty file list
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> fileProtectionService.batchProtectFile(emptyFileList, "TestUser", emptyDescriptionList));

        assertThat(exception.getMessage()).isEqualTo("File list cannot be null or empty");

        System.out.println("Batch protect files empty lists test passed");
    }

    @Test
    @DisplayName("Test Batch Protect Files - Single File")
    void testBatchProtectFilesSingleFile() throws Exception {
        File singleFile = createTestFile("batch-single.txt", "Single file content");

        List<File> fileList = List.of(singleFile);
        List<String> descriptionList = List.of("Single file description");

        List<FileMetadata> metadataList =
                fileProtectionService.batchProtectFile(fileList, "SingleUser", descriptionList);

        assertThat(metadataList).isNotNull();
        assertThat(metadataList).hasSize(1);

        FileMetadata metadata = metadataList.get(0);
        assertThat(metadata.getFileName()).isEqualTo(singleFile.getName());
        assertThat(metadata.getUploader()).isEqualTo("SingleUser");
        assertThat(metadata.getDescription()).isEqualTo("Single file description");

        System.out.println("Batch protect files single file test passed");
    }

    @Test
    @DisplayName("Test Batch Protect Files - Large Batch")
    void testBatchProtectFilesLargeBatch() throws Exception {
        int batchSize = 10;
        List<File> fileList = new ArrayList<>();
        List<String> descriptionList = new ArrayList<>();

        // Create multiple test files
        for (int i = 1; i <= batchSize; i++) {
            File file = createTestFile("batch-large-" + i + ".txt", "Large batch content " + i);
            fileList.add(file);
            descriptionList.add("Large batch description " + i);
        }

        List<FileMetadata> metadataList =
                fileProtectionService.batchProtectFile(fileList, "LargeBatchUser", descriptionList);

        assertThat(metadataList).isNotNull();
        assertThat(metadataList).hasSize(batchSize);

        // Verify each transaction
        for (int i = 0; i < batchSize; i++) {
            FileMetadata metadata = metadataList.get(i);
            assertThat(metadata.getId()).isNotNull();
            assertThat(metadata.getFileName()).isEqualTo("batch-large-" + (i + 1) + ".txt");
            assertThat(metadata.getFileHash()).isNotNull();
            assertThat(metadata.getUploader()).isEqualTo("LargeBatchUser");
        }

        System.out.printf("Batch protect files large batch test passed - Protected %d files%n", batchSize);
    }

    @Test
    @DisplayName("Test Batch Protect Files - Verify All Files Can Be Found")
    void testBatchProtectFilesVerifyFindability() throws Exception {
        // Create test files
        File file1 = createTestFile("batch-find-1.txt", "Findable content 1");
        File file2 = createTestFile("batch-find-2.txt", "Findable content 2");

        List<File> fileList = Arrays.asList(file1, file2);
        List<String> descriptionList = Arrays.asList("Findable desc 1", "Findable desc 2");

        // Batch protect files
        List<FileMetadata> metadataList =
                fileProtectionService.batchProtectFile(fileList, "FindableUser", descriptionList);

        // Verify each file can be found by name
        for (int i = 0; i < fileList.size(); i++) {
            File file = fileList.get(i);
            FileMetadata originalMetadata = metadataList.get(i);

            Optional<FileMetadata> optionalMetadata =
                    fileProtectionService.findFileMetadataByFileHash(originalMetadata.getFileHash());

            FileMetadata metadata = optionalMetadata.orElse(null);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getId()).isEqualTo(originalMetadata.getId());
            assertThat(metadata.getFileName()).isEqualTo(file.getName());
            assertThat(metadata.getUploader()).isEqualTo("FindableUser");
        }

        System.out.println("Batch protect files verify findability test passed");
    }

    @Test
    @DisplayName("Test Blockchain-Only Protection - Success")
    void testProtectFileToBlockchainSuccess() throws Exception {
        // Prepare test file
        File testFile = createTestFile("blockchain-only.txt", "Blockchain only test content");

        // Execute blockchain-only protection
        FileMetadata metadata =
                fileProtectionService.protectFileToBlockchain(testFile, "BlockchainUser", "Blockchain only test");

        // Verify results
        assertThat(metadata).isNotNull();
        assertThat(metadata.getId()).isNotNull();
        assertThat(metadata.getFileName()).isEqualTo(testFile.getName());
        assertThat(metadata.getFileHash()).isNotNull();
        assertThat(metadata.getUploader()).isEqualTo("BlockchainUser");
        assertThat(metadata.getDescription()).isEqualTo("Blockchain only test");

        System.out.println("Blockchain-only protection test passed");
    }

    @Test
    @DisplayName("Test Blockchain-Only Protection - Already Exists")
    void testProtectFileToBlockchainAlreadyExists() throws Exception {
        // Create and protect file to blockchain first
        File testFile = createTestFile("blockchain-exists.txt", "Already on blockchain content");
        FileMetadata firstMetadata =
                fileProtectionService.protectFileToBlockchain(testFile, "FirstUser", "First protection");

        // Try to protect the same file again (should return existing metadata)
        FileMetadata secondMetadata =
                fileProtectionService.protectFileToBlockchain(testFile, "SecondUser", "Second protection");

        // Should return the existing metadata (same file hash)
        assertThat(secondMetadata).isNotNull();
        assertThat(secondMetadata.getFileHash()).isEqualTo(firstMetadata.getFileHash());

        System.out.println("Blockchain-only protection already exists test passed");
    }

    @Test
    @DisplayName("Test Database-Only Save Metadata - Success")
    void testSaveFileMetadataToDatabase() throws Exception {
        // Create test file and metadata
        File testFile = createTestFile("db-save-test.txt", "Database save test content");
        FileMetadata metadata = fileProtectionService.createFileMetadata(testFile, "DbUser", "Database save test");

        // Save metadata to database only
        fileProtectionService.saveFileMetadataToDatabase(metadata);

        // Verify it was saved by finding it
        Optional<FileMetadata> optionalFoundMetadata =
                fileProtectionService.findFileMetadataByFileHashFromDatabase(metadata.getFileHash());

        FileMetadata foundMetadata = optionalFoundMetadata.orElse(null);
        assertThat(foundMetadata).isNotNull();
        assertThat(foundMetadata.getUploader()).isEqualTo("DbUser");

        System.out.println("Database-only save metadata test passed");
    }

    @Test
    @DisplayName("Test Batch Blockchain-Only Protection - Success")
    void testBatchProtectFileToBlockchainSuccess() throws Exception {
        // Prepare multiple test files
        File file1 = createTestFile("batch-bc-1.txt", "Batch blockchain content 1");
        File file2 = createTestFile("batch-bc-2.txt", "Batch blockchain content 2");
        File file3 = createTestFile("batch-bc-3.txt", "Batch blockchain content 3");

        List<File> fileList = Arrays.asList(file1, file2, file3);
        List<String> descriptionList = Arrays.asList("BC Description 1", "BC Description 2", "BC Description 3");

        // Execute batch blockchain-only protection
        List<FileMetadata> metadataList =
                fileProtectionService.batchProtectFileToBlockchain(fileList, "BatchBcUser", descriptionList);

        // Verify results
        assertThat(metadataList).isNotNull();
        assertThat(metadataList).hasSize(3);

        for (int i = 0; i < metadataList.size(); i++) {
            FileMetadata metadata = metadataList.get(i);
            File expectedFile = fileList.get(i);

            assertThat(metadata.getId()).isNotNull();
            assertThat(metadata.getFileName()).isEqualTo(expectedFile.getName());
            assertThat(metadata.getFileHash()).isNotNull();
            assertThat(metadata.getUploader()).isEqualTo("BatchBcUser");
            assertThat(metadata.getDescription()).isEqualTo(descriptionList.get(i));
        }

        System.out.printf("Batch blockchain-only protection test passed - Protected %d files%n", metadataList.size());
    }

    @Test
    @DisplayName("Test Batch Database-Only Save - Success")
    void testBatchSaveFileMetadataToDatabase() throws Exception {
        // Create multiple test files and metadata
        File file1 = createTestFile("batch-db-1.txt", "Batch database content 1");
        File file2 = createTestFile("batch-db-2.txt", "Batch database content 2");

        List<FileMetadata> metadataList = new ArrayList<>();
        metadataList.add(fileProtectionService.createFileMetadata(file1, "BatchDbUser", "DB Description 1"));
        metadataList.add(fileProtectionService.createFileMetadata(file2, "BatchDbUser", "DB Description 2"));

        // Execute batch database-only save
        List<Boolean> resultList = fileProtectionService.batchSaveFileMetadataToDatabase(metadataList);
        assertThat(resultList).isNotNull();
        assertThat(resultList).hasSize(metadataList.size());
        for (Boolean result : resultList) {
            assertThat(result).isTrue();
        }

        // Verify both files can be found in database
        for (int i = 0; i < metadataList.size(); i++) {
            FileMetadata originalMetadata = metadataList.get(i);
            Optional<FileMetadata> optionalFoundMetadata =
                    fileProtectionService.findFileMetadataByFileHashFromDatabase(originalMetadata.getFileHash());

            FileMetadata foundMetadata = optionalFoundMetadata.orElse(null);

            assertThat(foundMetadata).isNotNull();
            assertThat(foundMetadata.getUploader()).isEqualTo("BatchDbUser");
            assertThat(foundMetadata.getDescription()).isEqualTo("DB Description " + (i + 1));
        }

        System.out.println("Batch database-only save test passed");
    }

    @Test
    @DisplayName("Test Mixed Workflow - Blockchain then Database")
    void testMixedWorkflowBlockchainThenDatabase() throws Exception {
        // Step 1: Protect files to blockchain only
        File file1 = createTestFile("mixed-1.txt", "Mixed workflow content 1");
        File file2 = createTestFile("mixed-2.txt", "Mixed workflow content 2");

        List<File> fileList = Arrays.asList(file1, file2);
        List<String> descriptionList = Arrays.asList("Mixed desc 1", "Mixed desc 2");

        List<FileMetadata> metadataList =
                fileProtectionService.batchProtectFileToBlockchain(fileList, "MixedUser", descriptionList);

        // Step 2: Save the same metadata to database
        fileProtectionService.batchSaveFileMetadataToDatabase(metadataList);

        // Step 3: Verify both files can be found in database
        for (FileMetadata metadata : metadataList) {
            Optional<FileMetadata> optionalFoundMetadata =
                    fileProtectionService.findFileMetadataByFileHashFromDatabase(metadata.getFileHash());

            FileMetadata foundMetadata = optionalFoundMetadata.orElse(null);

            assertThat(foundMetadata).isNotNull();
            assertThat(foundMetadata.getFileHash()).isEqualTo(metadata.getFileHash());
            assertThat(foundMetadata.getUploader()).isEqualTo("MixedUser");
        }

        System.out.println("Mixed workflow blockchain then database test passed");
    }

    @Test
    @DisplayName("Test Blockchain-Only vs Full Protection Comparison")
    void testBlockchainOnlyVsFullProtectionComparison() throws Exception {
        // Create two identical files
        File file1 = createTestFile("compare-bc-only.txt", "same content for comparison");
        File file2 = createTestFile("compare-full.txt", "same content for comparison");

        //// Protect one file blockchain-only
        FileMetadata bcOnlyMetadata =
                fileProtectionService.protectFileToBlockchain(file1, "CompareUser", "Blockchain only");

        // Verify blockchain-only file is NOT in database initially
        Optional<FileMetadata> optionalFoundBcOnlyMetadata =
                fileProtectionService.findFileMetadataByFileHashFromDatabase(bcOnlyMetadata.getFileHash());

        FileMetadata foundBcOnlyMetadata = optionalFoundBcOnlyMetadata.orElse(null);
        assertThat(foundBcOnlyMetadata).isNull(); // Should not be found in database

        // Protect another file with full protection
        FileMetadata fullMetadata = fileProtectionService.protectFile(file2, "CompareUser", "Full protection");

        // Compare the metadata - should not have same hash since fileName differs
        assertThat(bcOnlyMetadata.getFileHash()).isNotEqualTo(fullMetadata.getFileHash());

        // Verify full protection file IS in database
        Optional<FileMetadata> optionalFoundFullMetadata =
                fileProtectionService.findFileMetadataByFileHashFromDatabase(fullMetadata.getFileHash());

        FileMetadata foundFullMetadata = optionalFoundFullMetadata.orElse(null);

        assertThat(foundFullMetadata).isNotNull();
        assertThat(foundFullMetadata.getId()).isEqualTo(fullMetadata.getId());
        assertThat(foundFullMetadata.getUploader()).isEqualTo("CompareUser");
        assertThat(foundFullMetadata.getDescription()).isEqualTo("Full protection");
        assertThat(foundFullMetadata.getFileHash()).isEqualTo(fullMetadata.getFileHash());

        System.out.println("Blockchain-only vs full protection comparison test passed");
    }

    /**
     * Helper method for creating test files
     */
    private File createTestFile(String fileName, String content) throws IOException {
        File testFile = new File(tempDir.toFile(), fileName);
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write(content + "\n");
            writer.write("Creation time: " + new java.util.Date() + "\n");
            try {
                writer.write("File content hash: " + HashUtil.calculateStringHash(content) + "\n");
            } catch (Exception e) {
                writer.write("File content hash: Calculation failed\n");
            }
        }
        System.out.printf("Created test file: %s (size: %d bytes)%n", testFile.getName(), testFile.length());
        return testFile;
    }
}
