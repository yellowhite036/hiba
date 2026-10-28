package org.gslab.mService.taker;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.gslab.Taker;
import org.gslab.configuration.ServiceConfiguration;
import org.gslab.fileProtection.exception.BatchFileProtectionException;
import org.gslab.fileProtection.exception.BlockchainUploadException;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.sdns.HiBAInitial;
import org.gslab.tpm.TPMService;
import org.gslab.tpm.exception.TPMConnectionException;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockchainFileProtectTaker extends Taker {

    private static final Logger logger = LoggerFactory.getLogger(BlockchainFileProtectTaker.class);
    private final FileProtectionService fileProtectionService;
    private final TPMService tpmService;
    private ServiceConfiguration config;
    private String resourcePath = "./files"; // Default resource path

    public BlockchainFileProtectTaker() {
        this.fileProtectionService = HiBAInitial.fileProtectionService;
        this.tpmService = HiBAInitial.tpmService;

        initializeResourcePath();
        // checkFilesProtectionStatus();

        logger.atDebug().log("BlockchainFileProtectTaker initialized");
    }

    public BlockchainFileProtectTaker(ServiceConfiguration config) {
        this.config = config;
        this.fileProtectionService = HiBAInitial.fileProtectionService;
        this.tpmService = HiBAInitial.tpmService;

        initializeResourcePath();
        // checkFilesProtectionStatus();

        logger.atDebug().log("BlockchainFileProtectTaker initialized with config: {}", config);
    }

    @Override
    public boolean checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier) {
        logger.atDebug().log("Checking if BlockchainFileProtectTaker can handle request");

        if (!HiBAInitial.blockchainFileProtect) {
            logger.atInfo().log("BlockchainFileProtect disabled by configuration, skipping local execution");
            return false;
        }

        if (fileProtectionService == null) {
            logger.atError().log("FileProtectionService is not initialized");
            return false;
        }

        logger.atDebug().log("FileProtectionService health status: {}", fileProtectionService.getHealthStatus());

        if (!fileProtectionService.isHealthy()) {
            logger.atError().log("FileProtectionService is not healthy");
            return false;
        }

        return true;
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
                this.resourcePath = config.getMethods().getString("fileDirectory");
                logger.atInfo().log("Resource path loaded from service configuration: {}", this.resourcePath);
            } else {
                logger.atWarn()
                        .log("fileDirectory not found in service configuration, using default: {}", this.resourcePath);
            }
        } catch (JSONException e) {
            logger.atError().log("Error reading fileDirectory from service configuration: {}", e);
            logger.atInfo().log("Using default resource path: {}", this.resourcePath);
        }
    }

    /**
     * Check protection status of all files in resourcePath during initialization
     */
    private void checkFilesProtectionStatus() {
        logger.atInfo().log("Checking blockchain protection status for files in: {}", this.resourcePath);

        File resourceDir = new File(this.resourcePath);
        if (!resourceDir.exists() || !resourceDir.isDirectory()) {
            logger.atWarn().log("Resource directory does not exist or is not a directory: {}", this.resourcePath);
            return;
        }

        File[] files = resourceDir.listFiles();
        if (files == null || files.length == 0) {
            logger.atInfo().log("No files found in resource directory: {}", this.resourcePath);
            return;
        }

        // Filter to include only files, excluding directories
        logger.atInfo().log("Filtering valid files from resource directory");

        List<File> fileList = Arrays.stream(files).filter(File::isFile).toList();

        fileList.forEach(file -> logger.atInfo().log("Valid file found: {}", file.getName()));

        if (fileList.isEmpty()) {
            logger.atInfo().log("No valid files to process in resource directory: {}", this.resourcePath);
            return;
        }

        logger.atInfo().log("Starting batch protection for {} files", fileList.size());

        List<FileMetadata> fileMetadataList;
        try {
            fileMetadataList = fileProtectionService.batchProtectFileToBlockchain(
                    fileList, tpmService.getEKFingerprint(), List.of());
        } catch (BatchFileProtectionException e) {
            logger.atError().setCause(e).log("Batch file protection error");
            throw new RuntimeException(e);
        } catch (BlockchainUploadException e) {
            logger.atError().setCause(e).log("Blockchain upload error during batch protection");
            throw new RuntimeException(e);
        } catch (TPMConnectionException e) {
            logger.atError().setCause(e).log("TPM connection error during batch protection");
            throw new RuntimeException(e);
        }
    }
}
