package org.gslab.tpm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Builder;
import lombok.Getter;
import org.gslab.configuration.MessageVerificationConfig;
import org.gslab.configuration.TPMDeviceRegistryConfig;
import org.gslab.handlers.NonceValidator;
import org.gslab.handlers.TimestampValidator;
import org.gslab.model.MessageVerificationResult;
import org.gslab.tpm.blockchain.Web3BlockchainService;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * TPM 效能測試
 *
 * <p>
 * 此測試提供量化數據以評估 TPM 驗證機制的效能表現。
 * 測試結果可用於碩士論文中的效能分析章節。
 * </p>
 *
 * <h2>測試項目</h2>
 * <ol>
 * <li><b>整體驗證延遲：</b>1000 次驗證的統計數據（平均值、標準差、P95）</li>
 * <li><b>各階段耗時分析：</b>四個驗證階段的個別耗時</li>
 * <li><b>吞吐量測試：</b>每秒可處理的驗證請求數</li>
 * <li><b>Nonce 快取效能：</b>不同快取大小下的查詢延遲</li>
 * </ol>
 *
 * @author TPM Test Project
 */
class TPMPerformanceTest {

    private static final String KEY_ALGORITHM = "RSA";

    // 系統屬性：是否使用真實區塊鏈（預設 false，使用 Mock）
    private static final boolean USE_BLOCKCHAIN = Boolean.getBoolean("tpm.test.use.blockchain");

    // 系統屬性：是否使用公鑰快取（預設 false，每次查詢區塊鏈）
    private static final boolean USE_CACHE = Boolean.getBoolean("tpm.test.use.cache");

    // 原始 TPM 簽章資料
    private JSONObject tpmSignatureInfo;
    private String originalSignature;
    private String originalPublicKey;
    private String originalMessageData;
    private String originalDeviceId;

    // 驗證器
    private TimestampValidator timestampValidator;
    private NonceValidator nonceValidator;

    // 公鑰註冊表（可為 Mock 或真實區塊鏈）
    private PublicKeyRegistry publicKeyRegistry;

    // 區塊鏈服務（僅在真實區塊鏈模式時使用）
    private Web3BlockchainService blockchainService;

    private static final String JSON_RESOURCE_PATH =
            System.getProperty("tpm.test.json", "/tpm_verification_sample.json");

    @BeforeEach
    void setUp() throws Exception {
        InputStream is;
        if (JSON_RESOURCE_PATH.startsWith("/") && Files.exists(Path.of(JSON_RESOURCE_PATH))) {
            is = new FileInputStream(JSON_RESOURCE_PATH);
        } else {
            is = getClass().getResourceAsStream(JSON_RESOURCE_PATH);
        }
        assertThat(is).as("測試資源檔案應該存在: " + JSON_RESOURCE_PATH).isNotNull();

        String jsonContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        JSONObject verificationData = new JSONObject(jsonContent);
        tpmSignatureInfo = verificationData.getJSONObject("tpmSignatureInfo");

        originalSignature = tpmSignatureInfo.getString("signature");
        originalPublicKey = tpmSignatureInfo.getString("publicKey");
        originalMessageData = tpmSignatureInfo.getString("messageData");
        originalDeviceId = tpmSignatureInfo.getString("deviceId");

        MessageVerificationConfig config = MessageVerificationConfig.defaultConfig();
        timestampValidator = new TimestampValidator(config);
        nonceValidator = NonceValidator.getInstance();
        nonceValidator.clear();

        // 根據系統屬性選擇使用 Mock 或真實區塊鏈
        if (USE_BLOCKCHAIN) {
            initRealBlockchain();
        } else {
            initMockBlockchain();
        }

        // 預熱 JVM
        warmUp();
    }

    /**
     * 初始化 Mock 區塊鏈註冊表
     */
    private void initMockBlockchain() {
        System.out.println("[設定] 使用 Mock 區塊鏈模式");
        MockBlockchainRegistry mockRegistry = new MockBlockchainRegistry();
        mockRegistry.registerDevice(originalDeviceId, originalPublicKey);
        publicKeyRegistry = mockRegistry;
    }

    /**
     * 初始化真實區塊鏈連線
     */
    private void initRealBlockchain() {
        System.out.println("[設定] 使用真實區塊鏈模式");
        System.out.println("   快取模式: " + (USE_CACHE ? "啟用" : "停用"));
        try {
            TPMDeviceRegistryConfig blockchainConfig = TPMDeviceRegistryConfig.load();

            // 根據 USE_CACHE 決定是否注入快取服務
            if (USE_CACHE) {
                blockchainService = new Web3BlockchainService.Builder()
                        .rpcUrl(blockchainConfig.getRpcUrl())
                        .contractAddress(blockchainConfig.getContractAddress())
                        .privateKey(blockchainConfig.getPrivateKey())
                        .networkId(String.valueOf(blockchainConfig.getChainId()))
                        .gasPrice(blockchainConfig.getGasPrice())
                        .gasLimit(blockchainConfig.getGasLimit())
                        .pollingInterval(blockchainConfig.getPollingIntervalMs())
                        .pollingAttempts(blockchainConfig.getPollingAttempts())
                        .cacheService(new PublicKeyCacheService())
                        .build();
            } else {
                blockchainService = blockchainConfig.createService();
            }
            publicKeyRegistry = new RealBlockchainRegistry(blockchainService);

            System.out.println("   RPC URL: " + blockchainConfig.getRpcUrl());
            System.out.println("   合約地址: " + blockchainConfig.getContractAddress());
            System.out.println("   區塊鏈連線成功");
        } catch (Exception e) {
            System.err.println("[警告] 區塊鏈連線失敗，回退到 Mock 模式: " + e.getMessage());
            initMockBlockchain();
        }
    }

    @AfterEach
    void tearDown() {
        nonceValidator.clear();
        // 關閉區塊鏈服務（如果有的話）
        if (blockchainService != null) {
            blockchainService.close();
            blockchainService = null;
        }
    }

    /**
     * JVM 預熱，確保測試結果穩定
     */
    private void warmUp() throws Exception {
        for (int i = 0; i < 100; i++) {
            executeFullVerification(createValidScenario());
        }
        nonceValidator.clear();
    }

    @Nested
    @DisplayName("整體驗證延遲測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class OverallLatencyTests {

        @Test
        @Order(1)
        @DisplayName("1000 次驗證延遲統計")
        void testOverallVerificationLatency() throws Exception {
            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                      整體驗證延遲測試                                 ");
            System.out.println("========================================================================");
            printVerificationTargetInfo();

            int iterations = 1000;
            long[] latencies = new long[iterations];

            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                executeFullVerification(createValidScenario());
                latencies[i] = System.nanoTime() - start;
            }

            PerformanceStats stats = calculateStats(latencies);
            printStatisticsTable(stats, iterations);

            // 確保平均延遲在合理範圍內（少於 10ms）
            assertThat(stats.getMeanMs()).isLessThan(10.0);
        }
    }

    @Nested
    @DisplayName("各階段耗時分析")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class StageLatencyTests {

        @Test
        @Order(1)
        @DisplayName("四階段驗證耗時分佈")
        void testStageLatencyBreakdown() throws Exception {
            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                      各階段耗時分析                                   ");
            System.out.println("========================================================================");
            printVerificationTargetInfo();

            int iterations = 1000;
            StageLatencies[] results = new StageLatencies[iterations];

            for (int i = 0; i < iterations; i++) {
                results[i] = measureStageLatencies(createValidScenario());
            }

            printStageLatencyChart(results);

            // 驗證所有階段都有執行
            for (StageLatencies result : results) {
                assertThat(result.getTimestampLatencyNs()).isPositive();
                assertThat(result.getNonceLatencyNs()).isPositive();
                assertThat(result.getPublicKeyLatencyNs()).isPositive();
                assertThat(result.getSignatureLatencyNs()).isPositive();
            }
        }
    }

    @Nested
    @DisplayName("吞吐量測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ThroughputTests {

        @Test
        @Order(1)
        @DisplayName("每秒驗證請求數")
        void testThroughput() throws Exception {
            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                         吞吐量測試                                    ");
            System.out.println("========================================================================");
            printVerificationTargetInfo();

            int durationSeconds = 5;
            AtomicInteger completedVerifications = new AtomicInteger(0);

            long startTime = System.currentTimeMillis();
            long endTime = startTime + (durationSeconds * 1000L);

            while (System.currentTimeMillis() < endTime) {
                executeFullVerification(createValidScenario());
                completedVerifications.incrementAndGet();
            }

            long actualDuration = System.currentTimeMillis() - startTime;
            double throughput = completedVerifications.get() / (actualDuration / 1000.0);

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                         吞吐量測試結果                              │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf("│ 測試時長:           %,d 毫秒%n", actualDuration);
            System.out.printf("│ 完成驗證數:         %,d 次%n", completedVerifications.get());
            System.out.printf("│ 吞吐量:             %.2f 驗證/秒%n", throughput);
            System.out.printf("│ 平均延遲:           %.3f 毫秒/驗證%n", actualDuration / (double) completedVerifications.get());
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            // 確保吞吐量大於 100 次/秒
            assertThat(throughput).isGreaterThan(100.0);
        }
    }

    @Nested
    @DisplayName("Nonce 快取效能測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class NonceCacheTests {

        @Test
        @Order(1)
        @DisplayName("快取大小 1,000 筆時的查詢延遲")
        void testNonceCachePerformance_1K() {
            runCachePerformanceTest(1_000);
        }

        @Test
        @Order(2)
        @DisplayName("快取大小 10,000 筆時的查詢延遲")
        void testNonceCachePerformance_10K() {
            runCachePerformanceTest(10_000);
        }

        @Test
        @Order(3)
        @DisplayName("快取大小 100,000 筆時的查詢延遲")
        void testNonceCachePerformance_100K() {
            runCachePerformanceTest(100_000);
        }

        private void runCachePerformanceTest(int cacheSize) {
            System.out.println();
            System.out.println("========================================================================");
            System.out.printf("              Nonce 快取效能測試 (快取大小: %,d)                       %n", cacheSize);
            System.out.println("========================================================================");
            System.out.println();

            nonceValidator.clear();

            // 預填充快取
            System.out.printf("正在填充快取至 %,d 筆...%n", cacheSize);
            long fillStart = System.nanoTime();
            for (int i = 0; i < cacheSize; i++) {
                nonceValidator.validateAndRecord(NonceValidator.generateNonce());
            }
            long fillDuration = System.nanoTime() - fillStart;
            System.out.printf("快取填充完成，耗時: %.2f 毫秒%n%n", fillDuration / 1_000_000.0);

            // 測量查詢延遲（查詢新的 Nonce，不應存在於快取中）
            int queryIterations = 1000;
            long[] queryLatencies = new long[queryIterations];

            for (int i = 0; i < queryIterations; i++) {
                String newNonce = NonceValidator.generateNonce();
                long start = System.nanoTime();
                nonceValidator.isValid(newNonce);
                queryLatencies[i] = System.nanoTime() - start;
            }

            PerformanceStats stats = calculateStats(queryLatencies);

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                      Nonce 快取查詢效能                             │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf("│ 快取大小:           %,d 筆%n", cacheSize);
            System.out.printf("│ 查詢次數:           %,d 次%n", queryIterations);
            System.out.printf("│ 平均延遲:           %.4f 毫秒%n", stats.getMeanMs());
            System.out.printf("│ 標準差:             %.4f 毫秒%n", stats.getStdDevMs());
            System.out.printf("│ P50 (中位數):       %.4f 毫秒%n", stats.getP50Ms());
            System.out.printf("│ P95:                %.4f 毫秒%n", stats.getP95Ms());
            System.out.printf("│ P99:                %.4f 毫秒%n", stats.getP99Ms());
            System.out.printf("│ 預估記憶體:         ~%s%n", estimateMemory(cacheSize));
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            // 確保查詢延遲在合理範圍內（少於 1ms）
            assertThat(stats.getMeanMs()).isLessThan(1.0);

            nonceValidator.clear();
        }
    }

    // ==================== 輔助方法 ====================

    private VerificationScenario createValidScenario() {
        return VerificationScenario.builder()
                .timestamp(System.currentTimeMillis())
                .nonce(NonceValidator.generateNonce())
                .deviceId(originalDeviceId)
                .publicKey(originalPublicKey)
                .signature(originalSignature)
                .messageData(originalMessageData)
                .build();
    }

    /**
     * 輸出驗證測試目標資訊
     */
    private void printVerificationTargetInfo() {
        byte[] messageDataBytes = Base64.getDecoder().decode(originalMessageData);
        byte[] signatureBytes = Base64.getDecoder().decode(originalSignature);
        byte[] publicKeyBytes = Base64.getDecoder().decode(originalPublicKey);

        // 反序列化原始訊息
        String messageClassName = "N/A";
        String messageContent = "N/A";
        try {
            Object deserializedMessage = deserializeObject(messageDataBytes);
            messageClassName = deserializedMessage.getClass().getSimpleName();
            messageContent = deserializedMessage.toString();
            // 截斷過長的內容
            if (messageContent.length() > 200) {
                messageContent = messageContent.substring(0, 200) + "...";
            }
        } catch (Exception e) {
            messageContent = "反序列化失敗: " + e.getMessage();
        }

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                         測試目標資訊                                │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ 資料來源:           %s%n", JSON_RESOURCE_PATH);
        System.out.printf(
                "│ Device ID:          %s...%n",
                originalDeviceId.substring(0, Math.min(16, originalDeviceId.length())));
        System.out.println("├────────────────────────────── 原始訊息資訊 ───────────────────────────────┤");
        System.out.printf("│ 訊息類型:           %s%n", messageClassName);
        System.out.printf("│ messageData 大小:   %,d bytes (序列化資料)%n", messageDataBytes.length);
        System.out.printf("│ signature 大小:     %,d bytes (RSA 簽章)%n", signatureBytes.length);
        System.out.printf("│ publicKey 大小:     %,d bytes%n", publicKeyBytes.length);
        System.out.println("├────────────────────────────── 原始訊息內容 ───────────────────────────────┤");
        System.out.printf("│ %s%n", messageContent);
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    /**
     * 反序列化物件
     */
    private Object deserializeObject(byte[] data) throws Exception {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
                ObjectInputStream ois = new ObjectInputStream(bis)) {
            return ois.readObject();
        }
    }

    private boolean executeFullVerification(VerificationScenario scenario) throws Exception {
        // 階段 1：時間戳記驗證
        MessageVerificationResult timestampResult = timestampValidator.validate(scenario.getTimestamp());
        if (!timestampResult.isSuccess()) {
            return false;
        }

        // 階段 2：Nonce 驗證
        MessageVerificationResult nonceResult = nonceValidator.validateAndRecord(scenario.getNonce());
        if (!nonceResult.isSuccess()) {
            return false;
        }

        // 階段 3：公鑰驗證
        Optional<String> registeredPublicKey = publicKeyRegistry.getPublicKey(scenario.getDeviceId());
        if (registeredPublicKey.isEmpty() || !registeredPublicKey.get().equals(scenario.getPublicKey())) {
            return false;
        }

        // 階段 4：簽章驗證
        PublicKey publicKey = extractPublicKey(scenario.getPublicKey());
        byte[] messageData = Base64.getDecoder().decode(scenario.getMessageData());
        byte[] signature = Base64.getDecoder().decode(scenario.getSignature());

        return TPMService.verifyWithJavaCrypto(publicKey, messageData, signature);
    }

    private StageLatencies measureStageLatencies(VerificationScenario scenario) throws Exception {
        long timestampStart = System.nanoTime();
        MessageVerificationResult timestampResult = timestampValidator.validate(scenario.getTimestamp());
        long timestampEnd = System.nanoTime();

        long nonceStart = System.nanoTime();
        MessageVerificationResult nonceResult = nonceValidator.validateAndRecord(scenario.getNonce());
        long nonceEnd = System.nanoTime();

        long publicKeyStart = System.nanoTime();
        Optional<String> registeredPublicKey = publicKeyRegistry.getPublicKey(scenario.getDeviceId());
        boolean publicKeyValid =
                registeredPublicKey.isPresent() && registeredPublicKey.get().equals(scenario.getPublicKey());
        long publicKeyEnd = System.nanoTime();

        long signatureStart = System.nanoTime();
        PublicKey publicKey = extractPublicKey(scenario.getPublicKey());
        byte[] messageData = Base64.getDecoder().decode(scenario.getMessageData());
        byte[] signature = Base64.getDecoder().decode(scenario.getSignature());
        boolean signatureValid = TPMService.verifyWithJavaCrypto(publicKey, messageData, signature);
        long signatureEnd = System.nanoTime();

        return StageLatencies.builder()
                .timestampLatencyNs(timestampEnd - timestampStart)
                .nonceLatencyNs(nonceEnd - nonceStart)
                .publicKeyLatencyNs(publicKeyEnd - publicKeyStart)
                .signatureLatencyNs(signatureEnd - signatureStart)
                .build();
    }

    private PerformanceStats calculateStats(long[] latencies) {
        // 排序用於百分位數計算
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);

        // 計算平均值
        double sum = 0;
        for (long latency : latencies) {
            sum += latency;
        }
        double mean = sum / latencies.length;

        // 計算標準差
        double sumSquaredDiff = 0;
        for (long latency : latencies) {
            sumSquaredDiff += Math.pow(latency - mean, 2);
        }
        double stdDev = Math.sqrt(sumSquaredDiff / latencies.length);

        return PerformanceStats.builder()
                .meanNs(mean)
                .stdDevNs(stdDev)
                .minNs(sorted[0])
                .maxNs(sorted[sorted.length - 1])
                .p50Ns(sorted[(int) (sorted.length * 0.50)])
                .p95Ns(sorted[(int) (sorted.length * 0.95)])
                .p99Ns(sorted[(int) (sorted.length * 0.99)])
                .build();
    }

    private void printStatisticsTable(PerformanceStats stats, int samples) {
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                      整體驗證延遲統計                               │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ 樣本數:             %,d%n", samples);
        System.out.printf("│ 平均值:             %.3f 毫秒%n", stats.getMeanMs());
        System.out.printf("│ 標準差:             %.3f 毫秒%n", stats.getStdDevMs());
        System.out.printf("│ 最小值:             %.3f 毫秒%n", stats.getMinMs());
        System.out.printf("│ 最大值:             %.3f 毫秒%n", stats.getMaxMs());
        System.out.printf("│ P50 (中位數):       %.3f 毫秒%n", stats.getP50Ms());
        System.out.printf("│ P95:                %.3f 毫秒%n", stats.getP95Ms());
        System.out.printf("│ P99:                %.3f 毫秒%n", stats.getP99Ms());
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private void printStageLatencyChart(StageLatencies[] results) {
        // 計算各階段平均耗時
        double avgTimestamp = 0, avgNonce = 0, avgPublicKey = 0, avgSignature = 0;
        for (StageLatencies result : results) {
            avgTimestamp += result.getTimestampLatencyNs();
            avgNonce += result.getNonceLatencyNs();
            avgPublicKey += result.getPublicKeyLatencyNs();
            avgSignature += result.getSignatureLatencyNs();
        }
        avgTimestamp /= results.length;
        avgNonce /= results.length;
        avgPublicKey /= results.length;
        avgSignature /= results.length;

        double total = avgTimestamp + avgNonce + avgPublicKey + avgSignature;

        // 計算百分比
        double pctTimestamp = (avgTimestamp / total) * 100;
        double pctNonce = (avgNonce / total) * 100;
        double pctPublicKey = (avgPublicKey / total) * 100;
        double pctSignature = (avgSignature / total) * 100;

        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                      各階段耗時分析                                 │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 階段                │ 平均耗時      │ 佔比    │ 視覺化             │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf(
                "│ 1. 時間戳記驗證     │ %8.3f ms   │ %5.1f%%  │ %s%n",
                avgTimestamp / 1_000_000.0, pctTimestamp, generateBar(pctTimestamp));
        System.out.printf(
                "│ 2. Nonce 驗證       │ %8.3f ms   │ %5.1f%%  │ %s%n",
                avgNonce / 1_000_000.0, pctNonce, generateBar(pctNonce));
        System.out.printf(
                "│ 3. 公鑰驗證         │ %8.3f ms   │ %5.1f%%  │ %s%n",
                avgPublicKey / 1_000_000.0, pctPublicKey, generateBar(pctPublicKey));
        System.out.printf(
                "│ 4. 簽章驗證         │ %8.3f ms   │ %5.1f%%  │ %s%n",
                avgSignature / 1_000_000.0, pctSignature, generateBar(pctSignature));
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ 總計                │ %8.3f ms   │ 100.0%%  │                    │%n", total / 1_000_000.0);
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private String generateBar(double percentage) {
        int barLength = (int) (percentage / 5); // 每 5% 一個區塊
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < barLength; i++) {
            bar.append("█");
        }
        // 補足空白至固定長度
        while (bar.length() < 20) {
            bar.append(" ");
        }
        return bar.toString();
    }

    private String estimateMemory(int cacheSize) {
        // 估算每個 Nonce 條目約 50 bytes（22 字元字串 + Long + 物件開銷）
        long bytes = cacheSize * 50L;
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    private PublicKey extractPublicKey(String base64PublicKey) throws Exception {
        byte[] publicKeyBytes = Base64.getDecoder().decode(base64PublicKey);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return keyFactory.generatePublic(keySpec);
    }

    // ==================== 資料類別 ====================

    @Getter
    @Builder
    static class VerificationScenario {
        private long timestamp;
        private String nonce;
        private String deviceId;
        private String publicKey;
        private String signature;
        private String messageData;
    }

    @Getter
    @Builder
    static class StageLatencies {
        private long timestampLatencyNs;
        private long nonceLatencyNs;
        private long publicKeyLatencyNs;
        private long signatureLatencyNs;

        public long getTotalLatencyNs() {
            return timestampLatencyNs + nonceLatencyNs + publicKeyLatencyNs + signatureLatencyNs;
        }
    }

    @Getter
    @Builder
    static class PerformanceStats {
        private double meanNs;
        private double stdDevNs;
        private long minNs;
        private long maxNs;
        private long p50Ns;
        private long p95Ns;
        private long p99Ns;

        public double getMeanMs() {
            return meanNs / 1_000_000.0;
        }

        public double getStdDevMs() {
            return stdDevNs / 1_000_000.0;
        }

        public double getMinMs() {
            return minNs / 1_000_000.0;
        }

        public double getMaxMs() {
            return maxNs / 1_000_000.0;
        }

        public double getP50Ms() {
            return p50Ns / 1_000_000.0;
        }

        public double getP95Ms() {
            return p95Ns / 1_000_000.0;
        }

        public double getP99Ms() {
            return p99Ns / 1_000_000.0;
        }
    }

    /**
     * 公鑰註冊表介面（抽象化 Mock 和真實區塊鏈）
     */
    interface PublicKeyRegistry {
        Optional<String> getPublicKey(String deviceId);
    }

    /**
     * Mock 區塊鏈註冊表（預設）
     */
    static class MockBlockchainRegistry implements PublicKeyRegistry {
        private final java.util.Map<String, String> registeredDevices = new java.util.HashMap<>();

        void registerDevice(String deviceId, String publicKey) {
            registeredDevices.put(deviceId, publicKey);
        }

        @Override
        public Optional<String> getPublicKey(String deviceId) {
            return Optional.ofNullable(registeredDevices.get(deviceId));
        }
    }

    /**
     * 真實區塊鏈註冊表（從 blockchain.properties 讀取設定）
     */
    static class RealBlockchainRegistry implements PublicKeyRegistry {
        private final Web3BlockchainService blockchainService;

        RealBlockchainRegistry(Web3BlockchainService blockchainService) {
            this.blockchainService = blockchainService;
        }

        @Override
        public Optional<String> getPublicKey(String deviceId) {
            // 根據 USE_CACHE 參數決定是否使用快取
            if (USE_CACHE) {
                return blockchainService.getPublicKeyWithCache(deviceId).map(pk -> Base64.getEncoder()
                        .encodeToString(pk.getEncoded()));
            } else {
                return blockchainService.getPublicKey(deviceId).map(pk -> Base64.getEncoder()
                        .encodeToString(pk.getEncoded()));
            }
        }
    }

    // ==================== TPM 簽章效能測試 ====================

    @Nested
    @DisplayName("TPM 簽章效能測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SigningPerformanceTests {

        // 系統屬性：是否執行簽章效能測試（需要 TPM 連線）
        private static final boolean RUN_SIGNING_TESTS = Boolean.getBoolean("tpm.test.signing");

        private TPMService tpmService;
        private boolean tpmAvailable = false;

        @BeforeEach
        void setUpTpm() {
            if (!RUN_SIGNING_TESTS) {
                return;
            }

            try {
                tpmService = new TPMService();
                tpmService.connect();
                tpmAvailable = true;
                System.out.println("[設定] TPM 連線成功");
                System.out.println("   使用模擬器: " + tpmService.isUsingSimulator());
            } catch (Exception e) {
                System.err.println("[警告] 無法連接 TPM: " + e.getMessage());
                tpmAvailable = false;
            }
        }

        @AfterEach
        void tearDownTpm() {
            if (tpmService != null) {
                tpmService.close();
                tpmService = null;
            }
        }

        @Test
        @Order(1)
        @DisplayName("1000 次簽章延遲統計")
        void testSigningLatency() throws Exception {
            if (!shouldRunTest()) return;

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                      TPM 簽章延遲測試                                 ");
            System.out.println("========================================================================");
            System.out.println();

            // 使用與驗證測試相同的資料（從 JSON 檔案讀取的 messageData）
            byte[] testData = Base64.getDecoder().decode(originalMessageData);

            // 預熱
            for (int i = 0; i < 10; i++) {
                tpmService.sign(testData);
            }

            int iterations = 1000;
            long[] latencies = new long[iterations];

            // 顯示測試目標資訊（含原始訊息內容）
            printSigningTargetInfoWithMessage(testData, iterations);

            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                tpmService.sign(testData);
                latencies[i] = System.nanoTime() - start;
            }

            PerformanceStats stats = calculateStats(latencies);
            printSigningStatisticsTable(stats, iterations, "簽章延遲統計");

            // TPM 簽章通常在幾十毫秒內完成
            assertThat(stats.getMeanMs()).isLessThan(500.0);
        }

        @Test
        @Order(2)
        @DisplayName("簽章吞吐量測試")
        void testSigningThroughput() throws Exception {
            if (!shouldRunTest()) return;

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                      TPM 簽章吞吐量測試                               ");
            System.out.println("========================================================================");
            System.out.println();

            // 使用與驗證測試相同的資料
            byte[] testData = Base64.getDecoder().decode(originalMessageData);
            int durationSeconds = 5;
            AtomicInteger completedSignings = new AtomicInteger(0);

            // 顯示測試目標資訊
            printSigningTargetInfoWithMessage(testData, -1); // -1 表示使用時間測試模式
            System.out.printf("│ 測試時長:           %d 秒%n", durationSeconds);
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            long startTime = System.currentTimeMillis();
            long endTime = startTime + (durationSeconds * 1000L);

            while (System.currentTimeMillis() < endTime) {
                tpmService.sign(testData);
                completedSignings.incrementAndGet();
            }

            long actualDuration = System.currentTimeMillis() - startTime;
            double throughput = completedSignings.get() / (actualDuration / 1000.0);

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                      TPM 簽章吞吐量結果                             │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf("│ 測試時長:           %,d 毫秒%n", actualDuration);
            System.out.printf("│ 完成簽章數:         %,d 次%n", completedSignings.get());
            System.out.printf("│ 吞吐量:             %.2f 簽章/秒%n", throughput);
            System.out.printf("│ 平均延遲:           %.3f 毫秒/簽章%n", actualDuration / (double) completedSignings.get());
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            // TPM 簽章吞吐量通常較低（10-50 次/秒）
            assertThat(throughput).isGreaterThan(1.0);
        }

        @Test
        @Order(3)
        @DisplayName("不同資料大小的簽章效能比較")
        void testSigningWithDifferentDataSizes() throws Exception {
            if (!shouldRunTest()) return;

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                不同資料大小的簽章效能比較                             ");
            System.out.println("========================================================================");
            System.out.println();

            int[] dataSizes = {64, 256, 1024, 4096, 16384, 65536};
            int iterationsPerSize = 100;

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                    資料大小 vs 簽章延遲                             │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.println("│ 資料大小          │ 平均延遲      │ 標準差      │ P95             │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");

            for (int dataSize : dataSizes) {
                byte[] testData = new byte[dataSize];
                Arrays.fill(testData, (byte) 'A');

                long[] latencies = new long[iterationsPerSize];
                for (int i = 0; i < iterationsPerSize; i++) {
                    long start = System.nanoTime();
                    tpmService.sign(testData);
                    latencies[i] = System.nanoTime() - start;
                }

                PerformanceStats stats = calculateStats(latencies);
                System.out.printf(
                        "│ %,8d bytes    │ %8.3f ms   │ %8.3f ms │ %8.3f ms     │%n",
                        dataSize, stats.getMeanMs(), stats.getStdDevMs(), stats.getP95Ms());
            }

            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();
            System.out.println("註：簽章時間主要取決於 TPM 硬體速度，資料大小影響主要在 Hash 計算階段");
            System.out.println();
        }

        @Test
        @Order(4)
        @DisplayName("簽章金鑰建立時間")
        void testSigningKeyCreationTime() throws Exception {
            if (!shouldRunTest()) return;

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                      簽章金鑰建立時間測試                             ");
            System.out.println("========================================================================");
            System.out.println();

            // 這個測試會建立新的 signing key，需要特別處理
            int iterations = 5; // 金鑰建立較慢，只測試 5 次
            long[] latencies = new long[iterations];

            for (int i = 0; i < iterations; i++) {
                // 關閉並重新連接以確保建立新金鑰
                tpmService.close();
                tpmService = new TPMService();
                tpmService.connect();

                long start = System.nanoTime();
                tpmService.createSigningKey();
                latencies[i] = System.nanoTime() - start;
            }

            PerformanceStats stats = calculateStats(latencies);
            printSigningStatisticsTable(stats, iterations, "金鑰建立時間");
        }

        @Test
        @Order(5)
        @DisplayName("簽章 vs 驗證效能比較")
        void testSigningVsVerificationComparison() throws Exception {
            if (!shouldRunTest()) return;

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                    簽章 vs 驗證效能比較                               ");
            System.out.println("========================================================================");
            System.out.println();

            int iterations = 100;
            byte[] testData = "Compare signing vs verification".getBytes(StandardCharsets.UTF_8);

            // 先簽章一次取得簽章與公鑰
            byte[] signature = tpmService.sign(testData);
            PublicKey publicKey = tpmService.getSigningPublicKey();

            // 測量簽章時間
            long[] signingLatencies = new long[iterations];
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                tpmService.sign(testData);
                signingLatencies[i] = System.nanoTime() - start;
            }

            // 測量驗證時間（使用 Java Crypto）
            long[] verificationLatencies = new long[iterations];
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                TPMService.verifyWithJavaCrypto(publicKey, testData, signature);
                verificationLatencies[i] = System.nanoTime() - start;
            }

            PerformanceStats signingStats = calculateStats(signingLatencies);
            PerformanceStats verificationStats = calculateStats(verificationLatencies);

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                    簽章 vs 驗證效能比較                             │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.println("│ 操作類型          │ 平均延遲      │ P95          │ 備註            │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf(
                    "│ TPM 簽章          │ %8.3f ms   │ %8.3f ms  │ 硬體操作        │%n",
                    signingStats.getMeanMs(), signingStats.getP95Ms());
            System.out.printf(
                    "│ Java 驗證         │ %8.3f ms   │ %8.3f ms  │ 軟體操作        │%n",
                    verificationStats.getMeanMs(), verificationStats.getP95Ms());
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf(
                    "│ 效能比值          │ 簽章/驗證 = %.1fx                                │%n",
                    signingStats.getMeanMs() / verificationStats.getMeanMs());
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();
        }

        private boolean shouldRunTest() {
            if (!RUN_SIGNING_TESTS) {
                System.out.println("========================================================================");
                System.out.println("  TPM 簽章效能測試 - 跳過（未啟用）");
                System.out.println("========================================================================");
                System.out.println();
                System.out.println("使用方式:");
                System.out.println("  mvn test -Dtest=TPMPerformanceTest\\$SigningPerformanceTests \\");
                System.out.println("    -Dtpm.test.signing=true");
                System.out.println();
                return false;
            }

            if (!tpmAvailable) {
                System.out.println("[跳過] TPM 不可用");
                return false;
            }

            return true;
        }

        private void printSigningTargetInfoWithMessage(byte[] testData, int iterations) {
            // 反序列化原始訊息並顯示
            String messageClassName = "N/A";
            String messageContent = "N/A";
            try {
                Object deserializedMessage = deserializeObject(testData);
                messageClassName = deserializedMessage.getClass().getSimpleName();
                messageContent = deserializedMessage.toString();
                // 截斷過長的內容
                if (messageContent.length() > 150) {
                    messageContent = messageContent.substring(0, 150) + "...";
                }
            } catch (Exception e) {
                // 如果反序列化失敗，可能是純文字資料
                messageClassName = "byte[]";
                String content = new String(testData, StandardCharsets.UTF_8);
                if (content.length() > 150) {
                    content = content.substring(0, 150) + "...";
                }
                messageContent = content;
            }

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                         測試目標資訊                                │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf("│ 資料來源:           %s%n", JSON_RESOURCE_PATH);
            System.out.printf(
                    "│ Device ID:          %s...%n",
                    originalDeviceId.substring(0, Math.min(16, originalDeviceId.length())));
            System.out.println("├────────────────────────────── 簽章目標資訊 ───────────────────────────────┤");
            System.out.printf("│ 訊息類型:           %s%n", messageClassName);
            System.out.printf("│ 資料大小:           %,d bytes (序列化資料)%n", testData.length);
            if (iterations > 0) {
                System.out.printf("│ 迭代次數:           %,d 次%n", iterations);
            }
            System.out.println("├────────────────────────────── 原始訊息內容 ───────────────────────────────┤");
            System.out.printf("│ %s%n", messageContent);
            // 注意：不在這裡關閉框線，讓吞吐量測試可以加額外資訊
            if (iterations > 0) {
                System.out.println("└─────────────────────────────────────────────────────────────────────┘");
                System.out.println();
            }
        }

        private void printSigningStatisticsTable(PerformanceStats stats, int samples, String title) {
            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.printf("│                      %s                               │%n", title);
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf("│ 樣本數:             %,d%n", samples);
            System.out.printf("│ 平均值:             %.3f 毫秒%n", stats.getMeanMs());
            System.out.printf("│ 標準差:             %.3f 毫秒%n", stats.getStdDevMs());
            System.out.printf("│ 最小值:             %.3f 毫秒%n", stats.getMinMs());
            System.out.printf("│ 最大值:             %.3f 毫秒%n", stats.getMaxMs());
            System.out.printf("│ P50 (中位數):       %.3f 毫秒%n", stats.getP50Ms());
            System.out.printf("│ P95:                %.3f 毫秒%n", stats.getP95Ms());
            System.out.printf("│ P99:                %.3f 毫秒%n", stats.getP99Ms());
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();
        }
    }
}
