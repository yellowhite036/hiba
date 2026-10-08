package org.gslab.fileProtection.performance;

import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Performance Test Record - Tracks detailed performance metrics for blockchain file protection operations
 *
 * <p>Follows Clean Code principles:
 * <ul>
 *   <li>Single Responsibility: Records and calculates performance metrics only</li>
 *   <li>Clear naming: All field names reveal their purpose</li>
 *   <li>Small methods: Each method does one thing well</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Time breakdown tracking (hash calculation, blockchain upload, database save)</li>
 *   <li>Performance metrics calculation (throughput, transfer rates)</li>
 *   <li>Gas cost tracking for blockchain operations</li>
 *   <li>Detailed and summary report generation</li>
 * </ul>
 */
public class PerformanceTestRecord {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0.00");

    // Test identification
    private final String testId;
    private final String testName;
    private final String blockchain;

    // Time tracking
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long durationMs;

    // Test parameters
    private final boolean isBatchOperation;
    private long totalFileSize;

    // Test results
    private boolean success;
    private String errorMessage;
    private int successCount;
    private int failureCount;

    // Performance metrics
    private double averageTimePerFile;
    private long minTimePerFile;
    private long maxTimePerFile;
    private double filesPerSecond;
    private double bytesPerSecond;

    // Time breakdown statistics
    private long totalHashCalculationMs;
    private long totalBlockchainUploadMs;
    private long totalDatabaseSaveMs;
    private double averageHashCalculationMs;
    private double averageBlockchainUploadMs;
    private double averageDatabaseSaveMs;
    private double hashCalculationPercentage;
    private double blockchainUploadPercentage;
    private double databaseSavePercentage;

    // Blockchain information
    private String transactionHash;
    private long gasUsed;
    private double gasPrice; // in Gwei
    private double totalGasCost; // in ETH

    // Individual file records
    private final List<SingleFileRecord> fileRecords;

    public PerformanceTestRecord(String testId, String testName, String blockchain, boolean isBatchOperation) {
        this.testId = Objects.requireNonNull(testId, "testId cannot be null");
        this.testName = Objects.requireNonNull(testName, "testName cannot be null");
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain cannot be null");
        this.isBatchOperation = isBatchOperation;
        this.fileRecords = new ArrayList<>();
        this.minTimePerFile = Long.MAX_VALUE;
        this.maxTimePerFile = Long.MIN_VALUE;
    }

    /**
     * Start the test - record start time
     */
    public void start() {
        this.startTime = LocalDateTime.now();
    }

    /**
     * Finish the test - record end time and calculate all metrics
     *
     * @param success Whether the test succeeded
     */
    public void finish(boolean success) {
        this.endTime = LocalDateTime.now();
        this.success = success;
        calculateMetrics();
        calculateTimeBreakdown();
    }

    /**
     * Add a single file record to this test
     *
     * @param record The file record to add
     */
    public void addFileRecord(SingleFileRecord record) {
        if (record == null) {
            return;
        }
        fileRecords.add(record);
        totalFileSize += record.fileSize;

        if (record.success) {
            successCount++;
        } else {
            failureCount++;
        }
    }

    /**
     * Calculate performance metrics based on collected file records
     */
    private void calculateMetrics() {
        if (startTime == null || endTime == null) {
            return;
        }

        durationMs = java.time.Duration.between(startTime, endTime).toMillis();

        int totalFiles = fileRecords.size();
        if (totalFiles == 0) {
            return;
        }

        // Calculate average, min, max time per file
        long totalTime = 0;
        for (SingleFileRecord record : fileRecords) {
            totalTime += record.durationMs;
            if (record.durationMs < minTimePerFile) {
                minTimePerFile = record.durationMs;
            }
            if (record.durationMs > maxTimePerFile) {
                maxTimePerFile = record.durationMs;
            }
        }

        averageTimePerFile = (double) totalTime / totalFiles;

        // Calculate throughput
        if (durationMs > 0) {
            filesPerSecond = (totalFiles * 1000.0) / durationMs;
            bytesPerSecond = (totalFileSize * 1000.0) / durationMs;
        }
    }

    /**
     * Calculate time breakdown percentages for different operation phases
     */
    private void calculateTimeBreakdown() {
        if (fileRecords.isEmpty()) {
            return;
        }

        // Sum up all phase durations
        for (SingleFileRecord record : fileRecords) {
            totalHashCalculationMs += record.hashCalculationDurationMs;
            totalBlockchainUploadMs += record.blockchainUploadDurationMs;
            totalDatabaseSaveMs += record.databaseSaveDurationMs;
        }

        int fileCount = fileRecords.size();
        averageHashCalculationMs = (double) totalHashCalculationMs / fileCount;
        averageBlockchainUploadMs = (double) totalBlockchainUploadMs / fileCount;
        averageDatabaseSaveMs = (double) totalDatabaseSaveMs / fileCount;

        // Calculate percentages
        long totalPhaseTime = totalHashCalculationMs + totalBlockchainUploadMs + totalDatabaseSaveMs;
        if (totalPhaseTime > 0) {
            hashCalculationPercentage = (totalHashCalculationMs * 100.0) / totalPhaseTime;
            blockchainUploadPercentage = (totalBlockchainUploadMs * 100.0) / totalPhaseTime;
            databaseSavePercentage = (totalDatabaseSaveMs * 100.0) / totalPhaseTime;
        }
    }

    /**
     * Generate a formatted summary report
     *
     * @return Formatted summary string
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║  性能測試報告: %s%n", testName));
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  測試 ID: %s%n", testId));
        sb.append(String.format("║  區塊鏈: %s%n", blockchain));
        sb.append(String.format("║  開始時間: %s%n", startTime != null ? startTime.format(TIME_FORMATTER) : "N/A"));
        sb.append(String.format("║  結束時間: %s%n", endTime != null ? endTime.format(TIME_FORMATTER) : "N/A"));
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  總耗時: %,d ms (%.2f 秒)%n", durationMs, durationMs / 1000.0));
        sb.append(String.format("║  文件數量: %d%n", fileRecords.size()));
        sb.append(String.format("║  總大小: %s%n", formatFileSize(totalFileSize)));
        sb.append(String.format("║  批量操作: %s%n", isBatchOperation ? "是" : "否"));
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  成功: %d / 失敗: %d%n", successCount, failureCount));

        int totalCount = successCount + failureCount;
        double successRate = totalCount > 0 ? (successCount * 100.0) / totalCount : 0;
        sb.append(String.format("║  成功率: %.2f%%%n", successRate));
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");

        if (fileRecords.size() > 0) {
            sb.append(String.format("║  平均耗時/文件: %s ms%n", DECIMAL_FORMAT.format(averageTimePerFile)));
            sb.append(String.format("║  最快: %,d ms%n", minTimePerFile != Long.MAX_VALUE ? minTimePerFile : 0));
            sb.append(String.format("║  最慢: %,d ms%n", maxTimePerFile != Long.MIN_VALUE ? maxTimePerFile : 0));
            sb.append(String.format("║  吞吐量: %.2f 文件/秒%n", filesPerSecond));
            sb.append(String.format("║  傳輸速率: %s/秒%n", formatFileSize((long) bytesPerSecond)));
        }

        // Time breakdown
        if (totalHashCalculationMs > 0 || totalBlockchainUploadMs > 0 || totalDatabaseSaveMs > 0) {
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append("║  ⏱️  時間分段分析:\n");
            sb.append(String.format(
                    "║    • Hash 計算: %,d ms (%.2f%%)%n", totalHashCalculationMs, hashCalculationPercentage));

            String bottleneckMarker = blockchainUploadPercentage > 50 ? " ⚠️ 主要瓶頸" : "";
            sb.append(String.format(
                    "║    • 區塊鏈上傳: %,d ms (%.2f%%)%s%n",
                    totalBlockchainUploadMs, blockchainUploadPercentage, bottleneckMarker));

            if (totalDatabaseSaveMs > 0) {
                sb.append(
                        String.format("║    • 數據庫保存: %,d ms (%.2f%%)%n", totalDatabaseSaveMs, databaseSavePercentage));
            }
        }

        // Gas information
        if (gasUsed > 0) {
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append(String.format("║  Gas 使用: %,d%n", gasUsed));
            sb.append(String.format("║  Gas 價格: %.2f Gwei%n", gasPrice));
            sb.append(String.format("║  總 Gas 成本: %.6f ETH%n", totalGasCost));
        }

        sb.append("╚══════════════════════════════════════════════════════════════╝\n");

        return sb.toString();
    }

    /**
     * Generate detailed report including individual file records
     *
     * @return Detailed report string
     */
    public String getDetailedSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(getSummary());
        sb.append("\n單個文件記錄:\n");
        sb.append("═══════════════════════════════════════════════════════════════\n");

        for (int i = 0; i < fileRecords.size(); i++) {
            SingleFileRecord record = fileRecords.get(i);
            sb.append(String.format("\n[%d] %s (%s)%n", i + 1, record.fileName, formatFileSize(record.fileSize)));
            sb.append(record.getTimeBreakdown());
            sb.append(String.format(
                    "    總耗時: %,d ms %s%n", record.durationMs, record.success ? "✓" : "✗ " + record.errorMessage));
        }

        return sb.toString();
    }

    /**
     * Generate CSV row for this test record
     *
     * @return CSV formatted string
     */
    public String toCsvRow() {
        return String.format(
                "%s,%s,%s,%s,%s,%d,%d,%d,%s,%d,%d,%.2f,%.2f,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d,%.2f,%.2f,%d,%.2f,%.6f,%s,%s",
                testId,
                testName,
                blockchain,
                startTime != null ? startTime.format(TIME_FORMATTER) : "",
                endTime != null ? endTime.format(TIME_FORMATTER) : "",
                durationMs,
                fileRecords.size(),
                totalFileSize,
                isBatchOperation ? "是" : "否",
                successCount,
                failureCount,
                (successCount + failureCount) > 0 ? (successCount * 100.0) / (successCount + failureCount) : 0,
                averageTimePerFile,
                minTimePerFile != Long.MAX_VALUE ? minTimePerFile : 0,
                maxTimePerFile != Long.MIN_VALUE ? maxTimePerFile : 0,
                filesPerSecond,
                bytesPerSecond,
                averageHashCalculationMs,
                averageBlockchainUploadMs,
                averageDatabaseSaveMs,
                totalHashCalculationMs,
                totalBlockchainUploadMs,
                hashCalculationPercentage,
                blockchainUploadPercentage,
                gasUsed,
                gasPrice,
                totalGasCost,
                success ? "成功" : "失敗",
                errorMessage != null ? errorMessage.replace(",", ";") : "");
    }

    /**
     * Get CSV header for test records
     *
     * @return CSV header string
     */
    public static String getCsvHeader() {
        return "測試ID,測試名稱,區塊鏈,開始時間,結束時間,總耗時(ms),文件數量,總大小(bytes)," + "批量操作,成功數,失敗數,成功率(%),平均耗時/文件(ms),最小耗時(ms),最大耗時(ms),"
                + "吞吐量(文件/秒),傳輸速率(bytes/秒),平均Hash計算(ms),平均區塊鏈上傳(ms),"
                + "平均數據庫保存(ms),總Hash計算(ms),總區塊鏈上傳(ms),Hash計算佔比(%),"
                + "區塊鏈上傳佔比(%),Gas使用,Gas價格(Gwei),總Gas成本(ETH),狀態,錯誤信息";
    }

    /**
     * Format file size in human-readable format
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    // Setters for blockchain information
    public void setTransactionHash(String transactionHash) {
        this.transactionHash = transactionHash;
    }

    public long getGasUsed() {
        return gasUsed;
    }

    public void setGasUsed(long gasUsed) {
        this.gasUsed = gasUsed;
    }

    public double getGasPrice() {
        return gasPrice;
    }

    public void setGasPrice(double gasPrice) {
        this.gasPrice = gasPrice;
    }

    public double getTotalGasCost() {
        return totalGasCost;
    }

    public void setTotalGasCost(double totalGasCost) {
        this.totalGasCost = totalGasCost;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // Getters
    public String getTestId() {
        return testId;
    }

    public String getTestName() {
        return testName;
    }

    public boolean isSuccess() {
        return success;
    }

    public int getFileCount() {
        return fileRecords.size();
    }

    public long getDurationMs() {
        return durationMs;
    }

    /**
     * Single File Record - Tracks performance metrics for a single file operation
     *
     * <p>Inner class with clear responsibility: track timing for individual file operations
     */
    public static class SingleFileRecord {

        private final String fileName;
        private final long fileSize;

        // Overall timing
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private long durationMs;

        // Phase timing
        private LocalDateTime hashCalculationStartTime;
        private LocalDateTime hashCalculationEndTime;
        private long hashCalculationDurationMs;

        private LocalDateTime blockchainUploadStartTime;
        private LocalDateTime blockchainUploadEndTime;
        private long blockchainUploadDurationMs;

        private LocalDateTime databaseSaveStartTime;
        private LocalDateTime databaseSaveEndTime;
        private long databaseSaveDurationMs;

        // Result
        private boolean success;
        private String errorMessage;
        private String transactionHash;

        public SingleFileRecord(String fileName, long fileSize) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.startTime = LocalDateTime.now();
        }

        public void startHashCalculation() {
            this.hashCalculationStartTime = LocalDateTime.now();
        }

        public void finishHashCalculation() {
            this.hashCalculationEndTime = LocalDateTime.now();
            if (hashCalculationStartTime != null) {
                this.hashCalculationDurationMs = java.time.Duration.between(
                                hashCalculationStartTime, hashCalculationEndTime)
                        .toMillis();
            }
        }

        public void startBlockchainUpload() {
            this.blockchainUploadStartTime = LocalDateTime.now();
        }

        public void finishBlockchainUpload() {
            this.blockchainUploadEndTime = LocalDateTime.now();
            if (blockchainUploadStartTime != null) {
                this.blockchainUploadDurationMs = java.time.Duration.between(
                                blockchainUploadStartTime, blockchainUploadEndTime)
                        .toMillis();
            }
        }

        public void startDatabaseSave() {
            this.databaseSaveStartTime = LocalDateTime.now();
        }

        public void finishDatabaseSave() {
            this.databaseSaveEndTime = LocalDateTime.now();
            if (databaseSaveStartTime != null) {
                this.databaseSaveDurationMs = java.time.Duration.between(databaseSaveStartTime, databaseSaveEndTime)
                        .toMillis();
            }
        }

        public void finish(boolean success) {
            this.endTime = LocalDateTime.now();
            this.success = success;
            if (startTime != null && endTime != null) {
                this.durationMs = java.time.Duration.between(startTime, endTime).toMillis();
            }
        }

        public String getTimeBreakdown() {
            StringBuilder sb = new StringBuilder();
            if (hashCalculationDurationMs > 0) {
                sb.append(String.format("    ├─ Hash 計算: %,d ms%n", hashCalculationDurationMs));
            }
            if (blockchainUploadDurationMs > 0) {
                sb.append(String.format("    ├─ 區塊鏈上傳: %,d ms%n", blockchainUploadDurationMs));
                if (transactionHash != null) {
                    sb.append(String.format("    │  └─ 交易 Hash: %s%n", transactionHash));
                }
            }
            if (databaseSaveDurationMs > 0) {
                sb.append(String.format("    └─ 數據庫保存: %,d ms%n", databaseSaveDurationMs));
            }
            return sb.toString();
        }

        // Setters
        public void setTransactionHash(String transactionHash) {
            this.transactionHash = transactionHash;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        /**
         * Set blockchain upload duration directly (for estimated timing in batch operations)
         *
         * @param durationMs duration in milliseconds
         */
        public void setBlockchainUploadDurationMs(long durationMs) {
            this.blockchainUploadDurationMs = durationMs;
        }

        /**
         * Set database save duration directly (for estimated timing in batch operations)
         *
         * @param durationMs duration in milliseconds
         */
        public void setDatabaseSaveDurationMs(long durationMs) {
            this.databaseSaveDurationMs = durationMs;
        }

        // Getters
        public String getFileName() {
            return fileName;
        }

        public long getFileSize() {
            return fileSize;
        }

        public boolean isSuccess() {
            return success;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public long getHashCalculationDurationMs() {
            return hashCalculationDurationMs;
        }

        public long getBlockchainUploadDurationMs() {
            return blockchainUploadDurationMs;
        }

        public long getDatabaseSaveDurationMs() {
            return databaseSaveDurationMs;
        }
    }
}
