package org.gslab.fileProtection.performance;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.model.TransactionResult;
import org.gslab.fileProtection.performance.PerformanceTestRecord.SingleFileRecord;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.fileProtection.service.FileProtectionService.BatchProtectionResultWithReceipt;
import org.gslab.fileProtection.service.FileProtectionService.ProtectionResultWithReceipt;
import org.gslab.fileProtection.util.FileMetadataFactory;

/**
 * Blockchain Performance Tester - Executes performance tests on blockchain file protection operations
 *
 * <p>Follows Clean Code principles:
 * <ul>
 *   <li>Single Responsibility: Only handles test execution and timing</li>
 *   <li>Dependency Injection: Uses FileProtectionService (doesn't create it)</li>
 *   <li>Small methods: Each test method focuses on one scenario</li>
 *   <li>DRY: Reuses FileMetadataFactory for hash calculation (no duplication)</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Detailed time breakdown (hash, blockchain upload, database save)</li>
 *   <li>Single file and batch operation tests</li>
 *   <li>Test file generation and cleanup</li>
 *   <li>Gas cost tracking</li>
 * </ul>
 */
@Slf4j
public class BlockchainPerformanceTester {

    private static final String DEFAULT_TEST_DIR = "test-data/performance";
    private static final Random RANDOM = new Random();

    private final FileProtectionService fileProtectionService;
    private final String testDirectory;
    private final String uploader;
    private final String blockchain;

    private final List<File> createdTestFiles;

    public BlockchainPerformanceTester(
            FileProtectionService fileProtectionService, String blockchain, String uploader) {
        this(fileProtectionService, blockchain, uploader, DEFAULT_TEST_DIR);
    }

    public BlockchainPerformanceTester(
            FileProtectionService fileProtectionService, String blockchain, String uploader, String testDirectory) {
        this.fileProtectionService = fileProtectionService;
        this.blockchain = blockchain;
        this.uploader = uploader;
        this.testDirectory = testDirectory;
        this.createdTestFiles = new ArrayList<>();
    }

    /**
     * Test single file protection with detailed timing
     *
     * @param file File to test
     * @return Performance test record
     */
    public PerformanceTestRecord testSingleFileProtection(File file) {
        String testId = generateTestId();
        String testName = "Single File Test - " + file.getName();

        PerformanceTestRecord testRecord = new PerformanceTestRecord(testId, testName, blockchain, false);
        testRecord.start();

        SingleFileRecord fileRecord = new SingleFileRecord(file.getName(), file.length());

        try {
            // Protect file with timing and Gas tracking
            FileMetadata metadata = protectFileWithTiming(file, fileRecord, testRecord);
            fileRecord.setTransactionHash(metadata.getId());
            fileRecord.finish(true);

            testRecord.addFileRecord(fileRecord);
            testRecord.finish(true);

            log.info("Single file test completed successfully: {}", file.getName());
        } catch (Exception e) {
            log.error("Single file test failed: {}", file.getName(), e);
            fileRecord.setErrorMessage(e.getMessage());
            fileRecord.finish(false);
            testRecord.addFileRecord(fileRecord);
            testRecord.setErrorMessage(e.getMessage());
            testRecord.finish(false);
        }

        return testRecord;
    }

    /**
     * Test batch file protection with detailed timing (Sequential mode)
     * Processes files one by one to track detailed time breakdown
     * Accumulates Gas usage from all file transactions
     *
     * @param files List of files to test
     * @return Performance test record
     */
    public PerformanceTestRecord testBatchFileProtection(List<File> files) {
        String testId = generateTestId();
        String testName = "Batch File Test (Sequential) - " + files.size() + " files";

        PerformanceTestRecord testRecord = new PerformanceTestRecord(testId, testName, blockchain, true);
        testRecord.start();

        // Track accumulated Gas from all transactions
        long totalGasUsed = 0;
        double totalGasPriceSum = 0.0; // Sum for averaging
        double totalGasCost = 0.0;
        int gasTransactionCount = 0;

        try {
            // Process each file individually to track detailed timing
            // Accumulate Gas from multiple transactions
            for (File file : files) {
                SingleFileRecord fileRecord = new SingleFileRecord(file.getName(), file.length());

                // Reset Gas values before processing this file (prepare to receive new values)
                testRecord.setGasUsed(0);
                testRecord.setGasPrice(0);
                testRecord.setTotalGasCost(0);

                try {
                    FileMetadata metadata = protectFileWithTiming(file, fileRecord, testRecord);
                    fileRecord.setTransactionHash(metadata.getId());
                    fileRecord.finish(true);

                    // Read Gas values for this file
                    long fileGasUsed = testRecord.getGasUsed();
                    double fileGasPrice = testRecord.getGasPrice();
                    double fileGasCost = testRecord.getTotalGasCost();

                    // Accumulate Gas if a new transaction occurred (not a duplicate file)
                    if (fileGasUsed > 0) {
                        totalGasUsed += fileGasUsed;
                        totalGasPriceSum += fileGasPrice;
                        totalGasCost += fileGasCost;
                        gasTransactionCount++;

                        log.debug(
                                "File {} - Gas: {}, Price: {} Gwei, Cost: {} ETH",
                                file.getName(),
                                fileGasUsed,
                                fileGasPrice,
                                fileGasCost);
                    } else {
                        log.debug("File {} - Duplicate (no new transaction)", file.getName());
                    }
                } catch (Exception e) {
                    log.error("Failed to protect file in batch: {}", file.getName(), e);
                    fileRecord.setErrorMessage(e.getMessage());
                    fileRecord.finish(false);
                }

                testRecord.addFileRecord(fileRecord);
            }

            // Set accumulated Gas totals on test record
            if (gasTransactionCount > 0) {
                testRecord.setGasUsed(totalGasUsed);
                testRecord.setGasPrice(totalGasPriceSum / gasTransactionCount); // Average gas price
                testRecord.setTotalGasCost(totalGasCost);

                log.info(
                        "Batch Gas Summary - Total Used: {}, Avg Price: {} Gwei, Total Cost: {} ETH ({} transactions)",
                        totalGasUsed,
                        totalGasPriceSum / gasTransactionCount,
                        totalGasCost,
                        gasTransactionCount);
            }

            testRecord.finish(true);
            log.info("Batch file test completed: {} files", files.size());
        } catch (Exception e) {
            log.error("Batch file test failed", e);
            testRecord.setErrorMessage(e.getMessage());
            testRecord.finish(false);
        }

        return testRecord;
    }

    /**
     * Test REAL batch file protection using batchProtectFileToBlockchain (Atomic mode)
     * <p>
     * This tests the actual batch operation performance with per-file metrics:
     * <ul>
     *   <li><b>ACTUAL measurements</b>: Hash calculation time (timed individually per file)</li>
     *   <li><b>ESTIMATED metrics</b>: Blockchain upload time, database save time, and gas costs
     *       (calculated by dividing batch totals by file count)</li>
     * </ul>
     * <p>
     * The atomic batch design means blockchain and database operations happen as single transactions,
     * so per-file breakdown requires estimation rather than individual measurement.
     *
     * @param files List of files to test
     * @return Performance test record with per-file metrics (actual + estimated)
     */
    public PerformanceTestRecord testRealBatchFileProtection(List<File> files) {
        String testId = generateTestId();
        String testName = "Real Batch File Test (Atomic) - " + files.size() + " files";

        PerformanceTestRecord testRecord = new PerformanceTestRecord(testId, testName, blockchain, true);
        testRecord.start();

        // Create a single aggregated file record for the entire batch
        long totalSize = files.stream().mapToLong(File::length).sum();
        SingleFileRecord batchRecord = new SingleFileRecord("Batch of " + files.size() + " files", totalSize);

        try {
            // Phase 1: Hash calculation for all files
            // Create individual file records with actual hash timing (measured per-file)
            List<SingleFileRecord> fileRecords = new ArrayList<>();
            batchRecord.startHashCalculation();
            List<FileMetadata> metadataList = new ArrayList<>();
            List<String> descriptions = new ArrayList<>();
            for (File file : files) {
                // Instrument each file's hash calculation individually for accurate timing
                SingleFileRecord fileRecord = new SingleFileRecord(file.getName(), file.length());
                fileRecord.startHashCalculation();

                FileMetadata metadata =
                        FileMetadataFactory.createFileMetadata(file, uploader, "Batch performance test");

                fileRecord.finishHashCalculation();
                fileRecords.add(fileRecord);
                metadataList.add(metadata);
                descriptions.add("Batch performance test");
            }
            batchRecord.finishHashCalculation();

            // Phase 2: Real batch blockchain upload with Gas tracking (ATOMIC operation)
            batchRecord.startBlockchainUpload();
            try {
                BatchProtectionResultWithReceipt result =
                        fileProtectionService.batchProtectFileToBlockchainWithReceipt(files, uploader, descriptions);

                // Extract Gas information from the batch transaction
                if (result.hasTransaction() && result.transactionResult() != null) {
                    TransactionResult txResult = result.transactionResult();
                    testRecord.setGasUsed(txResult.getGasUsedLong());
                    testRecord.setGasPrice(txResult.getGasPriceGwei());
                    testRecord.setTotalGasCost(txResult.getTotalCostEth());

                    log.info(
                            "Batch transaction Gas: {} @ {} Gwei = {} ETH",
                            txResult.getGasUsedLong(),
                            txResult.getGasPriceGwei(),
                            txResult.getTotalCostEth());
                }

            } finally {
                batchRecord.finishBlockchainUpload();
            }

            // Phase 3: Batch database save
            batchRecord.startDatabaseSave();
            try {
                fileProtectionService.batchSaveFileMetadataToDatabase(metadataList);
            } finally {
                batchRecord.finishDatabaseSave();
            }

            // Distribute estimated blockchain and database metrics to individual file records
            // NOTE: Hash calculation times are ACTUAL (measured per-file)
            //       Blockchain and database times are ESTIMATED (total / file count)
            //       Gas costs are ESTIMATED (total / file count) - atomic transaction cannot be split
            long totalBlockchainMs = batchRecord.getBlockchainUploadDurationMs();
            long totalDatabaseMs = batchRecord.getDatabaseSaveDurationMs();
            int fileCount = fileRecords.size();

            // Calculate estimated per-file metrics
            long estimatedBlockchainMsPerFile = fileCount > 0 ? totalBlockchainMs / fileCount : 0;
            long estimatedDatabaseMsPerFile = fileCount > 0 ? totalDatabaseMs / fileCount : 0;

            // Populate each file record with estimated blockchain and database timing
            for (SingleFileRecord fileRecord : fileRecords) {
                // Set estimated blockchain upload time
                fileRecord.setBlockchainUploadDurationMs(estimatedBlockchainMsPerFile);

                // Set estimated database save time
                fileRecord.setDatabaseSaveDurationMs(estimatedDatabaseMsPerFile);

                // Mark as successful and add to test record
                fileRecord.finish(true);
                testRecord.addFileRecord(fileRecord);
            }

            batchRecord.finish(true);
            testRecord.finish(true);

            log.info("Real batch file test completed: {} files", files.size());
        } catch (Exception e) {
            log.error("Real batch file test failed", e);
            batchRecord.setErrorMessage(e.getMessage());
            batchRecord.finish(false);
            testRecord.addFileRecord(batchRecord);
            testRecord.setErrorMessage(e.getMessage());
            testRecord.finish(false);
        }

        return testRecord;
    }

    /**
     * Test different file sizes to analyze performance characteristics
     *
     * @return List of performance test records
     */
    public List<PerformanceTestRecord> testDifferentFileSizes() {
        List<PerformanceTestRecord> results = new ArrayList<>();

        try {
            // Small file (< 1KB)
            File smallFile = createTestFile("small-test.txt", 512);
            results.add(testSingleFileProtection(smallFile));

            // Medium file (1-10KB)
            File mediumFile = createTestFile("medium-test.txt", 5 * 1024);
            results.add(testSingleFileProtection(mediumFile));

            // Large file (> 10KB)
            File largeFile = createTestFile("large-test.txt", 50 * 1024);
            results.add(testSingleFileProtection(largeFile));

            log.info("Different file sizes test completed: {} tests", results.size());
        } catch (IOException e) {
            log.error("Failed to create test files", e);
        }

        return results;
    }

    /**
     * Protect file with detailed timing breakdown and Gas tracking
     * Uses FileMetadataFactory to ensure consistent hash calculation
     *
     * @param file       File to protect
     * @param fileRecord Record to track timing
     * @param testRecord Test record to populate Gas information
     * @return FileMetadata of protected file
     */
    private FileMetadata protectFileWithTiming(File file, SingleFileRecord fileRecord, PerformanceTestRecord testRecord)
            throws Exception {
        FileMetadata metadata;
        TransactionResult transactionResult;

        // Phase 1: Hash calculation (using FileMetadataFactory - DRY principle)
        fileRecord.startHashCalculation();
        try {
            metadata = FileMetadataFactory.createFileMetadata(file, uploader, "Performance test file");
        } finally {
            fileRecord.finishHashCalculation();
        }

        // Phase 2: Blockchain upload with Gas tracking
        fileRecord.startBlockchainUpload();
        try {
            ProtectionResultWithReceipt result =
                    fileProtectionService.protectFileToBlockchainWithReceipt(file, uploader, "Performance test file");
            metadata = result.metadata();
            transactionResult = result.transactionResult();

            // Update test record with Gas information if this was a new upload
            if (result.hasTransaction() && transactionResult != null) {
                testRecord.setGasUsed(transactionResult.getGasUsedLong());
                testRecord.setGasPrice(transactionResult.getGasPriceGwei());
                testRecord.setTotalGasCost(transactionResult.getTotalCostEth());
            }
        } finally {
            fileRecord.finishBlockchainUpload();
        }

        // Phase 3: Database save
        fileRecord.startDatabaseSave();
        try {
            fileProtectionService.saveFileMetadataToDatabase(metadata);
        } finally {
            fileRecord.finishDatabaseSave();
        }

        return metadata;
    }

    /**
     * Create a test file with specified size
     *
     * <p>Performance optimization: Generates random content once and repeats it for large files.
     * This is acceptable for testing purposes as the file hash will still be unique
     * (based on filename and size).
     *
     * @param fileName File name
     * @param sizeInBytes File size in bytes
     * @return Created test file
     * @throws IOException If file creation fails
     */
    public File createTestFile(String fileName, long sizeInBytes) throws IOException {
        Path testDir = Paths.get(testDirectory);
        if (!Files.exists(testDir)) {
            Files.createDirectories(testDir);
        }

        File testFile = new File(testDir.toFile(), fileName);

        // Generate random content once for performance (especially important for large files)
        byte[] buffer = new byte[4096];
        RANDOM.nextBytes(buffer); // Generate once, reuse for entire file
        long remaining = sizeInBytes;

        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            while (remaining > 0) {
                int bytesToWrite = (int) Math.min(buffer.length, remaining);
                fos.write(buffer, 0, bytesToWrite); // Reuse same buffer
                remaining -= bytesToWrite;
            }
        }

        createdTestFiles.add(testFile);
        log.debug("Created test file: {} ({} bytes)", fileName, sizeInBytes);

        return testFile;
    }

    /**
     * Create multiple test files with same size
     *
     * @param count       Number of files to create
     * @param sizeInBytes Size of each file in bytes
     * @return List of created test files
     * @throws IOException If file creation fails
     */
    public List<File> createTestFiles(int count, long sizeInBytes) throws IOException {
        List<File> files = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String fileName = String.format("test-file-%03d.dat", i + 1);
            files.add(createTestFile(fileName, sizeInBytes));
        }

        log.info("Created {} test files of size {} bytes each", count, sizeInBytes);
        return files;
    }

    /**
     * Clean up all created test files
     */
    public void cleanupTestFiles() {
        int deletedCount = 0;
        int failedCount = 0;

        for (File file : createdTestFiles) {
            try {
                if (file.exists() && file.delete()) {
                    deletedCount++;
                } else {
                    failedCount++;
                }
            } catch (Exception e) {
                log.warn("Failed to delete test file: {}", file.getName(), e);
                failedCount++;
            }
        }

        createdTestFiles.clear();

        log.info("Cleanup completed: {} files deleted, {} failed", deletedCount, failedCount);

        // Try to delete test directory if empty
        try {
            Path testDir = Paths.get(testDirectory);
            if (Files.exists(testDir) && Files.list(testDir).findAny().isEmpty()) {
                Files.delete(testDir);
                log.debug("Deleted empty test directory: {}", testDirectory);
            }
        } catch (IOException e) {
            log.debug("Could not delete test directory: {}", testDirectory);
        }
    }

    /**
     * Generate a unique test ID
     *
     * @return Test ID string
     */
    private String generateTestId() {
        return "PERF-" + System.currentTimeMillis();
    }
}
