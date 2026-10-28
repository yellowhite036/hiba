package org.gslab.mService.executor;

import io.netty.channel.Channel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gslab.Executor;
import org.gslab.util.PathUtil;
import org.gslab.configuration.ServiceConfiguration;
import org.gslab.fileProtection.exception.BatchFileProtectionException;
import org.gslab.fileProtection.exception.BlockchainUploadException;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.model.RequestId;
import org.gslab.model.response.FileOperationResult;
import org.gslab.model.response.FileOperationResult.FileInfo;
import org.gslab.sdns.FileUploadInfo;
import org.gslab.sdns.HiBAInitial;
import org.gslab.tpm.TPMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executor for blockchain file protection service.
 *
 * Processes uploaded files and protects them using blockchain technology.
 * Supports batch processing and automatic cleanup.
 *
 * Input (from information Map):
 * - uploadedFiles: List<FileUploadInfo> - Files uploaded via HTTP
 * - keepFile: boolean - Whether to keep files after processing (default: false)
 * - description: String - Optional description for batch
 * - receiveTargetPath: String - Directory containing uploaded files
 *
 * Output (result Map):
 * - success: boolean - Overall success status
 * - processedFiles: List<Map> - Details of each processed file
 * - totalFiles: int - Total number of files processed
 * - successCount: int - Number of successfully protected files
 * - failureCount: int - Number of failed files
 * - batchId: String - Blockchain batch transaction ID (if available)
 */
public class BlockchainFileProtectExecutor extends Executor {
    private static final Logger logger = LoggerFactory.getLogger(BlockchainFileProtectExecutor.class);
    private final FileProtectionService fileProtectionService;
    private final TPMService tpmService;
    private final Path resourceDir;

    public BlockchainFileProtectExecutor() {
        this.fileProtectionService = HiBAInitial.fileProtectionService;
        this.tpmService = HiBAInitial.tpmService;
        this.resourceDir = Path.of("files");
        logger.atDebug().log("BlockchainFileProtectExecutor initialized");
    }

    public BlockchainFileProtectExecutor(ServiceConfiguration config) {
        this.resourceDir = Path.of(config.getMethods().optString("fileDirectory", "files"));
        this.fileProtectionService = HiBAInitial.fileProtectionService;
        this.tpmService = HiBAInitial.tpmService;
        logger.atDebug().log("BlockchainFileProtectExecutor initialized with config: {}", config);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> information, Channel brokerChannel) {
        RequestId requestId = (RequestId) information.get("requestId");
        MDC.put("requestId", requestId.value());
        logger.atInfo().log("=== BlockchainFileProtect execution started ===");
        logger.atDebug().log("Request information: {}", information.keySet());

        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            // 1. Validate FileProtectionService
            if (fileProtectionService == null) {
                logger.atError().log("FileProtectionService is not initialized");
                return createErrorResult("FileProtectionService not available");
            }

            // 2. Extract optional parameters
            boolean keepFile = extractBooleanParameter(information, "keepFile", false);
            String description = (String) information.getOrDefault("description", "File protection via HTTP upload");
            String receiveTargetPath = (String) information.get("receiveTargetPath");

            logger.atDebug().log(
                    "Parameters: keepFile={}, description={}, targetPath={}", keepFile, description, receiveTargetPath);

            // 3. Prefer prepared metadata if provided (broker-only execution)
            @SuppressWarnings("unchecked")
            List<FileMetadata> preparedMetadata = (List<FileMetadata>) information.get("preparedMetadata");

            if (preparedMetadata != null && !preparedMetadata.isEmpty()) {
                logger.atInfo().log("Processing prepared metadata: {} file(s)", preparedMetadata.size());
                Map<String, Object> processingResult = processPreparedMetadata(preparedMetadata, receiveTargetPath);

                long endTime = System.currentTimeMillis();
                processingResult.put("executionTimeMs", endTime - startTime);
                processingResult.put("receiveTargetPath", receiveTargetPath);
                return processingResult;
            }

            // 4. Extract uploaded files
            @SuppressWarnings("unchecked")
            List<FileUploadInfo> uploadedFiles = (List<FileUploadInfo>) information.get("uploadedFiles");

            if (uploadedFiles == null || uploadedFiles.isEmpty()) {
                logger.atWarn().log("No files uploaded in request");
                result.put("success", false);
                result.put("message", "No files to protect");
                result.put("totalFiles", 0);
                return result;
            }

            logger.atInfo().log("Processing {} uploaded file(s)", uploadedFiles.size());

            // 5. Process files and protect to blockchain
            Map<String, Object> processingResult = processAndProtectFiles(uploadedFiles, description, keepFile);

            // 5. Add execution metadata
            long endTime = System.currentTimeMillis();
            processingResult.put("executionTimeMs", endTime - startTime);
            processingResult.put("receiveTargetPath", receiveTargetPath);

            logger.atInfo().log("=== BlockchainFileProtect execution completed ===");
            logger.atInfo().log(
                    "Success: {}, Processed: {}/{}",
                    processingResult.get("success"),
                    processingResult.get("successCount"),
                    processingResult.get("totalFiles"));

            return processingResult;

        } catch (Exception e) {
            logger.atError().setCause(e).log("BlockchainFileProtect execution failed");
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("errorMessage", e.getMessage());
            result.put("executionTimeMs", System.currentTimeMillis() - startTime);
            return result;
        } finally {
            MDC.clear();
        }
    }

    /**
     * Process uploaded files and protect them using blockchain service.
     */
    private Map<String, Object> processAndProtectFiles(
            List<FileUploadInfo> uploadedFiles, String description, boolean keepFile) {

        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> processedFileDetails = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        try {
            // 1. Validate and prepare files for blockchain protection
            List<File> filesToProtect = new ArrayList<>();
            List<FileUploadInfo> validFiles = new ArrayList<>();

            for (FileUploadInfo fileInfo : uploadedFiles) {
                logger.atDebug().log("Validating file: {}", fileInfo.getOriginalFilename());

                Path filePath = Paths.get(fileInfo.getStoredPath());
                File file = filePath.toFile();

                if (!file.exists()) {
                    logger.atError().log("File not found: {}", fileInfo.getStoredPath());
                    processedFileDetails.add(createFileErrorResult(fileInfo, "File not found"));
                    failureCount++;
                    continue;
                }

                if (!file.canRead()) {
                    logger.atError().log("File not readable: {}", fileInfo.getStoredPath());
                    processedFileDetails.add(createFileErrorResult(fileInfo, "File not readable"));
                    failureCount++;
                    continue;
                }

                logger.atDebug().log("File validated: {} ({} bytes)", fileInfo.getOriginalFilename(), file.length());

                filesToProtect.add(file);
                validFiles.add(fileInfo);
            }

            if (filesToProtect.isEmpty()) {
                logger.atError().log("No valid files to protect");
                result.put("success", false);
                result.put("message", "No valid files found");
                result.put("processedFiles", processedFileDetails);
                result.put("totalFiles", uploadedFiles.size());
                result.put("successCount", 0);
                result.put("failureCount", failureCount);
                return result;
            }

            // 2. Batch protect files to blockchain
            logger.atInfo().log("Starting blockchain protection for {} files", filesToProtect.size());

            List<FileMetadata> protectedMetadata;
            try {
                protectedMetadata = fileProtectionService.batchProtectFileToBlockchain(
                        filesToProtect, tpmService.getEKFingerprint(), List.of(description));

                logger.atInfo().log("Blockchain protection completed: {} files protected", protectedMetadata.size());

            } catch (BatchFileProtectionException | BlockchainUploadException e) {
                logger.atError().setCause(e).log("Blockchain protection failed");

                // Mark all files as failed
                for (FileUploadInfo fileInfo : validFiles) {
                    processedFileDetails.add(
                            createFileErrorResult(fileInfo, "Blockchain protection failed: " + e.getMessage()));
                    failureCount++;
                }

                result.put("success", false);
                result.put("message", "Blockchain protection failed");
                result.put("error", e.getMessage());
                result.put("errorMessage", e.getMessage());
                result.put("processedFiles", processedFileDetails);
                result.put("totalFiles", uploadedFiles.size());
                result.put("successCount", 0);
                result.put("failureCount", failureCount);
                return result;
            }

            // 3. Match protected metadata with original file info
            for (int i = 0; i < validFiles.size() && i < protectedMetadata.size(); i++) {
                FileUploadInfo fileInfo = validFiles.get(i);
                FileMetadata metadata = protectedMetadata.get(i);

                Map<String, Object> fileResult = new HashMap<>();
                fileResult.put("filename", fileInfo.getOriginalFilename());
                fileResult.put("success", true);
                fileResult.put("fileSize", fileInfo.getFileSize());
                fileResult.put("contentType", fileInfo.getContentType());
                fileResult.put("storedPath", fileInfo.getStoredPath());

                // Blockchain metadata
                fileResult.put("fileHash", metadata.getFileHash());
                fileResult.put("protected", true);
                fileResult.put("timestamp", System.currentTimeMillis());

                processedFileDetails.add(fileResult);
                successCount++;

                logger.atInfo().log(
                        "File protected: {} -> hash: {}", fileInfo.getOriginalFilename(), metadata.getFileHash());
            }

            // 4. Cleanup files if requested
            List<String> fileToUploadResource = new ArrayList<>();

            if (!keepFile) {
                logger.atDebug().log("Cleaning up files (keepFile=false)");
                cleanupFiles(validFiles);
            } else {
                logger.atDebug().log("Files retained (keepFile=true)");

                processedFileDetails.forEach(file -> {
                    if ((boolean) file.get("success")) {
                        String fileName = (String) file.get("filename");

                        // copy file to resource directory for future use
                        Path sourcePath = Path.of((String) file.get("storedPath"));
                        Path destPath = PathUtil.buildPath(resourceDir, fileName);

                        try {
                            if (Files.exists(destPath)) {
                                logger.atWarn()
                                        .log(
                                                "File already exists, skipping copy: {}, source: {}, destination: {}",
                                                fileName,
                                                sourcePath.toAbsolutePath(),
                                                destPath.toAbsolutePath());
                            } else {
                                Files.copy(sourcePath, destPath);
                                logger.atDebug().log(
                                        "File copied successfully: {} -> {}",
                                        sourcePath.toAbsolutePath(),
                                        destPath.toAbsolutePath());
                            }
                        } catch (IOException e) {
                            logger.atError()
                                    .setCause(e)
                                    .log(
                                            "Failed to copy file: {}, source: {}, destination: {}",
                                            fileName,
                                            sourcePath.toAbsolutePath(),
                                            destPath.toAbsolutePath());
                        }

                        fileToUploadResource.add(fileName);
                    }
                });
            }

            // 5. Build final result
            result.put("success", true);
            result.put("message", "Files protected successfully");
            result.put("processedFiles", processedFileDetails);
            result.put("totalFiles", uploadedFiles.size());
            result.put("successCount", successCount);
            result.put("failureCount", failureCount);
            result.put("updateResource", fileToUploadResource);

            return result;

        } catch (Exception e) {
            logger.atError().setCause(e).log("Error processing files");
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("errorMessage", e.getMessage());
            result.put("processedFiles", processedFileDetails);
            result.put("totalFiles", uploadedFiles.size());
            result.put("successCount", successCount);
            result.put("failureCount", failureCount);
            return result;
        }
    }

    /**
     * Upload prepared metadata to blockchain without accessing local files.
     */
    private Map<String, Object> processPreparedMetadata(List<FileMetadata> preparedMetadata, String receiveTargetPath) {

        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> processedFileDetails = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        try {
            List<FileMetadata> uploadedMetadata =
                    fileProtectionService.batchUploadMetadataToBlockchain(preparedMetadata);

            for (FileMetadata metadata : uploadedMetadata) {
                Map<String, Object> fileResult = new HashMap<>();
                fileResult.put("filename", metadata.getFileName());
                fileResult.put("success", true);
                fileResult.put("fileSize", metadata.getFileSize());
                fileResult.put("storedPath", metadata.getFilePath());
                fileResult.put("fileHash", metadata.getFileHash());
                fileResult.put("protected", true);
                fileResult.put("timestamp", System.currentTimeMillis());

                processedFileDetails.add(fileResult);
                successCount++;
            }

            result.put("success", true);
            result.put("message", "Prepared metadata uploaded successfully");
            result.put("processedFiles", processedFileDetails);
            result.put("totalFiles", preparedMetadata.size());
            result.put("successCount", successCount);
            result.put("failureCount", failureCount);
            result.put("receiveTargetPath", receiveTargetPath);
            return result;

        } catch (BlockchainUploadException e) {
            logger.atError().setCause(e).log("Prepared metadata upload failed");

            for (FileMetadata metadata : preparedMetadata) {
                Map<String, Object> fileResult = new HashMap<>();
                fileResult.put("filename", metadata.getFileName());
                fileResult.put("success", false);
                fileResult.put("error", e.getMessage());
                fileResult.put("fileSize", metadata.getFileSize());
                fileResult.put("storedPath", metadata.getFilePath());
                processedFileDetails.add(fileResult);
                failureCount++;
            }

            result.put("success", false);
            result.put("message", "Prepared metadata upload failed");
            result.put("error", e.getMessage());
            result.put("errorMessage", e.getMessage());
            result.put("processedFiles", processedFileDetails);
            result.put("totalFiles", preparedMetadata.size());
            result.put("successCount", 0);
            result.put("failureCount", failureCount);
            result.put("receiveTargetPath", receiveTargetPath);
            return result;
        }
    }

    /**
     * Cleanup uploaded files after successful processing.
     */
    private void cleanupFiles(List<FileUploadInfo> files) {
        for (FileUploadInfo fileInfo : files) {
            try {
                Path filePath = Paths.get(fileInfo.getStoredPath());
                boolean deleted = Files.deleteIfExists(filePath);

                if (deleted) {
                    logger.atDebug().log("Deleted processed file: {}", fileInfo.getStoredPath());

                    // Try to cleanup empty parent directory
                    cleanupEmptyDirectory(filePath.getParent());
                } else {
                    logger.atWarn().log("File already deleted or not found: {}", fileInfo.getStoredPath());
                }

            } catch (Exception e) {
                logger.atError().setCause(e).log("Failed to cleanup file: {}", fileInfo.getStoredPath());
            }
        }
    }

    /**
     * Remove empty directory after file cleanup.
     */
    private void cleanupEmptyDirectory(Path directory) {
        try {
            if (directory == null) {
                return;
            }

            File dir = directory.toFile();
            if (dir.isDirectory() && dir.list() != null && dir.list().length == 0) {
                boolean deleted = dir.delete();
                if (deleted) {
                    logger.atDebug().log("Deleted empty directory: {}", directory);
                }
            }
        } catch (Exception e) {
            logger.atWarn().setCause(e).log("Failed to cleanup directory: {}", directory);
        }
    }

    /**
     * Create error result for a single file.
     */
    private Map<String, Object> createFileErrorResult(FileUploadInfo fileInfo, String errorMessage) {
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("filename", fileInfo.getOriginalFilename());
        errorResult.put("success", false);
        errorResult.put("error", errorMessage);
        errorResult.put("fileSize", fileInfo.getFileSize());
        errorResult.put("storedPath", fileInfo.getStoredPath());
        return errorResult;
    }

    /**
     * Create error result for overall execution.
     */
    private Map<String, Object> createErrorResult(String errorMessage) {
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("success", false);
        errorResult.put("error", errorMessage);
        errorResult.put("totalFiles", 0);
        errorResult.put("successCount", 0);
        errorResult.put("failureCount", 0);
        return errorResult;
    }

    /**
     * Build unified FileOperationResult format (new standardized response).
     *
     * This method provides the clean, standardized response format that eliminates
     * legacy inconsistencies like "processedFiles" vs "transfers".
     *
     * Use this for new code. The old Map-based format is kept for backward
     * compatibility.
     */
    private Map<String, Object> buildUnifiedResult(
            List<FileUploadInfo> uploadedFiles,
            List<FileMetadata> protectedMetadata,
            String receiveTargetPath,
            long executionTime) {

        FileOperationResult.Builder resultBuilder = FileOperationResult.builder();

        // Build file list
        for (int i = 0; i < uploadedFiles.size() && i < protectedMetadata.size(); i++) {
            FileUploadInfo fileInfo = uploadedFiles.get(i);
            FileMetadata metadata = protectedMetadata.get(i);

            FileInfo file = FileInfo.builder()
                    .fileName(fileInfo.getOriginalFilename())
                    .fileHash(metadata.getFileHash())
                    .fileSize(fileInfo.getFileSize())
                    .localPath(fileInfo.getStoredPath())
                    .contentType(fileInfo.getContentType())
                    .protectedFile(true)
                    .success(true)
                    .timestamp(System.currentTimeMillis())
                    .build();

            resultBuilder.addFile(file);
        }

        // Set counts
        resultBuilder
                .totalFiles(uploadedFiles.size())
                .metadata("receiveTargetPath", receiveTargetPath)
                .metadata("executionTimeMs", executionTime);

        // Convert to Map for compatibility with existing infrastructure
        Map<String, Object> result = resultBuilder.build().toMap();
        result.put("success", true);
        result.put("message", "Files protected successfully");

        return result;
    }

    /**
     * Extract boolean parameter from information map with default value.
     */
    private boolean extractBooleanParameter(Map<String, Object> information, String key, boolean defaultValue) {
        Object value = information.get(key);
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }

        logger.atWarn().log("Invalid boolean value for {}: {}, using default: {}", key, value, defaultValue);
        return defaultValue;
    }

    @Override
    public Map<String, Object> brokerExecute(Map<String, Object> information) {
        // For broker execution, use the same logic as local execution
        logger.atDebug().log("brokerExecute called, delegating to execute()");
        return execute(information, null);
    }
}
