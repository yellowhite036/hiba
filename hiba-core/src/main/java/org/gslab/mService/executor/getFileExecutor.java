package org.gslab.mService.executor;

import io.netty.channel.Channel;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.gslab.Executor;
import org.gslab.util.PathUtil;
import org.gslab.configuration.ServiceConfiguration;
import org.gslab.fileProtection.exception.BlockchainUploadException;
import org.gslab.fileProtection.exception.FileProtectionException;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.model.response.FileOperationResult;
import org.gslab.model.response.FileOperationResult.FileInfo;
import org.gslab.sdns.FileTransferClient;
import org.gslab.sdns.HiBAInitial;
import org.gslab.tpm.TPMService;
import org.gslab.tpm.exception.TPMConnectionException;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class getFileExecutor extends Executor {

    private static final Logger logger = LoggerFactory.getLogger(getFileExecutor.class);
    private ServiceConfiguration config;

    private static final ArrayList<String> providedFiles = new ArrayList<>(); // 節點提供出去的檔案清單

    private Path resourcePath = Paths.get("files");

    // BlockchainManager integration
    private final FileProtectionService fileProtectionService;
    private final TPMService tpmService;

    public getFileExecutor() {
        initializeResourcePath();

        // Initialize BlockchainManager with default configuration
        this.fileProtectionService = HiBAInitial.fileProtectionService;
        this.tpmService = HiBAInitial.tpmService;

        logger.atInfo().log("BlockchainManager initialized for GetFileExecutor");
    }

    public getFileExecutor(ServiceConfiguration config) {
        this.config = config;
        initializeResourcePath();

        // Initialize BlockchainManager with provided service configuration
        this.fileProtectionService = HiBAInitial.fileProtectionService;
        this.tpmService = HiBAInitial.tpmService;

        logger.atInfo().log("BlockchainManager initialized with service configuration for GetFileExecutor");
    }

    @Override
    public Executor getNewInstance() {
        return this;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> information, Channel brokerChannel) {
        Map<String, Object> resultInformation = initializeExecutionResult();
        String requestedFileName = (String) information.get("fileName");

        logger.atInfo().log("Node received get file request: {}", information);
        logger.atInfo().log("Requested file name: {}", requestedFileName);

        try {
            if (isInvalidFileName(requestedFileName)) {
                return createErrorResult(resultInformation, "No file name specified");
            }

            FileValidationResult validation = validateFileForExecution(requestedFileName);
            if (!validation.isValid) {
                return createErrorResult(resultInformation, validation.errorMessage);
            }

            String nodeName = (String) information.get("nodeName");
            FileTransferContext context = prepareFileTransfer(validation.sourceFile, nodeName);

            BlockchainProcessingResult blockchain =
                    processBlockchainProtection(validation.sourceFile, requestedFileName, "HiBA-GetFile");

            performFileTransferToNode(resultInformation, context, blockchain, brokerChannel, requestedFileName);

        } catch (Exception e) {
            logger.atError().setCause(e).log("Exception in getFileExecutor: ");
            resultInformation.put("transferred", false);
            resultInformation.put("error", "Execution error: " + e.getMessage());
        }

        return finalizeExecutionResult(resultInformation);
    }

    @Override
    public Map<String, Object> brokerExecute(Map<String, Object> information) {
        Map<String, Object> resultInformation = initializeExecutionResult();
        String requestedFileName = (String) information.get("fileName");

        logger.atInfo().log("Broker executing file transfer for: {}", requestedFileName);

        try {
            if (isInvalidFileName(requestedFileName)) {
                return createErrorResult(resultInformation, "No fileName specified");
            }

            FileValidationResult validation = validateFileForExecution(requestedFileName);
            if (!validation.isValid) {
                return createErrorResult(resultInformation, validation.errorMessage);
            }

            BlockchainProcessingResult blockchain =
                    processBlockchainProtection(validation.sourceFile, requestedFileName, "HiBA-Broker");

            prepareBrokerResult(resultInformation, validation.sourceFile, requestedFileName, blockchain);
            logger.atInfo().log("File found and ready for transfer: {}", validation.sourceFile.getPath());

        } catch (Exception e) {
            logger.atError().setCause(e).log("Exception in broker execute: ");
            resultInformation.put("transferred", false);
            resultInformation.put("error", "Broker execution error: " + e.getMessage());
        }

        return finalizeBrokerResult(resultInformation);
    }

    /**
     * 檢查請求的檔案是否在節點的資源清單中，從 ServiceConfiguration 中讀取
     */
    private boolean checkFileInResourceList(String requestedFileName) {
        try {
            if (config == null || config.getResources() == null) {
                throw new RuntimeException("Resource list is not configured in ServiceConfiguration");
            }

            // 從 ServiceConfiguration 讀取 fileName 資源清單
            if (config.getResources().has("fileName")) {
                org.json.JSONArray fileNameArray = config.getResources().getJSONArray("fileName");
                for (int i = 0; i < fileNameArray.length(); i++) {
                    String availableFile = fileNameArray.getString(i);
                    if (availableFile.equals(requestedFileName)) {
                        logger.atInfo().log("File {} found in service configuration resource list", requestedFileName);
                        return true;
                    }
                }
            }

            logger.atWarn().log("File {} not found in resource list", requestedFileName);
            return false;
        } catch (Exception e) {
            logger.atError().setCause(e).log("Error checking resource list");
            return false;
        }
    }

    /**
     * Initialize resource path from service configuration and check file protection status
     */
    private void initializeResourcePath() {
        if (config == null || config.getMethods() == null) {
            logger.atWarn()
                    .log(
                            "Service configuration or methods not available, using default resource path: {}",
                            this.resourcePath);
            return;
        }

        try {
            if (config.getMethods().has("fileDirectory")) {
                String configPath = config.getMethods().getString("fileDirectory");
                this.resourcePath = PathUtil.normalizePath(configPath);
                logger.atInfo().log("Resource path loaded from service configuration: {}", this.resourcePath);
            } else {
                logger.atWarn()
                        .log("fileDirectory not found in service configuration, using default: {}", this.resourcePath);
            }
        } catch (JSONException e) {
            logger.atError().setCause(e).log("Error reading fileDirectory from service configuration");
            logger.atError().log("Using default resource path: {}", this.resourcePath);
        }
    }

    /**
     * Set service configuration (for backward compatibility)
     */
    public void setServiceConfiguration(ServiceConfiguration config) {
        this.config = config;
        initializeResourcePath();
        logger.atInfo().log("Service configuration set for getFileExecutor: {}", config.getServiceName());
    }

    private static class FileValidationResult {
        final boolean isValid;
        final File sourceFile;
        final String errorMessage;

        FileValidationResult(boolean isValid, File sourceFile, String errorMessage) {
            this.isValid = isValid;
            this.sourceFile = sourceFile;
            this.errorMessage = errorMessage;
        }
    }

    private static class FileTransferContext {
        final File sourceFile;
        final String targetPath;
        final File targetDirFile;

        FileTransferContext(File sourceFile, String targetPath, File targetDirFile) {
            this.sourceFile = sourceFile;
            this.targetPath = targetPath;
            this.targetDirFile = targetDirFile;
        }
    }

    private BlockchainProcessingResult processBlockchainProtection(
            File sourceFile, String requestedFileName, String serviceType)
            throws BlockchainUploadException, FileProtectionException {

        // Use TPM EK Fingerprint as the uploader for hardware-bound identity
        String uploader;
        try {
            uploader = tpmService.getEKFingerprint();
        } catch (TPMConnectionException e) {
            logger.atWarn()
                    .log("Failed to get TPM EK Fingerprint, using requestedFileName as fallback: {}", e.getMessage());
            uploader = requestedFileName;
        }

        FileMetadata metadata = fileProtectionService.protectFileToBlockchain(
                sourceFile, uploader, "File shared via " + serviceType + " service");

        return new BlockchainProcessingResult(metadata);
    }

    private Map<String, Object> initializeExecutionResult() {
        Map<String, Object> resultInformation = new HashMap<>();
        long startTime = System.currentTimeMillis();
        resultInformation.put("startTime", startTime);
        return resultInformation;
    }

    private boolean isInvalidFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            logger.atWarn().log("No file name specified in request");
            return true;
        }
        return false;
    }

    private Map<String, Object> createErrorResult(Map<String, Object> resultInformation, String errorMessage) {
        resultInformation.put("transferred", false);
        resultInformation.put("error", errorMessage);
        return finalizeExecutionResult(resultInformation);
    }

    private FileValidationResult validateFileForExecution(String requestedFileName) {
        if (!checkFileInResourceList(requestedFileName)) {
            String error = "Requested file not in resource list: " + requestedFileName;
            logger.atWarn().log("Requested file not in resource list: {}", requestedFileName);
            return new FileValidationResult(false, null, error);
        }

        Path sourceFilePath = PathUtil.buildPath(resourcePath, requestedFileName);
        File sourceFile = sourceFilePath.toFile();
        if (!sourceFile.exists() || !sourceFile.isFile()) {
            String error = "File not found in files directory: " + sourceFile.getPath();
            logger.atWarn().log("File not found in files directory: {}", sourceFile.getPath());
            return new FileValidationResult(false, null, error);
        }

        return new FileValidationResult(true, sourceFile, null);
    }

    private FileTransferContext prepareFileTransfer(File sourceFile, String nodeName) {
        Path targetPath = PathUtil.buildPath(resourcePath, nodeName);
        logger.atInfo().log("Found file in files directory: {}", sourceFile.getPath());
        logger.atInfo().log("Preparing file transfer to broker: {} -> {}", sourceFile.getPath(), targetPath);

        File targetDirFile = targetPath.toFile();
        if (!targetDirFile.exists()) {
            targetDirFile.mkdirs();
        }

        return new FileTransferContext(sourceFile, targetPath.toString(), targetDirFile);
    }

    private static class BlockchainProcessingResult {

        final FileMetadata metadata;

        BlockchainProcessingResult(FileMetadata metadata) {
            this.metadata = metadata;
        }
    }

    private void performFileTransferToNode(
            Map<String, Object> resultInformation,
            FileTransferContext context,
            BlockchainProcessingResult blockchain,
            Channel brokerChannel,
            String requestedFileName) {
        // Local execution: brokerChannel is null, just provide file info
        if (brokerChannel == null) {
            logger.atInfo().log("Local execution - file already available at: {}", context.sourceFile.getPath());
            providedFiles.add(requestedFileName);
            resultInformation.put("transferred", true);
            resultInformation.put("localFile", true);
            resultInformation.put("filePath", context.sourceFile.getPath());
            resultInformation.put("fileName", requestedFileName);
            resultInformation.put("fileSize", context.sourceFile.length());
            addBlockchainInfoToResult(resultInformation, blockchain);
            return;
        }

        // Remote execution: use FileTransferClient with dedicated DATA_PORT connection
        // This avoids blocking the control channel with large file transfers
        boolean transferSuccess = FileTransferClient.doTransfer(
                context.sourceFile.getPath(),
                context.targetPath,
                null, // nodeIP not needed, will be extracted from brokerChannel
                brokerChannel,
                HiBAInitial.DATA_PORT,
                blockchain.metadata,
                null // requestId - not available in this context
                );

        if (transferSuccess) {
            providedFiles.add(requestedFileName);
            resultInformation.put("transferred", true);
            resultInformation.put("localFile", false);
            resultInformation.put("filePath", context.sourceFile.getPath());
            resultInformation.put("fileName", requestedFileName);
            resultInformation.put("fileSize", context.sourceFile.length());
            resultInformation.put("brokerTargetPath", context.targetPath);
            addBlockchainInfoToResult(resultInformation, blockchain);
            logger.atInfo().log("File transfer to broker successful: {} -> {}", requestedFileName, context.targetPath);
        } else {
            resultInformation.put("transferred", false);
            resultInformation.put("error", "File transfer failed via FileTransferClient");
            logger.atError().log("File transfer failed: {} -> {}", requestedFileName, context.targetPath);
        }
    }

    private void prepareBrokerResult(
            Map<String, Object> resultInformation,
            File targetFile,
            String requestedFileName,
            BlockchainProcessingResult blockchain) {
        resultInformation.put("transferred", true);
        resultInformation.put("fileName", requestedFileName);
        resultInformation.put("filePath", targetFile.getPath());
        addBlockchainInfoToResult(resultInformation, blockchain);
    }

    private void addBlockchainInfoToResult(
            Map<String, Object> resultInformation, BlockchainProcessingResult blockchain) {
        if (blockchain.metadata != null) {
            resultInformation.put("fileMetadata", blockchain.metadata);
            resultInformation.put("fileHash", blockchain.metadata.getFileHash());
            resultInformation.put("fileId", blockchain.metadata.getId());
        }
    }

    private Map<String, Object> finalizeExecutionResult(Map<String, Object> resultInformation) {
        long startTime = (Long) resultInformation.get("startTime");
        long endTime = System.currentTimeMillis();
        long timeConsuming = endTime - startTime;

        resultInformation.put("nodeProvides", providedFiles);
        resultInformation.put("endTime", endTime);
        resultInformation.put("exeTime", timeConsuming / 1000.00);

        logger.atInfo().log("File execution result: {}", resultInformation);
        return resultInformation;
    }

    private Map<String, Object> finalizeBrokerResult(Map<String, Object> resultInformation) {
        long startTime = (Long) resultInformation.get("startTime");
        long endTime = System.currentTimeMillis();
        long timeConsuming = endTime - startTime;

        resultInformation.put("endTime", endTime);
        resultInformation.put("exeTime", timeConsuming / 1000.00);

        logger.atInfo().log("Broker execution result: {}", resultInformation);
        return resultInformation;
    }

    /**
     * Build unified FileOperationResult format (new standardized response).
     *
     * This method provides the clean, standardized response format that eliminates
     * legacy inconsistencies like "transferred", "localFile", multiple path fields.
     *
     * Use this for new code. The old Map-based format is kept for backward compatibility.
     */
    private Map<String, Object> buildUnifiedResult(
            String fileName,
            File sourceFile,
            String sourceNode,
            BlockchainProcessingResult blockchain,
            boolean success,
            long executionTime) {

        FileOperationResult.Builder resultBuilder = FileOperationResult.builder();

        // Build file info
        FileInfo.FileInfoBuilder fileBuilder = FileInfo.builder()
                .fileName(fileName)
                .fileSize(sourceFile != null ? sourceFile.length() : 0)
                .localPath(sourceFile != null ? sourceFile.getPath() : null)
                .success(success)
                .sourceNode(sourceNode);

        // Add blockchain metadata if available
        if (blockchain != null && blockchain.metadata != null) {
            fileBuilder
                    .fileHash(blockchain.metadata.getFileHash())
                    .fileId(blockchain.metadata.getId())
                    .metadata(convertFileMetadataToMap(blockchain.metadata));
        }

        resultBuilder.addFile(fileBuilder.build());
        resultBuilder.totalFiles(1);

        // Convert to Map for compatibility
        Map<String, Object> result = resultBuilder.build().toMap();
        result.put("success", success);
        result.put("executionTimeMs", executionTime);

        return result;
    }

    /**
     * Convert FileMetadata to Map for unified response format.
     */
    private Map<String, Object> convertFileMetadataToMap(FileMetadata metadata) {
        Map<String, Object> map = new HashMap<>();
        if (metadata != null) {
            map.put("id", metadata.getId());
            map.put("fileName", metadata.getFileName());
            map.put("fileHash", metadata.getFileHash());
            map.put("fileSize", metadata.getFileSize());
            map.put("uploader", metadata.getUploader());
            map.put("uploadTime", metadata.getUploadTime());
            if (metadata.getFilePath() != null) {
                map.put("filePath", metadata.getFilePath());
            }
            if (metadata.getDescription() != null) {
                map.put("description", metadata.getDescription());
            }
        }
        return map;
    }
}
