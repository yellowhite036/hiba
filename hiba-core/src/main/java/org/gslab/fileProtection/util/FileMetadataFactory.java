package org.gslab.fileProtection.util;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.exception.FileProtectionException;
import org.gslab.fileProtection.model.FileMetadata;

/**
 * Factory for creating FileMetadata objects
 * Extracted from FileManager to simplify architecture
 */
@Slf4j
public class FileMetadataFactory {

    private FileMetadataFactory() {
        // Utility class
    }

    /**
     * Create file metadata from file
     *
     * @param file        File object
     * @param uploader    Uploader name
     * @param description File description (can be null)
     * @return FileMetadata object
     * @throws FileProtectionException When file metadata creation fails
     */
    public static FileMetadata createFileMetadata(File file, String uploader, String description)
            throws FileProtectionException {

        validateFile(file);
        validateUploader(uploader);

        String fileName = file.getName();

        try {
            String fileId = HashUtil.generateUniqueId();
            LocalDateTime uploadTime = LocalDateTime.now();
            long fileSize = file.length();
            String fileHash = HashUtil.calculateFileHash(
                    file,
                    fileName,
                    Long.toString(fileSize),
                    HashUtil.getHashAlgorithm(),
                    Integer.toString(HashUtil.getBufferSize()));
            String filePath = file.getAbsolutePath();

            FileMetadata metadata =
                    new FileMetadata(fileId, fileName, fileHash, uploadTime, uploader, fileSize, filePath, description);

            log.debug("Created file metadata: {}", fileName);
            return metadata;

        } catch (IOException e) {
            log.error("Failed to read file during metadata creation: {}", fileName, e);
            throw new FileProtectionException(
                    "create-metadata", "file-reading", "Failed to read file: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            log.error("Hash algorithm not supported during metadata creation: {}", fileName, e);
            throw new FileProtectionException(
                    "create-metadata", "hash-calculation", "Hash algorithm not supported: " + e.getMessage(), e);
        }
    }

    /**
     * Create file metadata with minimal parameters
     */
    public static FileMetadata createFileMetadata(File file, String uploader) throws FileProtectionException {
        return createFileMetadata(file, uploader, null);
    }

    private static void validateFile(File file) throws FileProtectionException {
        if (file == null || !file.exists() || !file.isFile()) {
            throw new FileProtectionException(
                    "validate-file",
                    "file-validation",
                    "File does not exist or is not a valid file: " + (file != null ? file.getPath() : "null"));
        }
    }

    private static void validateUploader(String uploader) throws FileProtectionException {
        if (uploader == null || uploader.trim().isEmpty()) {
            throw new FileProtectionException(
                    "validate-uploader", "uploader-validation", "Uploader cannot be null or empty");
        }
    }
}
