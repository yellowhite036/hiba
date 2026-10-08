package org.gslab.fileProtection.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Collection;
import org.gslab.fileProtection.BlockchainFileProtectManager;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.fileProtection.util.DatabaseHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockchainIntegrationTest {
    private DatabaseHelper databaseHelper;
    private FileProtectionService fileProtectionService;

    @BeforeEach
    public void setUp() throws SQLException {
        databaseHelper = new DatabaseHelper();
        fileProtectionService =
                BlockchainFileProtectManager.getFileProtectionService(databaseHelper.getTestDatabaseManager());
    }

    @AfterEach
    public void tearDown() {
        if (databaseHelper != null) {
            databaseHelper.tearDown();
        }
    }

    @Test
    @DisplayName("Test Real Blockchain Storage and Event Parsing")
    public void testRealBlockchainStorage() throws Exception {
        System.out.println("=== JUnit Blockchain Integration Test ===\n");

        // Create test file
        System.out.println("1. Creating test file...");
        String testContent = "JUnit blockchain test content - " + System.currentTimeMillis();
        File testFile = File.createTempFile("junit_blockchain_test", ".txt");
        Files.write(testFile.toPath(), testContent.getBytes());
        System.out.println("✓ Test file created: " + testFile.getName());

        try {
            // Store file on blockchain
            System.out.println("\n2. Storing file on blockchain...");
            long startTime = System.currentTimeMillis();

            FileMetadata metadata =
                    fileProtectionService.protectFile(testFile, "JUnitTestUser", "JUnit blockchain test");

            long endTime = System.currentTimeMillis();
            System.out.println("✓ File stored successfully!");
            System.out.println("  - FileMetadata ID: " + metadata.getId());
            System.out.println("  - File Name: " + metadata.getFileName());
            System.out.println("  - Protection time: " + (endTime - startTime) + "ms");

            // Assertions
            assertNotNull(metadata, "FileMetadata should not be null");
            assertNotNull(metadata.getId(), "FileMetadata ID should be set");
            assertNotNull(metadata.getFileHash(), "File hash should be set");
            assertNotNull(metadata.getFileName(), "File name should be present");

            // Verify storage
            System.out.println("\n3. Verifying stored file...");
            boolean isVerified = fileProtectionService.verifyFileIntegrityWithBlockchain(metadata);
            System.out.println("✓ File verification: " + (isVerified ? "PASSED" : "FAILED"));
            assertTrue(isVerified, "File verification should pass");

            // Test retrieval by file ID
            System.out.println("\n4. Testing file retrieval...");
            FileMetadata retrievedFile = fileProtectionService.findFileByIdFromDatabase(metadata.getId());
            if (retrievedFile != null) {
                System.out.println("✓ File retrieval working correctly!");
                System.out.println("  - Retrieved file ID: " + retrievedFile.getId());
                System.out.println(
                        "  - File hash matches: " + metadata.getFileHash().equals(retrievedFile.getFileHash()));

                assertEquals(metadata.getFileHash(), retrievedFile.getFileHash(), "File hashes should match");
            } else {
                fail("Could not retrieve file - retrieval may have failed");
            }

            System.out.println("\n✅ All blockchain integration tests PASSED!");
            System.out.println("🎉 Your Web3j implementation is working correctly!");

        } finally {
            // Cleanup
            testFile.delete();
        }
    }

    @Test
    @DisplayName("Test Service Initialization and Configuration")
    public void testServiceInitialization() {
        System.out.println("\n=== Service Initialization Test ===");

        assertDoesNotThrow(
                () -> {
                    FileProtectionService service = BlockchainFileProtectManager.getFileProtectionService(
                            databaseHelper.getTestDatabaseManager());
                    System.out.println("✓ FileProtectionService initialized successfully");

                    Collection<FileMetadata> files = service.getAllProtectedFiles();
                    System.out.println("✓ Service can retrieve protected files: " + files.size() + " files found");
                },
                "Service initialization should not throw exceptions");
    }
}
