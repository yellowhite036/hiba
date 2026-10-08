package org.gslab.fileProtection.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for HashUtil utility class
 */
class HashUtilTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Test file hash calculation")
    void testCalculateFileHash() throws IOException, NoSuchAlgorithmException {
        // Create test file
        File testFile = new File(tempDir.toFile(), "hash-test.txt");
        String content = "Test file hash calculation content";

        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write(content);
        }

        // Calculate file hash
        String hash = HashUtil.calculateFileHash(testFile);

        // Verify result
        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64); // SHA-256 generates 64 character hexadecimal string
        assertThat(hash).matches("[a-f0-9]+"); // Should only contain hexadecimal characters

        System.out.println("File hash test passed - Hash: " + hash);

        // Test same content generates same hash
        String hash2 = HashUtil.calculateFileHash(testFile);
        assertThat(hash2).isEqualTo(hash);

        System.out.println("Same file hash consistency test passed");
    }

    @Test
    @DisplayName("Test string hash calculation")
    void testCalculateStringHash() throws NoSuchAlgorithmException {
        String testString = "Test string hash calculation";

        // Calculate string hash
        String hash = HashUtil.calculateStringHash(testString);

        // Verify result
        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64); // SHA-256 generates 64 character hexadecimal string
        assertThat(hash).matches("[a-f0-9]+"); // Should only contain hexadecimal characters

        System.out.printf("String hash test passed - Hash: %s%n", hash);

        // Test same string generates same hash
        String hash2 = HashUtil.calculateStringHash(testString);
        assertThat(hash2).isEqualTo(hash);

        // Test different string generates different hash
        String differentHash = HashUtil.calculateStringHash("Different string");
        assertThat(differentHash).isNotEqualTo(hash);

        System.out.println("String hash consistency and uniqueness test passed");
    }

    @Test
    @DisplayName("Test unique ID generation")
    void testGenerateUniqueId() {
        // Generate multiple IDs
        String id1 = HashUtil.generateUniqueId();
        String id2 = HashUtil.generateUniqueId();
        String id3 = HashUtil.generateUniqueId();

        // Verify result
        assertThat(id1).isNotNull();
        assertThat(id2).isNotNull();
        assertThat(id3).isNotNull();

        // Verify each ID is unique
        assertThat(id1).isNotEqualTo(id2);
        assertThat(id2).isNotEqualTo(id3);
        assertThat(id1).isNotEqualTo(id3);

        // Verify ID format (UUID format)
        assertThat(id1).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        System.out.printf("Unique ID generation test passed - ID1: %s, ID2: %s, ID3: %s%n", id1, id2, id3);
    }

    @Test
    @DisplayName("Test empty file hash calculation")
    void testEmptyFileHash() throws IOException, NoSuchAlgorithmException {
        // Create empty file
        File emptyFile = new File(tempDir.toFile(), "empty-test.txt");
        emptyFile.createNewFile();

        // Calculate empty file hash
        String hash = HashUtil.calculateFileHash(emptyFile);

        // Verify result
        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64);

        System.out.printf("Empty file hash test passed - Hash: %s%n", hash);
    }

    @Test
    @DisplayName("Test large file hash calculation")
    void testLargeFileHash() throws IOException, NoSuchAlgorithmException {
        // Create larger test file
        File largeFile = new File(tempDir.toFile(), "large-test.txt");

        try (FileWriter writer = new FileWriter(largeFile)) {
            // Write repeated content to create larger file
            for (int i = 0; i < 1000; i++) {
                writer.write(
                        "This is line " + i + " test content for testing large file hash calculation functionality.\n");
            }
        }

        // Calculate large file hash
        String hash = HashUtil.calculateFileHash(largeFile);

        // Verify result
        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64);

        System.out.printf("Large file hash test passed - File size: %s bytes, Hash: %s%n", largeFile.length(), hash);
    }

    @Test
    @DisplayName("Test non-existent file hash calculation")
    void testNonExistentFileHash() throws Exception {
        File nonExistentFile = new File(tempDir.toFile(), "non-existent.txt");

        // Should return empty string for non-existent file
        String hash = HashUtil.calculateFileHash(nonExistentFile);
        assertThat(hash).isEmpty();

        System.out.println("Non-existent file hash test passed");
    }

    @Test
    @DisplayName("Test empty string hash calculation")
    void testEmptyStringHash() throws NoSuchAlgorithmException {
        String emptyString = "";

        // Calculate empty string hash
        String hash = HashUtil.calculateStringHash(emptyString);

        // Verify result
        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64);

        System.out.printf("Empty string hash test passed - Hash: %s%n", hash);
    }

    @Test
    @DisplayName("Test null string hash calculation")
    void testNullStringHash() throws NoSuchAlgorithmException {
        // Null string should return empty string hash

        String hash = HashUtil.calculateStringHash(null);

        assertThat(hash).isEqualTo("");

        System.out.println("Null string hash test passed");
    }
}
