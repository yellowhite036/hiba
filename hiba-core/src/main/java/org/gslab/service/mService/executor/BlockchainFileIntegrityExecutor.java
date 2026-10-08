package org.gslab.service.mService.executor;

import io.netty.channel.Channel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.gslab.Executor;
import org.gslab.configuration.ServiceConfiguration;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.model.RequestId;
import org.gslab.sdns.FileUploadInfo;
import org.gslab.sdns.HiBAInitial;
import org.gslab.tpm.TPMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executor for blockchain file integrity verification service.
 *
 * Verifies file integrity using blockchain-stored metadata through a four-stage process:
 * 1. Stage 1: Local File Analysis - Compute file hash
 * 2. Stage 2: Hash Comparison - Compare with expected hash
 * 3. Stage 3: Blockchain Query - Retrieve metadata from blockchain
 * 4. Stage 4: Field-by-Field Comparison - Validate all metadata fields
 *
 * Supports three request modes:
 * - Mode A: Verify uploaded file (multipart)
 * - Mode B: Verify by fileName (search in resource folder)
 * - Mode C: Query blockchain only (by fileHash)
 */
public class BlockchainFileIntegrityExecutor extends Executor {

    private static final Logger logger = LoggerFactory.getLogger(BlockchainFileIntegrityExecutor.class);
    private final FileProtectionService fileProtectionService;
    private final TPMService tpmService;
    private final Path resourceDir;

    public BlockchainFileIntegrityExecutor() {
        this.fileProtectionService = HiBAInitial.fileProtectionService;
        this.tpmService = HiBAInitial.tpmService;
        this.resourceDir = Path.of("files");
        logger.atDebug().log("BlockchainFileIntegrityExecutor initialized");
    }

    public BlockchainFileIntegrityExecutor(ServiceConfiguration config) {
        this.fileProtectionService = HiBAInitial.fileProtectionService;
        this.tpmService = HiBAInitial.tpmService;
        this.resourceDir = Path.of(config.getMethods().optString("fileDirectory", "files"));
        logger.atDebug().log("BlockchainFileIntegrityExecutor initialized with config: {}", config);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> information, Channel brokerChannel) {
        RequestId requestId = (RequestId) information.get("requestId");
        MDC.put("requestId", requestId.value());
        logger.atInfo().log("=== BlockchainFileIntegrity verification started ===");

        long startTime = System.currentTimeMillis();

        try {
            if (fileProtectionService == null) {
                logger.atError().log("FileProtectionService is not initialized");
                return createErrorResult("FileProtectionService not available");
            }

            // Determine request mode and execute
            Map<String, Object> result;
            if (hasUploadedFile(information)) {
                result = verifyUploadedFile(information);
            } else if (hasFileName(information)) {
                result = verifyByFileNameSearch(information);
            } else if (hasFileHash(information)) {
                result = queryBlockchainOnly(information);
            } else {
                result = createErrorResult("Require: file, fileName, or fileHash");
            }

            result.put("executionTimeMs", System.currentTimeMillis() - startTime);
            logger.atInfo().log("=== BlockchainFileIntegrity verification completed ===");
            return result;

        } catch (Exception e) {
            logger.atError().setCause(e).log("BlockchainFileIntegrity verification failed");
            Map<String, Object> errorResult = createErrorResult(e.getMessage());
            errorResult.put("executionTimeMs", System.currentTimeMillis() - startTime);
            return errorResult;
        } finally {
            MDC.clear();
        }
    }

    private boolean hasUploadedFile(Map<String, Object> information) {
        @SuppressWarnings("unchecked")
        List<FileUploadInfo> uploadedFiles = (List<FileUploadInfo>) information.get("uploadedFiles");
        return uploadedFiles != null && !uploadedFiles.isEmpty();
    }

    private boolean hasFileName(Map<String, Object> information) {
        String fileName = (String) information.get("fileName");
        return fileName != null && !fileName.isEmpty();
    }

    private boolean hasFileHash(Map<String, Object> information) {
        String fileHash = (String) information.get("fileHash");
        return fileHash != null && !fileHash.isEmpty();
    }

    /**
     * Mode A: Verify uploaded file (multipart)
     */
    private Map<String, Object> verifyUploadedFile(Map<String, Object> information) {
        @SuppressWarnings("unchecked")
        List<FileUploadInfo> uploadedFiles = (List<FileUploadInfo>) information.get("uploadedFiles");
        FileUploadInfo fileInfo = uploadedFiles.get(0);

        File file = new File(fileInfo.getStoredPath());
        if (!file.exists()) {
            return createErrorResult("Uploaded file not found: " + fileInfo.getStoredPath());
        }

        logger.atInfo().log("Verifying uploaded file: {}", fileInfo.getOriginalFilename());
        return performFullVerification(file);
    }

    /**
     * Mode B: Verify by fileName (search in resource folder)
     */
    private Map<String, Object> verifyByFileNameSearch(Map<String, Object> information) {
        String fileName = (String) information.get("fileName");
        logger.atInfo().log("Searching for file in resource directory: {}", fileName);

        File foundFile = findFileInResourceDirectory(fileName);
        if (foundFile == null) {
            return createErrorResult("File not found in resource directory: " + fileName);
        }

        logger.atInfo().log("Found file: {}", foundFile.getAbsolutePath());
        return performFullVerification(foundFile);
    }

    /**
     * Mode C: Query blockchain only (by fileHash)
     */
    private Map<String, Object> queryBlockchainOnly(Map<String, Object> information) {
        String fileHash = (String) information.get("fileHash");
        logger.atInfo().log("Querying blockchain for fileHash: {}", fileHash);

        Map<String, Object> result = new HashMap<>();
        result.put("mode", "blockchainQuery");
        result.put("queryHash", fileHash);

        try {
            Optional<FileMetadata> bcMetadata =
                    fileProtectionService.findFileMetadataByFileHashFromBlockchain(fileHash);

            if (bcMetadata.isPresent()) {
                FileMetadata metadata = bcMetadata.get();
                result.put("success", true);
                result.put("found", true);
                result.put("blockchainMetadata", metadataToMap(metadata));
                logger.atInfo().log("Blockchain record found for hash: {}", fileHash);
            } else {
                result.put("success", true);
                result.put("found", false);
                result.put("message", "No blockchain record found for fileHash");
                logger.atWarn().log("No blockchain record found for hash: {}", fileHash);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Blockchain query failed: " + e.getMessage());
            logger.atError().setCause(e).log("Blockchain query failed");
        }

        return result;
    }

    /**
     * Perform full four-stage verification
     */
    private Map<String, Object> performFullVerification(File file) {
        String fileName = file.getName();
        String localPath = file.getAbsolutePath();

        // Build file-level info that will be included in the files array
        Map<String, Object> fileInfo = new HashMap<>();
        fileInfo.put("fileName", fileName);
        fileInfo.put("localPath", localPath);
        fileInfo.put("mode", "fullVerification");

        try {
            // Stage 1: Local File Analysis
            logger.atInfo().log("========================================");
            logger.atInfo().log("[VERIFICATION] Stage 1: Local File Analysis");
            logger.atInfo().log("========================================");

            FileMetadata localMetadata = fileProtectionService.createFileMetadata(file, tpmService.getEKFingerprint());
            String localHash = localMetadata.getFileHash();

            fileInfo.put("stage1", "PASSED");
            fileInfo.put("localMetadata", metadataToMap(localMetadata));
            logger.atInfo().log("Local hash computed: {}", localHash);

            // Stage 2: Blockchain Query
            logger.atInfo().log("========================================");
            logger.atInfo().log("[VERIFICATION] Stage 2: Blockchain Query");
            logger.atInfo().log("========================================");

            Optional<FileMetadata> bcMetadataOpt =
                    fileProtectionService.findFileMetadataByFileHashFromBlockchain(localHash);

            if (!bcMetadataOpt.isPresent()) {
                fileInfo.put("success", false);
                fileInfo.put("stage2", "FAILED");
                fileInfo.put("failedStage", 2);
                fileInfo.put("errorMessage", "File hash not registered on blockchain");
                logger.atWarn().log("Stage 2 FAILED: No blockchain record for hash {}", localHash);
                return buildStandardizedResult(fileInfo, false);
            }

            FileMetadata bcMetadata = bcMetadataOpt.get();
            fileInfo.put("stage2", "PASSED");
            fileInfo.put("blockchainMetadata", metadataToMap(bcMetadata));
            logger.atInfo().log("Blockchain record found");

            // Stage 3: Field-by-Field Comparison
            logger.atInfo().log("========================================");
            logger.atInfo().log("[VERIFICATION] Stage 3: Field Comparison");
            logger.atInfo().log("========================================");

            Map<String, Object> comparison = new HashMap<>();
            boolean fileNameMatch = java.util.Objects.equals(localMetadata.getFileName(), bcMetadata.getFileName());
            boolean fileHashMatch = java.util.Objects.equals(localMetadata.getFileHash(), bcMetadata.getFileHash());
            boolean fileSizeMatch = localMetadata.getFileSize() == bcMetadata.getFileSize();

            comparison.put("fileNameMatch", fileNameMatch);
            comparison.put("fileHashMatch", fileHashMatch);
            comparison.put("fileSizeMatch", fileSizeMatch);
            fileInfo.put("fieldComparison", comparison);

            logger.atInfo().log(
                    "fileName: {} | {}",
                    fileNameMatch ? "✓ MATCH" : "✗ MISMATCH",
                    localMetadata.getFileName() + " vs " + bcMetadata.getFileName());
            logger.atInfo().log(
                    "fileHash: {} | {}", fileHashMatch ? "✓ MATCH" : "✗ MISMATCH", localHash.substring(0, 16) + "...");
            logger.atInfo().log(
                    "fileSize: {} | {} vs {}",
                    fileSizeMatch ? "✓ MATCH" : "✗ MISMATCH",
                    localMetadata.getFileSize(),
                    bcMetadata.getFileSize());

            boolean metadataMatch = fileProtectionService.compareFileMetadata(bcMetadata, localMetadata);
            fileInfo.put("stage3", metadataMatch ? "PASSED" : "FAILED");

            // Stage 4: Final Verdict
            logger.atInfo().log("========================================");
            logger.atInfo().log("[VERIFICATION] Stage 4: Final Verdict");
            logger.atInfo().log("========================================");

            if (metadataMatch) {
                fileInfo.put("success", true);
                fileInfo.put("stage4", "PASSED");
                fileInfo.put("verdict", "VERIFICATION_SUCCESSFUL");
                logger.atInfo().log("✅ VERIFICATION SUCCESSFUL");
                return buildStandardizedResult(fileInfo, true);
            } else {
                fileInfo.put("success", false);
                fileInfo.put("stage4", "FAILED");
                fileInfo.put("failedStage", 3);
                fileInfo.put("verdict", "METADATA_MISMATCH");
                fileInfo.put("errorMessage", "Metadata comparison failed");
                logger.atWarn().log("⚠️ VERIFICATION FAILED: Metadata mismatch");
                return buildStandardizedResult(fileInfo, false);
            }

        } catch (Exception e) {
            fileInfo.put("success", false);
            fileInfo.put("errorMessage", e.getMessage());
            logger.atError().setCause(e).log("Verification error");
            return buildStandardizedResult(fileInfo, false);
        }
    }

    /**
     * Build standardized result with files array structure.
     */
    private Map<String, Object> buildStandardizedResult(Map<String, Object> fileInfo, boolean success) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);

        List<Map<String, Object>> files = new ArrayList<>();
        files.add(fileInfo);

        result.put("totalFiles", 1);
        result.put("files", files);
        result.put("successCount", success ? 1 : 0);
        if (!success) {
            result.put("failureCount", 1);
        }

        return result;
    }

    /**
     * Search for file in resource directory
     */
    private File findFileInResourceDirectory(String fileName) {
        try (Stream<Path> walk = Files.walk(resourceDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst()
                    .map(Path::toFile)
                    .orElse(null);
        } catch (IOException e) {
            logger.atError().setCause(e).log("Error searching resource directory");
            return null;
        }
    }

    private Map<String, Object> metadataToMap(FileMetadata metadata) {
        Map<String, Object> map = new HashMap<>();
        map.put("fileName", metadata.getFileName());
        map.put("fileHash", metadata.getFileHash());
        map.put("fileSize", metadata.getFileSize());
        map.put("uploader", metadata.getUploader());
        map.put(
                "uploadTime",
                metadata.getUploadTime() != null ? metadata.getUploadTime().toString() : null);
        return map;
    }

    /**
     * Create error result with standardized structure matching success format.
     * Includes file info with error message to maintain consistent response
     * structure.
     */
    private Map<String, Object> createErrorResult(String errorMessage) {
        return createErrorResult(errorMessage, null, null);
    }

    /**
     * Create error result with file context for better error tracking.
     */
    private Map<String, Object> createErrorResult(String errorMessage, String fileName, String localPath) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);

        // Build file info with error
        Map<String, Object> fileInfo = new HashMap<>();
        if (fileName != null) {
            fileInfo.put("fileName", fileName);
        }
        if (localPath != null) {
            fileInfo.put("localPath", localPath);
        }
        fileInfo.put("success", false);
        fileInfo.put("errorMessage", errorMessage);

        // Build standardized data structure
        List<Map<String, Object>> files = new ArrayList<>();
        files.add(fileInfo);

        result.put("totalFiles", fileName != null ? 1 : 0);
        result.put("files", files);
        result.put("successCount", 0);
        result.put("failureCount", fileName != null ? 1 : 0);
        result.put("errorMessage", errorMessage);

        return result;
    }

    @Override
    public Map<String, Object> brokerExecute(Map<String, Object> information) {
        logger.atDebug().log("brokerExecute called, delegating to execute()");
        return execute(information, null);
    }
}
