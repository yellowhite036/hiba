package org.gslab.fileProtection.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.config.BlockchainFileProtectManagerConfig;
import org.gslab.fileProtection.config.FileProtectionConfig;
import org.gslab.fileProtection.config.RetryConfig;
import org.gslab.fileProtection.database.DatabaseManager;
import org.gslab.fileProtection.exception.BatchFileProtectionException;
import org.gslab.fileProtection.exception.BlockchainNetworkException;
import org.gslab.fileProtection.exception.BlockchainUploadException;
import org.gslab.fileProtection.exception.DatabaseException;
import org.gslab.fileProtection.exception.FileNotFoundException;
import org.gslab.fileProtection.exception.FileProtectionException;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.model.TransactionResult;
import org.gslab.fileProtection.util.FileMetadataFactory;
import org.gslab.fileProtection.util.RetryUtil;

/**
 * File Protection Service - Simplified core service for blockchain-based file protection
 * <p>
 * Follows Linus Torvalds "Good Taste" principles:
 * - Blockchain is the single source of truth
 * - Database is query optimization only
 * - Unified retry logic via RetryUtil
 * - No built-in caching - users implement their own caching strategy
 * <p>
 * Architecture: Service (FileProtectionService) -> Database/Blockchain
 * Data flow: Database (optimization) -> Blockchain (truth)
 */
@Slf4j
public class FileProtectionService implements AutoCloseable {

    private final DatabaseManager databaseManager;
    private final BlockchainService blockchainService;

    private final BlockchainFileProtectManagerConfig blockchainManagerConfig;

    private final FileProtectionConfig fileProtectionConfig;
    private final boolean isEnableDatabaseRetry;

    private final RetryConfig retryConfig;

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    public FileProtectionService(BlockchainService blockchainService, DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.blockchainService = blockchainService;

        this.blockchainManagerConfig = new BlockchainFileProtectManagerConfig();
        this.fileProtectionConfig = this.blockchainManagerConfig.getFileProtectionConfig();
        this.isEnableDatabaseRetry = this.fileProtectionConfig.isEnableDatabaseRetry();
        this.retryConfig = this.blockchainManagerConfig.getRetryConfig();
    }

    public FileProtectionService(
            BlockchainService blockchainService,
            DatabaseManager databaseManager,
            BlockchainFileProtectManagerConfig blockchainManagerConfig) {
        this.databaseManager = databaseManager;
        this.blockchainService = blockchainService;

        this.blockchainManagerConfig = blockchainManagerConfig;
        this.fileProtectionConfig = this.blockchainManagerConfig.getFileProtectionConfig();
        this.isEnableDatabaseRetry = this.fileProtectionConfig.isEnableDatabaseRetry();
        this.retryConfig = this.blockchainManagerConfig.getRetryConfig();
    }

    /**
     * Protect file and upload to blockchain (without local database storage)
     *
     * @param file        File to protect
     * @param uploader    Uploader
     * @param description File description
     * @return FileMetadata record
     * @throws FileProtectionException   Thrown when file validation or metadata creation fails
     * @throws BlockchainUploadException Thrown when blockchain upload fails
     */
    public FileMetadata protectFileToBlockchain(File file, String uploader, String description)
            throws FileProtectionException, BlockchainUploadException {
        // Create FileMetadata (includes file validation)
        FileMetadata metadata = FileMetadataFactory.createFileMetadata(file, uploader, description);

        // Check if file already exists on blockchain by trying to retrieve it
        if (blockchainService.exists(metadata.getFileHash())) {

            try {
                FileMetadata existingMetadata = blockchainService.retrieveMetadataByFileHash(metadata.getFileHash());
                log.info("File already exists on blockchain: {}", existingMetadata.getFileName());
                return existingMetadata;
            } catch (FileNotFoundException e) {
                // File doesn't actually exist despite exists() returning true - continue with upload
                log.warn(
                        "File existence check returned true but file not found - continuing with upload: {}",
                        metadata.getFileName());
            } catch (BlockchainNetworkException e) {
                log.error("Network error while retrieving existing file from blockchain: {}", metadata.getFileName());
                throw new BlockchainUploadException(
                        "CHECK-EXISTENCE",
                        "retrieveMetadataByFileHash",
                        "Network error while verifying existing file on blockchain: " + e.getMessage(),
                        e);
            }
        }

        // Store FileMetadata directly to blockchain smart contract map
        try {
            blockchainService.uploadMetadata(metadata);
            log.info("File stored to blockchain successfully: {}", metadata.getFileName());
        } catch (Exception e) {
            log.error("Failed to store file to blockchain: {}", metadata.getFileName());
            throw new BlockchainUploadException(
                    "STORE-METADATA",
                    "uploadMetadata",
                    "Failed to store file metadata to blockchain: " + e.getMessage(),
                    e);
        }

        log.info("File protection to blockchain completed successfully: {}", metadata.getFileName());

        return metadata;
    }

    /**
     * Upload prepared FileMetadata to blockchain without file access.
     *
     * @param metadata Prepared FileMetadata
     * @return Uploaded FileMetadata
     * @throws BlockchainUploadException Thrown when blockchain upload fails
     */
    public FileMetadata uploadMetadataToBlockchain(FileMetadata metadata) throws BlockchainUploadException {
        if (metadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }

        try {
            blockchainService.uploadMetadata(metadata);
            log.info("Prepared metadata uploaded successfully: {}", metadata.getFileName());
            return metadata;
        } catch (Exception e) {
            log.error("Failed to upload prepared metadata: {}", metadata.getFileName(), e);
            throw new BlockchainUploadException(
                    "STORE-METADATA",
                    "uploadMetadata",
                    "Failed to upload prepared metadata to blockchain: " + e.getMessage(),
                    e);
        }
    }

    /**
     * Batch upload prepared FileMetadata to blockchain without file access.
     *
     * @param metadataList Prepared metadata list
     * @return Uploaded metadata list
     * @throws BlockchainUploadException Thrown when blockchain upload fails
     */
    public List<FileMetadata> batchUploadMetadataToBlockchain(List<FileMetadata> metadataList)
            throws BlockchainUploadException {
        if (metadataList == null || metadataList.isEmpty()) {
            throw new IllegalArgumentException("Metadata list cannot be null or empty");
        }

        try {
            blockchainService.batchUploadMetadata(metadataList);
            log.info("Prepared metadata batch uploaded successfully: {} files", metadataList.size());
            return metadataList;
        } catch (Exception e) {
            log.error("Failed to upload prepared metadata batch", e);
            throw new BlockchainUploadException(
                    "STORE-METADATA-BATCH",
                    "batchUploadMetadata",
                    "Failed to upload prepared metadata batch: " + e.getMessage(),
                    e);
        }
    }

    /**
     * Save FileMetadata to local database with retry mechanism
     *
     * @param metadata The FileMetadata to save
     * @return true if saved successfully, false if failed after retries
     */
    public boolean saveFileMetadataToDatabase(FileMetadata metadata) {
        if (metadata == null) {
            log.error("Cannot save null metadata to database");
            throw new IllegalArgumentException("Metadata cannot be null");
        }

        boolean localSuccess = attemptLocalMetadataStorage(metadata);

        if (localSuccess) {
            log.info("FileMetadata saved to database successfully: {}", metadata.getFileName());
        } else {
            // Local storage failed, schedule async retry
            scheduleAsyncMetadataRetry(metadata);
            log.warn("FileMetadata save to database pending (will retry): {}", metadata.getFileName());
        }

        return localSuccess;
    }

    /**
     * Protect file and upload to blockchain (with automatic local database storage)
     *
     * @param file        File to protect
     * @param uploader    Uploader
     * @param description File description
     * @return FileMetadata record
     * @throws FileProtectionException   Thrown when file validation or metadata creation fails
     * @throws BlockchainUploadException Thrown when blockchain upload fails
     */
    public FileMetadata protectFile(File file, String uploader, String description)
            throws FileProtectionException, BlockchainUploadException {

        // First protect to blockchain
        FileMetadata metadata = protectFileToBlockchain(file, uploader, description);

        // Then save to local database
        saveFileMetadataToDatabase(metadata);

        return metadata;
    }

    public CompletableFuture<FileMetadata> protectFileToBlockchainAsync(
            File file, String uploader, String description) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return protectFileToBlockchain(file, uploader, description);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Asynchronous file protection
     *
     * @param file        File to protect
     * @param uploader    Uploader
     * @param description File description
     * @return CompletableFuture wrapped FileMetadata record
     * @throws CompletionException When the async operation fails (wraps other exceptions)
     */
    public CompletableFuture<FileMetadata> protectFileAsync(File file, String uploader, String description) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return protectFile(file, uploader, description);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Batch protect multiple files to blockchain only (without local database storage)
     *
     * <p><b>IMPORTANT:</b> This method preserves input order in the returned results</p>
     * <ul>
     * <li>result[i] corresponds to fileList[i] (when successful)</li>
     * <li>Invalid files cause BatchFileProtectionException with file names</li>
     * <li>Blockchain operations are ATOMIC: all files succeed or all fail</li>
     * </ul>
     *
     * @param fileList        List of files to protect
     * @param uploader        Uploader
     * @param descriptionList List of descriptions corresponding to each file
     * @return List of FileMetadata records in original input order (failed files excluded)
     * @throws IllegalArgumentException     When fileList and descriptionList sizes don't match
     * @throws BatchFileProtectionException When any files are invalid (contains list of invalid files)
     * @throws BlockchainUploadException    Thrown when blockchain upload fails
     */
    public List<FileMetadata> batchProtectFileToBlockchain(
            List<File> fileList, String uploader, List<String> descriptionList)
            throws BatchFileProtectionException, BlockchainUploadException {

        if (fileList == null || fileList.isEmpty()) {
            throw new IllegalArgumentException("File list cannot be null or empty");
        }

        // check for invalid files first
        List<File> invalidFileList = fileList.stream()
                .filter(file -> file == null || !file.exists() || !file.isFile())
                .toList();

        if (!invalidFileList.isEmpty()) {
            throw new BatchFileProtectionException(
                    "batch-validate", "file-validation", "Batch file validation failed", invalidFileList);
        }

        // Ensure we have descriptions for all files (pad with empty strings if needed)
        descriptionList = ensureDescriptionList(fileList, descriptionList);

        // Parallel file existence checking
        // Expensive validation - parallel blockchain checks (only for valid files)
        final List<String> finalDescriptionList = descriptionList; // Make effectively final
        final String finalUploader = uploader;
        final BlockchainService finalBlockchainService = blockchainService;

        // Create file-description pairs and process valid files
        // Using IntStream to maintain index-based pairing between fileList and descriptionList
        List<CompletableFuture<IndexedFileCheckResult>> blockchainFileCheckFutures = IntStream.range(0, fileList.size())
                .filter(i -> {
                    File file = fileList.get(i);
                    return file != null && file.exists() && file.isFile(); // All should be valid now
                })
                .mapToObj(i -> {
                    final File file = fileList.get(i);
                    final String description = finalDescriptionList.get(i); // Index-based pairing
                    final int originalIndex = i; // Track original position for order preservation

                    return CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    FileMetadata metadata =
                                            FileMetadataFactory.createFileMetadata(file, finalUploader, description);

                                    // Check if file already exists on blockchain
                                    if (finalBlockchainService.exists(metadata.getFileHash())) {
                                        try {
                                            FileMetadata existingMetadata =
                                                    finalBlockchainService.retrieveMetadataByFileHash(
                                                            metadata.getFileHash());
                                            log.debug(
                                                    "File already exists on blockchain, skipping: {}",
                                                    existingMetadata.getFileName());
                                            return new IndexedFileCheckResult(
                                                    originalIndex,
                                                    new FileCheckResult(metadata, existingMetadata, true));
                                        } catch (FileNotFoundException e) {
                                            // File doesn't actually exist despite exists() returning true - continue
                                            // with
                                            // upload
                                            log.warn(
                                                    "File existence check returned true but file not found - continuing with upload: {}",
                                                    metadata.getFileName());
                                            return new IndexedFileCheckResult(
                                                    originalIndex, new FileCheckResult(metadata, null, false));
                                        } catch (BlockchainNetworkException e) {
                                            log.error(
                                                    "Network error while retrieving existing file from blockchain: {}",
                                                    metadata.getFileName());
                                            // Continue with upload attempt rather than failing the entire batch
                                            return new IndexedFileCheckResult(
                                                    originalIndex, new FileCheckResult(metadata, null, false));
                                        }
                                    }

                                    // File doesn't exist, add to upload list
                                    return new IndexedFileCheckResult(
                                            originalIndex, new FileCheckResult(metadata, null, false));
                                } catch (Exception e) {
                                    log.error("Failed to check file existence: {}", file.getName(), e);
                                    return new IndexedFileCheckResult(
                                            originalIndex, new FileCheckResult(null, null, false, e));
                                }
                            },
                            executorService);
                })
                .toList();

        // Wait for all parallel checks to complete
        CompletableFuture<Void> allChecks =
                CompletableFuture.allOf(blockchainFileCheckFutures.toArray(new CompletableFuture[0]));

        try {
            allChecks.join(); // Wait for all tasks to complete
        } catch (Exception e) {
            log.error("Some parallel file checks failed", e);
            // Continue processing - individual results may still be valid
        }

        // Collect indexed results - all futures are now complete, so join() calls are non-blocking
        List<IndexedFileCheckResult> indexedResults = blockchainFileCheckFutures.stream()
                .map(future -> {
                    try {
                        // Extract result from completed future - this join() is non-blocking because
                        // allOf().join() above has already waited for all tasks to complete.
                        // We use try-catch to handle individual task exceptions without breaking
                        // the entire result collection process.
                        return future.join();
                    } catch (Exception e) {
                        log.error("Failed to get file check result", e);
                        // Convert individual task failure to error result instead of failing entire batch
                        return new IndexedFileCheckResult(-1, new FileCheckResult(null, null, false, e));
                    }
                })
                .filter(IndexedFileCheckResult::isValid) // Filter out invalid results
                .toList();

        // Separate existing and new files (maintain for blockchain operations)
        List<FileMetadata> uploadList = indexedResults.stream()
                .filter(indexedFileCheckResult -> !indexedFileCheckResult.hasExisting())
                .map(indexedFileCheckResult -> indexedFileCheckResult.result.newMetadata)
                .toList();

        // Batch store to blockchain smart contract (if needed)
        if (!uploadList.isEmpty()) {
            try {
                blockchainService.batchUploadMetadata(uploadList);
                log.info("Batch stored {} new files to blockchain successfully", uploadList.size());
            } catch (Exception e) {
                log.error("Failed to batch store files to blockchain");
                throw new BlockchainUploadException(
                        "BATCH-STORE",
                        "batchUploadMetadata",
                        "Failed to batch store file metadata to blockchain: " + e.getMessage(),
                        e);
            }
        }

        // Create ordered result array preserving input file order
        FileMetadata[] orderedResults = new FileMetadata[fileList.size()];

        // Fill results in original order
        indexedResults.stream()
                .filter(r -> r.originalIndex() >= 0 && r.originalIndex() < fileList.size())
                .forEach(r -> orderedResults[r.originalIndex()] = r.result().newMetadata());

        // Convert to list, filtering out null entries (failed files)
        List<FileMetadata> resultList =
                Arrays.stream(orderedResults).filter(Objects::nonNull).toList();

        log.info("Batch protection to blockchain completed successfully: {} total files processed", resultList.size());
        return resultList;
    }

    /**
     * Protect file and upload to blockchain (without local database storage) - WITH GAS TRACKING
     *
     * <p>This method returns detailed transaction information including Gas costs for performance analysis.
     *
     * @param file        File to protect
     * @param uploader    Uploader
     * @param description File description
     * @return ProtectionResultWithReceipt containing FileMetadata and Gas information
     * @throws FileProtectionException   Thrown when file validation or metadata creation fails
     * @throws BlockchainUploadException Thrown when blockchain upload fails
     */
    public ProtectionResultWithReceipt protectFileToBlockchainWithReceipt(
            File file, String uploader, String description) throws FileProtectionException, BlockchainUploadException {
        // Create FileMetadata (includes file validation)
        FileMetadata metadata = FileMetadataFactory.createFileMetadata(file, uploader, description);

        TransactionResult transactionResult = null;
        boolean isNewUpload = true;

        // Check if file already exists on blockchain
        if (blockchainService.exists(metadata.getFileHash())) {
            try {
                FileMetadata existingMetadata = blockchainService.retrieveMetadataByFileHash(metadata.getFileHash());
                log.info("File already exists on blockchain: {}", existingMetadata.getFileName());
                // Return existing metadata with no transaction result
                return new ProtectionResultWithReceipt(existingMetadata, null, false);
            } catch (FileNotFoundException e) {
                // File doesn't actually exist despite exists() returning true - continue with upload
                log.warn(
                        "File existence check returned true but file not found - continuing with upload: {}",
                        metadata.getFileName());
            } catch (BlockchainNetworkException e) {
                log.error("Network error while retrieving existing file from blockchain: {}", metadata.getFileName());
                throw new BlockchainUploadException(
                        "CHECK-EXISTENCE",
                        "retrieveMetadataByFileHash",
                        "Network error while verifying existing file on blockchain: " + e.getMessage(),
                        e);
            }
        }

        // Store FileMetadata to blockchain and get transaction result with Gas info
        try {
            transactionResult = blockchainService.uploadMetadataWithReceipt(metadata);
            log.info(
                    "File stored to blockchain successfully: {} (Gas: {} @ {} Gwei = {} ETH)",
                    metadata.getFileName(),
                    transactionResult.getGasUsedLong(),
                    transactionResult.getGasPriceGwei(),
                    transactionResult.getTotalCostEth());
        } catch (Exception e) {
            log.error("Failed to store file to blockchain: {}", metadata.getFileName());
            throw new BlockchainUploadException(
                    "STORE-METADATA",
                    "uploadMetadataWithReceipt",
                    "Failed to store file metadata to blockchain: " + e.getMessage(),
                    e);
        }

        log.info("File protection to blockchain completed successfully: {}", metadata.getFileName());

        return new ProtectionResultWithReceipt(metadata, transactionResult, isNewUpload);
    }

    /**
     * Batch protect files and upload to blockchain (without local database storage) - WITH GAS TRACKING
     *
     * <p>This method returns detailed transaction information including Gas costs for performance analysis.
     * Note: Only ONE TransactionResult is returned for the entire batch operation.
     *
     * @param fileList        List of files to protect
     * @param uploader        Uploader
     * @param descriptionList List of descriptions corresponding to each file
     * @return BatchProtectionResultWithReceipt containing all FileMetadata and single Gas transaction info
     * @throws BatchFileProtectionException When file validation fails
     * @throws BlockchainUploadException    When blockchain upload fails
     */
    public BatchProtectionResultWithReceipt batchProtectFileToBlockchainWithReceipt(
            List<File> fileList, String uploader, List<String> descriptionList)
            throws BatchFileProtectionException, BlockchainUploadException {

        if (fileList == null || fileList.isEmpty()) {
            throw new IllegalArgumentException("File list cannot be null or empty");
        }

        // Check for invalid files first
        List<File> invalidFileList = fileList.stream()
                .filter(file -> file == null || !file.exists() || !file.isFile())
                .toList();

        if (!invalidFileList.isEmpty()) {
            throw new BatchFileProtectionException(
                    "batch-validate", "file-validation", "Batch file validation failed", invalidFileList);
        }

        // Ensure we have descriptions for all files
        descriptionList = ensureDescriptionList(fileList, descriptionList);

        // Parallel file existence checking (same as batchProtectFileToBlockchain)
        final List<String> finalDescriptionList = descriptionList;
        final String finalUploader = uploader;
        final BlockchainService finalBlockchainService = blockchainService;

        List<CompletableFuture<IndexedFileCheckResult>> blockchainFileCheckFutures = IntStream.range(0, fileList.size())
                .filter(i -> {
                    File file = fileList.get(i);
                    return file != null && file.exists() && file.isFile();
                })
                .mapToObj(i -> {
                    final File file = fileList.get(i);
                    final String description = finalDescriptionList.get(i);
                    final int originalIndex = i;

                    return CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    FileMetadata metadata =
                                            FileMetadataFactory.createFileMetadata(file, finalUploader, description);

                                    if (finalBlockchainService.exists(metadata.getFileHash())) {
                                        try {
                                            FileMetadata existingMetadata =
                                                    finalBlockchainService.retrieveMetadataByFileHash(
                                                            metadata.getFileHash());
                                            log.debug(
                                                    "File already exists on blockchain, skipping: {}",
                                                    existingMetadata.getFileName());
                                            return new IndexedFileCheckResult(
                                                    originalIndex,
                                                    new FileCheckResult(metadata, existingMetadata, true));
                                        } catch (FileNotFoundException e) {
                                            log.warn(
                                                    "File existence check returned true but file not found - continuing with upload: {}",
                                                    metadata.getFileName());
                                            return new IndexedFileCheckResult(
                                                    originalIndex, new FileCheckResult(metadata, null, false));
                                        } catch (BlockchainNetworkException e) {
                                            log.error(
                                                    "Network error while retrieving existing file from blockchain: {}",
                                                    metadata.getFileName());
                                            return new IndexedFileCheckResult(
                                                    originalIndex, new FileCheckResult(metadata, null, false));
                                        }
                                    }

                                    return new IndexedFileCheckResult(
                                            originalIndex, new FileCheckResult(metadata, null, false));
                                } catch (Exception e) {
                                    log.error("Failed to check file existence: {}", file.getName(), e);
                                    return new IndexedFileCheckResult(
                                            originalIndex, new FileCheckResult(null, null, false, e));
                                }
                            },
                            executorService);
                })
                .toList();

        // Wait for all parallel checks to complete
        CompletableFuture<Void> allChecks =
                CompletableFuture.allOf(blockchainFileCheckFutures.toArray(new CompletableFuture[0]));

        try {
            allChecks.join();
        } catch (Exception e) {
            log.error("Some parallel file checks failed", e);
        }

        // Collect indexed results
        List<IndexedFileCheckResult> indexedResults = blockchainFileCheckFutures.stream()
                .map(future -> {
                    try {
                        return future.join();
                    } catch (Exception e) {
                        log.error("Failed to get file check result", e);
                        return new IndexedFileCheckResult(-1, new FileCheckResult(null, null, false, e));
                    }
                })
                .filter(IndexedFileCheckResult::isValid)
                .toList();

        // Separate existing and new files
        List<FileMetadata> uploadList = indexedResults.stream()
                .filter(indexedFileCheckResult -> !indexedFileCheckResult.hasExisting())
                .map(indexedFileCheckResult -> indexedFileCheckResult.result.newMetadata)
                .toList();

        // Batch store to blockchain WITH GAS TRACKING
        TransactionResult transactionResult = null;
        if (!uploadList.isEmpty()) {
            try {
                transactionResult = blockchainService.batchUploadMetadataWithReceipt(uploadList);
                log.info(
                        "Batch stored {} new files to blockchain successfully (Gas: {} @ {} Gwei = {} ETH)",
                        uploadList.size(),
                        transactionResult.getGasUsedLong(),
                        transactionResult.getGasPriceGwei(),
                        transactionResult.getTotalCostEth());
            } catch (Exception e) {
                log.error("Failed to batch store files to blockchain");
                throw new BlockchainUploadException(
                        "BATCH-STORE",
                        "batchUploadMetadataWithReceipt",
                        "Failed to batch store file metadata to blockchain: " + e.getMessage(),
                        e);
            }
        }

        // Create ordered result array preserving input file order
        FileMetadata[] orderedResults = new FileMetadata[fileList.size()];

        indexedResults.stream()
                .filter(r -> r.originalIndex() >= 0 && r.originalIndex() < fileList.size())
                .forEach(r -> orderedResults[r.originalIndex()] = r.result().newMetadata());

        List<FileMetadata> resultList =
                Arrays.stream(orderedResults).filter(Objects::nonNull).toList();

        log.info("Batch protection to blockchain completed successfully: {} total files processed", resultList.size());

        return new BatchProtectionResultWithReceipt(resultList, transactionResult, uploadList.size());
    }

    /**
     * Save multiple FileMetadata to local database with retry mechanism
     *
     * @param metadataList The list of FileMetadata to save
     * @return List of boolean results indicating success/failure for each metadata
     */
    public List<Boolean> batchSaveFileMetadataToDatabase(List<FileMetadata> metadataList) {
        List<Boolean> results = new ArrayList<>();

        if (metadataList == null || metadataList.isEmpty()) {
            log.warn("Cannot save null or empty metadata list to database");
            return results;
        }

        for (FileMetadata metadata : metadataList) {
            if (metadata == null) {
                results.add(false);
                continue;
            }

            boolean localSuccess = attemptLocalMetadataStorage(metadata);

            if (localSuccess) {
                log.info("FileMetadata saved to database successfully: {}", metadata.getFileName());
            } else {
                // Local storage failed, schedule async retry
                scheduleAsyncMetadataRetry(metadata);
                log.warn("FileMetadata save to database pending (will retry): {}", metadata.getFileName());
            }

            results.add(localSuccess);
        }

        long successCount = results.stream().mapToLong(b -> b ? 1 : 0).sum();
        log.info("Batch save to database completed: {}/{} files saved successfully", successCount, metadataList.size());

        return results;
    }

    /**
     * Batch protect multiple files (with automatic local database storage)
     *
     * @param fileList        List of files to protect
     * @param uploader        Uploader
     * @param descriptionList List of descriptions corresponding to each file
     * @return List of FileMetadata records
     * @throws IllegalArgumentException  When fileList and descriptionList sizes don't match
     * @throws FileProtectionException   Thrown when any file validation or metadata creation fails
     * @throws BlockchainUploadException Thrown when blockchain upload fails
     */
    public List<FileMetadata> batchProtectFile(List<File> fileList, String uploader, List<String> descriptionList)
            throws FileProtectionException, BlockchainUploadException {

        // First protect to blockchain
        List<FileMetadata> metadataList = batchProtectFileToBlockchain(fileList, uploader, descriptionList);

        // Then save to local database
        batchSaveFileMetadataToDatabase(metadataList);

        return metadataList;
    }

    /**
     * Asynchronous batch file protection to blockchain only
     *
     * @param fileList        List of files to protect
     * @param uploader        Uploader
     * @param descriptionList List of descriptions corresponding to each file
     * @return CompletableFuture wrapped list of FileMetadata records
     * @throws CompletionException When the async operation fails (wraps other exceptions)
     */
    public CompletableFuture<List<FileMetadata>> batchProtectFileToBlockchainAsync(
            List<File> fileList, String uploader, List<String> descriptionList) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return batchProtectFileToBlockchain(fileList, uploader, descriptionList);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                },
                executorService);
    }

    /**
     * Asynchronous batch file protection (with local database storage)
     *
     * @param fileList        List of files to protect
     * @param uploader        Uploader
     * @param descriptionList List of descriptions corresponding to each file
     * @return CompletableFuture wrapped list of FileMetadata records
     * @throws CompletionException When the async operation fails (wraps other exceptions)
     */
    public CompletableFuture<List<FileMetadata>> batchProtectFileAsync(
            List<File> fileList, String uploader, List<String> descriptionList) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return batchProtectFile(fileList, uploader, descriptionList);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                },
                executorService);
    }

    /**
     * Attempt to save FileMetadata to local database with unified retry logic
     */
    private boolean attemptLocalMetadataStorage(FileMetadata metadata) {
        if (metadata == null) {
            log.error("Cannot attempt local storage for null metadata");
            throw new IllegalArgumentException("Metadata cannot be null");
        }

        if (isEnableDatabaseRetry) {
            log.debug("Database retry enabled - attempting save with retries for: {}", metadata.getFileName());
            try {
                RetryUtil.executeWithRetry(
                        () -> databaseManager.saveFileMetadata(metadata),
                        "save metadata for " + metadata.getFileName(),
                        retryConfig.getMaxAttempts(),
                        retryConfig.getBaseDelayMs());
                return true;
            } catch (Exception e) {
                log.error("All local metadata storage attempts failed for file: {}", metadata.getFileName());
                return false;
            }
        } else {
            log.debug("Database retry disabled - attempting single save for: {}", metadata.getFileName());
            try {
                databaseManager.saveFileMetadata(metadata);
                return true;
            } catch (Exception e) {
                log.error("Single attempt to save metadata failed for file: {}", metadata.getFileName());
                return false;
            }
        }
    }

    /**
     * Schedule asynchronous retry for local metadata storage using unified retry logic
     */
    private void scheduleAsyncMetadataRetry(FileMetadata metadata) {
        if (metadata == null) {
            log.error("Cannot schedule async retry for null metadata");
            throw new IllegalArgumentException("Metadata cannot be null");
        }

        if (isEnableDatabaseRetry) {
            log.debug("Scheduling async metadata save retry for: {}", metadata.getFileName());
            RetryUtil.executeWithAsyncRetry(
                            () -> databaseManager.saveFileMetadata(metadata),
                            "save metadata for " + metadata.getFileName(),
                            retryConfig.getAsyncMaxAttempts(),
                            retryConfig.getAsyncBaseDelayMs())
                    .exceptionally(ex -> {
                        log.error(
                                "All async metadata retries failed for file: {}. Manual intervention required.",
                                metadata.getFileName());
                        log.error("Data can be recovered from blockchain. FileHash: {}", metadata.getFileHash());
                        return null;
                    });
        } else {
            log.debug("Database retry disabled - cannot schedule async retry for: {}", metadata.getFileName());
            try {
                databaseManager.saveFileMetadata(metadata);
                log.debug("Single attempt to save metadata succeeded for: {}", metadata.getFileName());
            } catch (Exception e) {
                log.error("Single attempt to save metadata failed for file: {}", metadata.getFileName());
            }
        }
    }

    /**
     * Find FileMetadata by file name
     *
     * @param fileName File name
     * @return FileMetadata record, returns null if not found
     */
    public FileMetadata findFileByNameFromDatabase(String fileName) {
        return databaseManager.findFileMetadataByName(fileName);
    }

    /**
     * Find FileMetadata by file ID
     *
     * @param fileId File ID
     * @return FileMetadata record, returns null if not found
     */
    public FileMetadata findFileByIdFromDatabase(String fileId) {
        return databaseManager.findFileMetadataById(fileId);
    }

    /**
     * Find FileMetadata by file hash - Simplified Linus-style data flow
     * Database (query optimization) -> Blockchain (source of truth)
     */
    public Optional<FileMetadata> findFileMetadataByFileHash(String fileHash) throws BlockchainNetworkException {
        // Try database for query optimization (but blockchain is truth)
        FileMetadata dbResult = databaseManager.findFileMetadataByHash(fileHash);
        if (dbResult != null) {
            return Optional.of(dbResult);
        }

        // Blockchain is the source of truth
        return findFileMetadataByFileHashFromBlockchain(fileHash);
    }

    /**
     * Find FileMetadata by file hash in blockchain only
     *
     * @param fileHash File hash
     * @return FileMetadata record from blockchain, empty if not found
     * @throws BlockchainNetworkException When blockchain network/system error occurs
     */
    public Optional<FileMetadata> findFileMetadataByFileHashFromBlockchain(String fileHash)
            throws BlockchainNetworkException {
        log.debug("Querying blockchain for metadata with fileHash: {}", fileHash);
        try {
            FileMetadata result = blockchainService.retrieveMetadataByFileHash(fileHash);
            log.info("Found metadata in blockchain for file: {} ({})", result.getFileName(), fileHash);
            return Optional.of(result);
        } catch (FileNotFoundException e) {
            log.warn("No metadata found in blockchain for fileHash: {}", fileHash);
            return Optional.empty();
        }
    }

    /**
     * Find FileMetadata by file hash in database only - Simplified
     */
    public Optional<FileMetadata> findFileMetadataByFileHashFromDatabase(String fileHash) {
        if (fileHash == null || fileHash.trim().isEmpty()) {
            throw new IllegalArgumentException("File hash cannot be null or empty");
        }
        return Optional.ofNullable(databaseManager.findFileMetadataByHash(fileHash));
    }

    /**
     * Verify file integrity
     *
     * @param localMetadata FileMetadata to verify
     * @return Verification result
     * @throws IllegalArgumentException When localMetadata or fileHash is null
     * @throws RuntimeException         When verification process fails due to network/system errors
     */
    public boolean verifyFileIntegrityWithBlockchain(FileMetadata localMetadata) throws BlockchainNetworkException {
        // check localMetadata is not null, and check the hash of the localMetadata is not null or empty
        String fileHash = Optional.ofNullable(localMetadata)
                .map(FileMetadata::getFileHash)
                .filter(hash -> !hash.trim().isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("FileMetadata or fileHash cannot be null"));

        Optional<FileMetadata> optionalBCMetadata = findFileMetadataByFileHashFromBlockchain(fileHash);
        return optionalBCMetadata
                .map(bcMetadata -> compareFileMetadata(localMetadata, bcMetadata))
                .orElse(false);
    }

    /**
     * Get all protected files
     *
     * @return All file metadata, empty collection if none found
     * @throws DatabaseException When database access fails
     */
    public Collection<FileMetadata> getAllProtectedFiles() {
        return databaseManager.getAllFileMetadata();
    }

    /**
     * Find files by uploader
     *
     * @param uploader Uploader
     * @return All files for this uploader, empty list if none found
     * @throws DatabaseException When database access fails
     */
    public List<FileMetadata> findFilesByUploader(String uploader) {
        return databaseManager.findFileMetadataByUploader(uploader);
    }

    /**
     * Get service statistics
     *
     * @return Statistics information
     * @throws DatabaseException When database access fails
     */
    public Map<String, Object> getStatistics() {
        return databaseManager.getStatistics();
    }

    /**
     * Create file metadata from a file
     *
     * @param file        File to create metadata for
     * @param uploader    Uploader name
     * @param description File description
     * @return FileMetadata object
     * @throws FileProtectionException When file metadata creation fails
     */
    public FileMetadata createFileMetadata(File file, String uploader, String description)
            throws FileProtectionException {
        return FileMetadataFactory.createFileMetadata(file, uploader, description);
    }

    /**
     * Create file metadata from a file with minimal parameters
     *
     * @param file     File to create metadata for
     * @param uploader Uploader name
     * @return FileMetadata object
     * @throws FileProtectionException When file metadata creation fails
     */
    public FileMetadata createFileMetadata(File file, String uploader) throws FileProtectionException {
        return FileMetadataFactory.createFileMetadata(file, uploader, null);
    }

    /**
     * Compare two FileMetadata objects for consistency
     *
     * @param original  Original FileMetadata
     * @param recovered FileMetadata recovered from blockchain
     * @return true if the metadata are consistent, false otherwise
     */
    public boolean compareFileMetadata(FileMetadata original, FileMetadata recovered) {
        if (original == null || recovered == null) {
            return false;
        }

        return original.equals(recovered);
    }

    /**
     * Check overall health status of the service
     *
     * @return true if both blockchain and database connections are healthy
     */
    public boolean isHealthy() {
        return blockchainService.isConnected() && databaseManager.isConnected();
    }

    /**
     * Get detailed health status of all components
     *
     * @return Map containing health status of blockchain, database, and overall service
     */
    public Map<String, Boolean> getHealthStatus() {
        boolean blockchainHealthy = blockchainService.isConnected();
        boolean databaseHealthy = databaseManager.isConnected();

        Map<String, Boolean> health = new java.util.HashMap<>();
        health.put("blockchain", blockchainHealthy);
        health.put("database", databaseHealthy);
        health.put("overall", blockchainHealthy && databaseHealthy);

        return health;
    }

    /**
     * Ensure description list matches file list size - Linus style: simple utility
     */
    private List<String> ensureDescriptionList(List<File> fileList, List<String> descriptionList) {
        if (descriptionList == null || descriptionList.size() < fileList.size()) {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < fileList.size(); i++) {
                if (descriptionList != null && i < descriptionList.size()) {
                    result.add(descriptionList.get(i));
                } else {
                    result.add("");
                }
            }
            return result;
        }
        return descriptionList;
    }

    /**
     * Shutdown the service and close database connections
     */
    @Override
    public void close() {
        log.info("Shutting down FileProtectionService...");
        if (databaseManager != null) {
            try {
                databaseManager.close();
            } catch (Exception e) {
                log.error("Error during database manager shutdown: {}", e.getMessage());
            }
        }

        try {
            blockchainService.close();
        } catch (Exception e) {
            log.error("Error during blockchain service shutdown: {}", e.getMessage());
        }
        log.info("FileProtectionService shutdown complete");
    }

    /**
     * Result wrapper for single file protection with Gas tracking
     *
     * @param metadata          The FileMetadata of the protected file
     * @param transactionResult Transaction result with Gas information (null if file already existed)
     * @param isNewUpload       True if a new transaction was created, false if file already existed
     */
    public record ProtectionResultWithReceipt(
            FileMetadata metadata, TransactionResult transactionResult, boolean isNewUpload) {

        /**
         * Check if this was a new upload with a transaction
         */
        public boolean hasTransaction() {
            return transactionResult != null;
        }

        /**
         * Get total Gas cost in ETH (0.0 if no transaction)
         */
        public double getTotalGasCostEth() {
            return transactionResult != null ? transactionResult.getTotalCostEth() : 0.0;
        }
    }

    /**
     * Result wrapper for batch file protection with Gas tracking
     *
     * @param metadataList      List of all FileMetadata (includes existing and new files)
     * @param transactionResult Transaction result with Gas information (null if no new files uploaded)
     * @param newFileCount      Number of new files actually uploaded (excludes existing files)
     */
    public record BatchProtectionResultWithReceipt(
            List<FileMetadata> metadataList, TransactionResult transactionResult, int newFileCount) {

        /**
         * Check if any new files were uploaded
         */
        public boolean hasTransaction() {
            return transactionResult != null && newFileCount > 0;
        }

        /**
         * Get total Gas cost in ETH (0.0 if no transaction)
         */
        public double getTotalGasCostEth() {
            return transactionResult != null ? transactionResult.getTotalCostEth() : 0.0;
        }

        /**
         * Get average Gas cost per file in ETH
         */
        public double getAverageGasCostPerFileEth() {
            if (newFileCount == 0 || transactionResult == null) {
                return 0.0;
            }
            return transactionResult.getTotalCostEth() / newFileCount;
        }
    }

    /**
     * Internal class to track original file position for order preservation
     */
    private record IndexedFileCheckResult(int originalIndex, FileCheckResult result) {

        boolean isValid() {
            return result != null && result.isValid();
        }

        boolean hasExisting() {
            return result != null && result.hasExisting();
        }
    }

    /**
     * Result container for parallel file existence checking
     */
    private record FileCheckResult(
            FileMetadata newMetadata, FileMetadata existingMetadata, boolean exists, Exception error) {
        FileCheckResult(FileMetadata newMetadata, FileMetadata existingMetadata, boolean exists) {
            this(newMetadata, existingMetadata, exists, null);
        }

        boolean isValid() {
            return error == null && newMetadata != null;
        }

        boolean hasExisting() {
            return exists && existingMetadata != null;
        }
    }
}
