package org.gslab.fileProtection.performance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.gslab.fileProtection.config.BlockchainFileProtectManagerConfig;
import org.gslab.fileProtection.config.BlockchainServiceConfig;
import org.gslab.fileProtection.config.DatabaseConfig;
import org.gslab.fileProtection.database.DatabaseManager;
import org.gslab.fileProtection.database.impl.SQLiteDatabaseManagerImpl;
import org.gslab.fileProtection.performance.ReportWriterFactory.ReportFormat;
import org.gslab.fileProtection.performance.impl.CsvReportWriter;
import org.gslab.fileProtection.service.BlockchainService;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.fileProtection.service.impl.Web3jBlockchainServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sepolia Performance Test - Real blockchain performance testing scenarios
 *
 * <p>This test class executes performance tests against the Sepolia testnet
 * to measure real-world blockchain file protection performance.
 *
 * <p>Configuration (via environment variables):
 * <ul>
 *   <li>SEPOLIA_RPC_URL - Sepolia RPC endpoint</li>
 *   <li>SEPOLIA_PRIVATE_KEY - Private key for transactions</li>
 *   <li>SEPOLIA_CONTRACT_ADDRESS - Deployed contract address</li>
 * </ul>
 *
 * <p>Test scenarios:
 * <ul>
 *   <li>Single file tests (small, medium, large sizes)</li>
 *   <li>Batch file tests (5, 10, 20 files)</li>
 *   <li>Time breakdown analysis (hash, blockchain, database)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SepoliaPerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(SepoliaPerformanceTest.class);
    private static final String BLOCKCHAIN_NAME = "Sepolia";
    private static final String UPLOADER = "performance-tester";
    private static final String REPORT_DIR = "test-results/performance/sepolia";

    private FileProtectionService fileProtectionService;
    private BlockchainPerformanceTester tester;
    private PerformanceReportWriter jsonWriter;
    private PerformanceReportWriter csvWriter;
    private PerformanceReportWriter htmlWriter;
    private List<PerformanceTestRecord> allTestResults;

    @BeforeAll
    public void setup() {
        logger.info("Setting up Sepolia Performance Test...");

        // Read Sepolia configuration from environment
        String rpcUrl = System.getenv("SEPOLIA_RPC_URL");
        String privateKey = System.getenv("SEPOLIA_PRIVATE_KEY");
        String contractAddress = System.getenv("SEPOLIA_CONTRACT_ADDRESS");
        String networkId = "11155111"; // Sepolia network ID

        if (rpcUrl == null || privateKey == null || contractAddress == null) {
            logger.warn("Sepolia configuration not found in environment variables");
            logger.warn("Please set: SEPOLIA_RPC_URL, SEPOLIA_PRIVATE_KEY, SEPOLIA_CONTRACT_ADDRESS");
            logger.warn("Tests will be skipped");
            return;
        }

        try {
            // Create configuration
            BlockchainServiceConfig blockchainConfig = new BlockchainServiceConfig();
            blockchainConfig.setRpcUrl(rpcUrl);
            blockchainConfig.setPrivateKey(privateKey);
            blockchainConfig.setContractAddress(contractAddress);
            blockchainConfig.setNetworkId(networkId);
            blockchainConfig.setPollingAttempts(60);
            blockchainConfig.setPollingInterval(1000);

            DatabaseConfig databaseConfig = new DatabaseConfig();
            databaseConfig.setUrl("jdbc:sqlite:performance-test.db");

            BlockchainFileProtectManagerConfig config = new BlockchainFileProtectManagerConfig();
            config.setBlockchainServiceConfig(blockchainConfig);
            config.setDatabaseConfig(databaseConfig);

            // Initialize services
            BlockchainService blockchainService = new Web3jBlockchainServiceImpl(blockchainConfig);
            DatabaseManager databaseManager = new SQLiteDatabaseManagerImpl(databaseConfig);

            fileProtectionService = new FileProtectionService(blockchainService, databaseManager, config);

            // Initialize performance tester and report writers
            tester = new BlockchainPerformanceTester(fileProtectionService, BLOCKCHAIN_NAME, UPLOADER);
            jsonWriter = ReportWriterFactory.create(ReportFormat.JSON);
            csvWriter = ReportWriterFactory.create(ReportFormat.CSV_APPEND);
            htmlWriter = ReportWriterFactory.create(ReportFormat.HTML);
            allTestResults = new ArrayList<>();

            logger.info("Sepolia Performance Test setup completed");
        } catch (Exception e) {
            logger.error("Failed to setup Sepolia Performance Test", e);
            throw new RuntimeException("Setup failed", e);
        }
    }

    @Test
    public void testSingleFile_100KB() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Single File - 100KB");

        File testFile = tester.createTestFile("single-100KB.txt", 100 * 1024);
        PerformanceTestRecord record = tester.testSingleFileProtection(testFile);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "single-100KB");
    }

    @Test
    public void testSingleFile_1MB() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Single File - 1MB");

        File testFile = tester.createTestFile("single-1MB.txt", 1024 * 1024);
        PerformanceTestRecord record = tester.testSingleFileProtection(testFile);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "single-1MB");
    }

    @Test
    @Tag("medium")
    public void testSingleFile_10MB() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Single File - 10MB");

        File testFile = tester.createTestFile("single-10MB.txt", 10L * 1024 * 1024);
        PerformanceTestRecord record = tester.testSingleFileProtection(testFile);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "single-10MB");
    }

    @Test
    @Tag("slow")
    public void testSingleFile_100MB() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Single File - 100MB");

        File testFile = tester.createTestFile("single-100MB.txt", 100L * 1024 * 1024);
        PerformanceTestRecord record = tester.testSingleFileProtection(testFile);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "single-100MB");
    }

    @Test
    @Tag("slow")
    public void testSingleFile_1GB() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Single File - 1GB");

        File testFile = tester.createTestFile("single-1G.txt", (long) 1024 * 1024 * 1024);
        PerformanceTestRecord record = tester.testSingleFileProtection(testFile);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "single-1GB");
    }

    @Test
    public void testBatchFiles_5Files() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Batch Files - 5 Files");

        List<File> testFiles = tester.createTestFiles(5, 2 * 1024);
        PerformanceTestRecord record = tester.testBatchFileProtection(testFiles);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "batch-5");
    }

    @Test
    @Tag("medium")
    public void testBatchFiles_10Files() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Batch Files - 10 Files");

        List<File> testFiles = tester.createTestFiles(10, 2 * 1024);
        PerformanceTestRecord record = tester.testBatchFileProtection(testFiles);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "batch-10");
    }

    @Test
    @Tag("slow")
    public void testBatchFiles_20Files() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Batch Files - 20 Files");

        List<File> testFiles = tester.createTestFiles(20, 2 * 1024);
        PerformanceTestRecord record = tester.testBatchFileProtection(testFiles);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "batch-20");
    }

    @Test
    public void testRealBatch_5Files() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Real Batch (Atomic) - 5 Files");

        List<File> testFiles = tester.createTestFiles(5, 2 * 1024);
        PerformanceTestRecord record = tester.testRealBatchFileProtection(testFiles);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "real-batch-5");

        logger.info("NOTE: This test uses batchProtectFileToBlockchain for true atomic batch operation");
    }

    @Test
    @Tag("medium")
    public void testRealBatch_10Files() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Real Batch (Atomic) - 10 Files");

        List<File> testFiles = tester.createTestFiles(10, 2 * 1024);
        PerformanceTestRecord record = tester.testRealBatchFileProtection(testFiles);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "real-batch-10");

        logger.info("NOTE: This test uses batchProtectFileToBlockchain for true atomic batch operation");
    }

    @Test
    @Tag("medium")
    public void testRealBatch_20Files() throws IOException {
        if (tester == null) {
            logger.warn("Test skipped: Sepolia configuration not available");
            return;
        }

        logger.info("Starting test: Real Batch (Atomic) - 20 Files");

        List<File> testFiles = tester.createTestFiles(20, 2 * 1024);
        PerformanceTestRecord record = tester.testRealBatchFileProtection(testFiles);

        logger.debug("Real Batch 20 Files Test Record: {}", record);

        allTestResults.add(record);
        printTestSummary(record);
        saveTestResult(record, "real-batch-20");

        logger.info("NOTE: This test uses batchProtectFileToBlockchain for true atomic batch operation");
    }

    @AfterAll
    public void cleanup() {
        logger.info("Cleaning up Sepolia Performance Test...");

        if (tester != null) {
            tester.cleanupTestFiles();
        }

        // Save consolidated reports
        if (!allTestResults.isEmpty()) {
            try {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path csvPath = Path.of(REPORT_DIR, "sepolia-performance-" + timestamp + ".csv");
                Path htmlPath = Path.of(REPORT_DIR, "sepolia-performance-" + timestamp + ".html");

                // Save consolidated CSV report (overwrite mode for final report)
                PerformanceReportWriter consolidatedCsvWriter = new CsvReportWriter(false);
                consolidatedCsvWriter.saveAll(allTestResults, csvPath);
                logger.info("Consolidated CSV report saved: {}", csvPath);

                // Save consolidated HTML report
                // htmlWriter.saveAll(allTestResults, htmlPath);
                // logger.info("Consolidated HTML report saved: {}", htmlPath);

                // Print overall summary
                printOverallSummary();
            } catch (IOException e) {
                logger.error("Failed to save consolidated report", e);
            }
        }

        if (fileProtectionService != null) {
            try {
                fileProtectionService.close();
            } catch (Exception e) {
                logger.error("Error closing file protection service", e);
            }
        }

        logger.info("Cleanup completed");
    }

    /**
     * Print test summary to console
     */
    private void printTestSummary(PerformanceTestRecord record) {
        System.out.println("\n" + record.getSummary());
    }

    /**
     * Print overall test summary
     */
    private void printOverallSummary() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  總體性能測試摘要 - Sepolia");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  總測試數: %d%n", allTestResults.size());

        long successCount =
                allTestResults.stream().filter(PerformanceTestRecord::isSuccess).count();
        System.out.printf("║  成功: %d / 失敗: %d%n", successCount, allTestResults.size() - successCount);

        long totalFiles = allTestResults.stream()
                .mapToLong(PerformanceTestRecord::getFileCount)
                .sum();
        System.out.printf("║  總文件數: %d%n", totalFiles);

        long totalDuration = allTestResults.stream()
                .mapToLong(PerformanceTestRecord::getDurationMs)
                .sum();
        System.out.printf("║  總耗時: %.2f 秒%n", totalDuration / 1000.0);
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");
    }

    /**
     * Save test result to JSON and CSV
     */
    private void saveTestResult(PerformanceTestRecord record, String testName) {
        try {
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

            // Save to JSON (individual test result)
            Path jsonPath = Path.of(REPORT_DIR, testName + "-" + timestamp + ".json");
            jsonWriter.save(record, jsonPath);

            // Append to incremental CSV
            Path csvPath = Path.of(REPORT_DIR, "all-tests.csv");
            csvWriter.save(record, csvPath);

            logger.info("Test result saved: {} (JSON and CSV)", testName);
        } catch (IOException e) {
            logger.error("Failed to save test result: {}", testName, e);
        }
    }
}
