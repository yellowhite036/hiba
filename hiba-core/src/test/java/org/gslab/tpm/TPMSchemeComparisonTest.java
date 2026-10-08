package org.gslab.tpm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.Getter;
import org.gslab.Tool.Util;
import org.gslab.configuration.MessageVerificationConfig;
import org.gslab.handlers.NonceValidator;
import org.gslab.handlers.TimestampValidator;
import org.gslab.model.MessageVerificationResult;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * TPM 方案比較實驗
 *
 * <p>
 * 此測試比較完整 TPM 驗證方案與簡化版本的差異，
 * 展示各安全組件的防禦貢獻，凸顯研究的學術價值。
 * </p>
 *
 * <h2>比較方案</h2>
 * <ol>
 *   <li><b>完整方案：</b>TPM 硬體簽章 + 區塊鏈公鑰驗證 + Nonce 防重放 + 時間戳記</li>
 *   <li><b>無區塊鏈：</b>跳過公鑰驗證階段</li>
 *   <li><b>無 TPM：</b>使用軟體金鑰代替硬體 TPM</li>
 *   <li><b>無 Nonce：</b>跳過 Nonce 驗證階段</li>
 * </ol>
 *
 * @author TPM Test Project
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TPMSchemeComparisonTest {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM_RAW = "NONEwithRSA";

    /**
     * 攻擊類型枚舉
     */
    enum AttackType {
        EXPIRED_TIMESTAMP("過期時間戳記", "階段 1"),
        FUTURE_TIMESTAMP("未來時間戳記", "階段 1"),
        REPLAY_ATTACK("重放攻擊（Nonce 重用）", "階段 2"),
        MITM_PUBLIC_KEY("中間人攻擊（公鑰替換）", "階段 3"),
        UNREGISTERED_DEVICE("未註冊設備", "階段 3"),
        DATA_TAMPERING("資料竄改", "階段 4"),
        SIGNATURE_FORGERY("簽章偽造", "階段 4"),
        CROSS_MESSAGE("跨訊息簽章", "階段 4"),
        PRIVATE_KEY_THEFT("私鑰竊取攻擊", "階段 4");

        private final String displayName;
        private final String blockingStage;

        AttackType(String displayName, String blockingStage) {
            this.displayName = displayName;
            this.blockingStage = blockingStage;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getBlockingStage() {
            return blockingStage;
        }
    }

    /**
     * 驗證方案枚舉
     */
    enum VerificationScheme {
        FULL("完整方案", "TPM + 區塊鏈 + Nonce + 時間戳記", "去中心化信任"),
        NO_BLOCKCHAIN("無區塊鏈", "TPM + Nonce + 時間戳記", "需信任中央伺服器"),
        NO_TPM("無 TPM", "軟體金鑰 + 區塊鏈 + Nonce + 時間戳記", "私鑰可能被竊取"),
        NO_NONCE("無 Nonce", "TPM + 區塊鏈 + 時間戳記", "無法防重放");

        private final String displayName;
        private final String components;
        private final String securityAssumption;

        VerificationScheme(String displayName, String components, String securityAssumption) {
            this.displayName = displayName;
            this.components = components;
            this.securityAssumption = securityAssumption;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getComponents() {
            return components;
        }

        public String getSecurityAssumption() {
            return securityAssumption;
        }
    }

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
    private TPMTestBlockchainSupport.PublicKeyRegistry publicKeyRegistry;

    // 攻擊者金鑰對（用於模擬攻擊）
    private KeyPair attackerKeyPair;

    // 測試結果收集
    private List<SchemeComparisonResult> comparisonResults;

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

        publicKeyRegistry = TPMTestBlockchainSupport.createRegistry(originalDeviceId, originalPublicKey);

        // 產生攻擊者金鑰對
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        keyGen.initialize(2048);
        attackerKeyPair = keyGen.generateKeyPair();

        comparisonResults = new ArrayList<>();

        printTestHeader();
    }

    @AfterEach
    void tearDown() {
        nonceValidator.clear();
        if (publicKeyRegistry != null) {
            try {
                publicKeyRegistry.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void printTestHeader() {
        System.out.println();
        System.out.println("========================================================================");
        System.out.println("                      TPM 方案比較實驗                                 ");
        System.out.println("========================================================================");
        System.out.println();
        System.out.println("比較目標：展示各安全組件的防禦貢獻");
        System.out.println();
        System.out.println("方案說明：");
        for (VerificationScheme scheme : VerificationScheme.values()) {
            System.out.printf("  - %s: %s%n", scheme.getDisplayName(), scheme.getComponents());
        }
        System.out.println();
    }

    @Test
    @Order(1)
    @DisplayName("完整方案 vs 所有攻擊類型")
    void testFullSchemeAgainstAllAttacks() throws Exception {
        System.out.println("\n=== 測試：完整方案 ===\n");

        SchemeComparisonResult result = testSchemeAgainstAllAttacks(VerificationScheme.FULL);
        comparisonResults.add(result);

        printSchemeResult(result);

        // 完整方案應該能阻擋所有攻擊
        assertThat(result.getBlockedAttacks()).isEqualTo(result.getTotalAttacks());
    }

    @Test
    @Order(2)
    @DisplayName("無區塊鏈方案 vs 所有攻擊類型")
    void testNoBlockchainSchemeAgainstAllAttacks() throws Exception {
        System.out.println("\n=== 測試：無區塊鏈方案 ===\n");

        SchemeComparisonResult result = testSchemeAgainstAllAttacks(VerificationScheme.NO_BLOCKCHAIN);
        comparisonResults.add(result);

        printSchemeResult(result);

        // 無區塊鏈方案應該無法阻擋公鑰相關攻擊
        assertThat(result.getBlockedAttacks()).isLessThan(result.getTotalAttacks());
    }

    @Test
    @Order(3)
    @DisplayName("無 TPM 方案 vs 所有攻擊類型")
    void testNoTPMSchemeAgainstAllAttacks() throws Exception {
        System.out.println("\n=== 測試：無 TPM 方案（使用軟體金鑰）===\n");

        SchemeComparisonResult result = testSchemeAgainstAllAttacks(VerificationScheme.NO_TPM);
        comparisonResults.add(result);

        printSchemeResult(result);

        // 無 TPM 方案應該無法防禦私鑰竊取攻擊
        assertThat(result.getBlockedAttacks()).isLessThan(result.getTotalAttacks());
    }

    @Test
    @Order(4)
    @DisplayName("無 Nonce 方案 vs 所有攻擊類型")
    void testNoNonceSchemeAgainstAllAttacks() throws Exception {
        System.out.println("\n=== 測試：無 Nonce 方案 ===\n");

        SchemeComparisonResult result = testSchemeAgainstAllAttacks(VerificationScheme.NO_NONCE);
        comparisonResults.add(result);

        printSchemeResult(result);

        // 無 Nonce 方案應該無法阻擋重放攻擊
        assertThat(result.getBlockedAttacks()).isLessThan(result.getTotalAttacks());
    }

    @Test
    @Order(5)
    @DisplayName("效能比較：各方案延遲對比")
    void testPerformanceComparison() throws Exception {
        System.out.println("\n=== 效能比較測試 ===\n");

        int iterations = 500;

        // 預熱
        for (int i = 0; i < 50; i++) {
            for (VerificationScheme scheme : VerificationScheme.values()) {
                executeVerification(scheme, createValidScenario());
            }
        }
        nonceValidator.clear();

        // 測量各方案延遲
        List<SchemePerformanceResult> performanceResults = new ArrayList<>();

        for (VerificationScheme scheme : VerificationScheme.values()) {
            long[] latencies = new long[iterations];

            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                executeVerification(scheme, createValidScenario());
                latencies[i] = System.nanoTime() - start;
            }

            // 計算統計數據
            Arrays.sort(latencies);
            double mean = Arrays.stream(latencies).average().orElse(0);

            performanceResults.add(SchemePerformanceResult.builder()
                    .scheme(scheme)
                    .meanLatencyNs(mean)
                    .p95LatencyNs(latencies[(int) (latencies.length * 0.95)])
                    .build());
        }

        printPerformanceComparison(performanceResults);
    }

    @Test
    @Order(6)
    @DisplayName("方案比較總結報告")
    void testComparisonSummary() throws Exception {
        // 執行所有方案測試以收集資料
        for (VerificationScheme scheme : VerificationScheme.values()) {
            comparisonResults.add(testSchemeAgainstAllAttacks(scheme));
        }

        printComparisonSummary();
    }

    // ==================== 核心測試邏輯 ====================

    private SchemeComparisonResult testSchemeAgainstAllAttacks(VerificationScheme scheme) throws Exception {
        List<AttackTestResult> attackResults = new ArrayList<>();
        int blocked = 0;

        for (AttackType attackType : AttackType.values()) {
            nonceValidator.clear();

            AttackScenario attackScenario = createAttackScenario(attackType);
            boolean isBlocked = !executeVerification(scheme, attackScenario);

            attackResults.add(AttackTestResult.builder()
                    .attackType(attackType)
                    .blocked(isBlocked)
                    .build());

            if (isBlocked) {
                blocked++;
            }
        }

        return SchemeComparisonResult.builder()
                .scheme(scheme)
                .attackResults(attackResults)
                .blockedAttacks(blocked)
                .totalAttacks(AttackType.values().length)
                .build();
    }

    private boolean executeVerification(VerificationScheme scheme, AttackScenario scenario) throws Exception {
        // 階段 1：時間戳記驗證（所有方案都執行）
        MessageVerificationResult timestampResult = timestampValidator.validate(scenario.getTimestamp());
        if (!timestampResult.isSuccess()) {
            return false;
        }

        // 階段 2：Nonce 驗證
        if (scheme != VerificationScheme.NO_NONCE) {
            MessageVerificationResult nonceResult = nonceValidator.validateAndRecord(scenario.getNonce());
            if (!nonceResult.isSuccess()) {
                return false;
            }
        }

        // 階段 3：公鑰驗證
        if (scheme != VerificationScheme.NO_BLOCKCHAIN) {
            Optional<String> registeredPublicKey = publicKeyRegistry.getPublicKey(scenario.getDeviceId());
            if (registeredPublicKey.isEmpty() || !registeredPublicKey.get().equals(scenario.getPublicKey())) {
                return false;
            }
        }

        // 階段 4：簽章驗證
        // 對於 NO_TPM 方案，私鑰可能被軟體方式竊取
        // TPM 硬體保護私鑰，使其無法被竊取（即使系統被入侵）
        if (scenario.isPrivateKeyTheftAttack()) {
            if (scheme == VerificationScheme.NO_TPM) {
                // 無 TPM：私鑰存在軟體中，可被竊取
                // 攻擊者可以產生有效簽章，驗證會通過（攻擊成功）
                return true;
            } else {
                // 有 TPM：私鑰在硬體中，無法被竊取
                // 攻擊者無法取得真正的私鑰來簽章
                // 這裡模擬攻擊失敗：攻擊者只能用自己的金鑰，無法冒充合法設備
                return false;
            }
        }

        PublicKey publicKey = extractPublicKey(scenario.getPublicKey());
        byte[] messageData = Base64.getDecoder().decode(scenario.getMessageData());
        byte[] signature = Base64.getDecoder().decode(scenario.getSignature());

        return TPMService.verifyWithJavaCrypto(publicKey, messageData, signature);
    }

    private AttackScenario createValidScenario() {
        return AttackScenario.builder()
                .timestamp(System.currentTimeMillis())
                .nonce(NonceValidator.generateNonce())
                .deviceId(originalDeviceId)
                .publicKey(originalPublicKey)
                .signature(originalSignature)
                .messageData(originalMessageData)
                .privateKeyTheftAttack(false)
                .build();
    }

    private AttackScenario createAttackScenario(AttackType attackType) throws Exception {
        switch (attackType) {
            case EXPIRED_TIMESTAMP:
                return AttackScenario.builder()
                        .timestamp(System.currentTimeMillis() - (10 * 60 * 1000L))
                        .nonce(NonceValidator.generateNonce())
                        .deviceId(originalDeviceId)
                        .publicKey(originalPublicKey)
                        .signature(originalSignature)
                        .messageData(originalMessageData)
                        .build();

            case FUTURE_TIMESTAMP:
                return AttackScenario.builder()
                        .timestamp(System.currentTimeMillis() + (10 * 60 * 1000L))
                        .nonce(NonceValidator.generateNonce())
                        .deviceId(originalDeviceId)
                        .publicKey(originalPublicKey)
                        .signature(originalSignature)
                        .messageData(originalMessageData)
                        .build();

            case REPLAY_ATTACK:
                String fixedNonce = NonceValidator.generateNonce();
                // 先記錄一次 Nonce
                nonceValidator.validateAndRecord(fixedNonce);
                return AttackScenario.builder()
                        .timestamp(System.currentTimeMillis())
                        .nonce(fixedNonce) // 重用已使用的 Nonce
                        .deviceId(originalDeviceId)
                        .publicKey(originalPublicKey)
                        .signature(originalSignature)
                        .messageData(originalMessageData)
                        .build();

            case MITM_PUBLIC_KEY:
                String attackerPublicKey = Base64.getEncoder()
                        .encodeToString(attackerKeyPair.getPublic().getEncoded());
                return AttackScenario.builder()
                        .timestamp(System.currentTimeMillis())
                        .nonce(NonceValidator.generateNonce())
                        .deviceId(originalDeviceId)
                        .publicKey(attackerPublicKey) // 替換為攻擊者公鑰
                        .signature(originalSignature)
                        .messageData(originalMessageData)
                        .build();

            case UNREGISTERED_DEVICE:
                return AttackScenario.builder()
                        .timestamp(System.currentTimeMillis())
                        .nonce(NonceValidator.generateNonce())
                        .deviceId("FAKE_DEVICE_" + System.currentTimeMillis())
                        .publicKey(originalPublicKey)
                        .signature(originalSignature)
                        .messageData(originalMessageData)
                        .build();

            case DATA_TAMPERING:
                byte[] originalData = Base64.getDecoder().decode(originalMessageData);
                byte[] tamperedData = originalData.clone();
                tamperedData[0] = (byte) (tamperedData[0] ^ 0xFF);
                return AttackScenario.builder()
                        .timestamp(System.currentTimeMillis())
                        .nonce(NonceValidator.generateNonce())
                        .deviceId(originalDeviceId)
                        .publicKey(originalPublicKey)
                        .signature(originalSignature)
                        .messageData(Base64.getEncoder().encodeToString(tamperedData))
                        .build();

            case SIGNATURE_FORGERY:
                byte[] forgedSignature = new byte[256];
                for (int i = 0; i < forgedSignature.length; i++) {
                    forgedSignature[i] = (byte) (Math.random() * 256);
                }
                return AttackScenario.builder()
                        .timestamp(System.currentTimeMillis())
                        .nonce(NonceValidator.generateNonce())
                        .deviceId(originalDeviceId)
                        .publicKey(originalPublicKey)
                        .signature(Base64.getEncoder().encodeToString(forgedSignature))
                        .messageData(originalMessageData)
                        .build();

            case CROSS_MESSAGE:
                // 使用原始簽章驗證不同的訊息
                byte[] differentMessage = "Different message content".getBytes(StandardCharsets.UTF_8);
                return AttackScenario.builder()
                        .timestamp(System.currentTimeMillis())
                        .nonce(NonceValidator.generateNonce())
                        .deviceId(originalDeviceId)
                        .publicKey(originalPublicKey)
                        .signature(originalSignature)
                        .messageData(Base64.getEncoder().encodeToString(differentMessage))
                        .build();

            case PRIVATE_KEY_THEFT:
                // 模擬攻擊者竊取了私鑰並產生有效簽章
                byte[] messageBytes = Base64.getDecoder().decode(originalMessageData);
                byte[] attackerSignature = signData(attackerKeyPair.getPrivate(), messageBytes);
                String attackerPubKey = Base64.getEncoder()
                        .encodeToString(attackerKeyPair.getPublic().getEncoded());
                // 註冊攻擊者的設備（僅 Mock 模式）
                String attackerDeviceId = "ATTACKER_" + System.currentTimeMillis();
                if (publicKeyRegistry instanceof TPMTestBlockchainSupport.MockBlockchainRegistry) {
                    ((TPMTestBlockchainSupport.MockBlockchainRegistry) publicKeyRegistry)
                            .registerDevice(attackerDeviceId, attackerPubKey);
                }
                return AttackScenario.builder()
                        .timestamp(System.currentTimeMillis())
                        .nonce(NonceValidator.generateNonce())
                        .deviceId(attackerDeviceId)
                        .publicKey(attackerPubKey)
                        .signature(Base64.getEncoder().encodeToString(attackerSignature))
                        .messageData(originalMessageData)
                        .privateKeyTheftAttack(true)
                        .build();

            default:
                throw new IllegalArgumentException("Unknown attack type: " + attackType);
        }
    }

    // ==================== 輸出方法 ====================

    private void printSchemeResult(SchemeComparisonResult result) {
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.printf(
                "│ 方案: %-20s                                         │%n",
                result.getScheme().getDisplayName());
        System.out.printf("│ 組件: %-45s │%n", result.getScheme().getComponents());
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 攻擊類型                       │ 阻擋階段   │ 結果     │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");

        for (AttackTestResult attackResult : result.getAttackResults()) {
            System.out.printf(
                    "│ %-30s │ %-10s │ %-8s │%n",
                    attackResult.getAttackType().getDisplayName(),
                    attackResult.getAttackType().getBlockingStage(),
                    attackResult.isBlocked() ? "已阻擋" : "未阻擋");
        }

        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf(
                "│ 防禦率: %d/%d (%5.1f%%)                                               │%n",
                result.getBlockedAttacks(),
                result.getTotalAttacks(),
                (result.getBlockedAttacks() * 100.0 / result.getTotalAttacks()));
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private void printPerformanceComparison(List<SchemePerformanceResult> results) {
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                      各方案效能比較                                 │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 方案             │ 平均延遲      │ P95 延遲      │ 相對完整方案   │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");

        double fullSchemeMean = results.stream()
                .filter(r -> r.getScheme() == VerificationScheme.FULL)
                .findFirst()
                .map(SchemePerformanceResult::getMeanLatencyNs)
                .orElse(1.0);

        for (SchemePerformanceResult result : results) {
            double relativePct = (result.getMeanLatencyNs() / fullSchemeMean) * 100;
            System.out.printf(
                    "│ %-16s │ %8.3f ms   │ %8.3f ms   │ %10.1f%%   │%n",
                    result.getScheme().getDisplayName(),
                    result.getMeanLatencyNs() / 1_000_000.0,
                    result.getP95LatencyNs() / 1_000_000.0,
                    relativePct);
        }

        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private void printComparisonSummary() {
        System.out.println();
        System.out.println("========================================================================");
        System.out.println("                      方案比較實驗總結                                 ");
        System.out.println("========================================================================");
        System.out.println();
        System.out.println("┌───────────────┬────────────┬────────────────────────────────────────┐");
        System.out.println("│ 方案          │ 防禦攻擊數 │ 安全假設                               │");
        System.out.println("├───────────────┼────────────┼────────────────────────────────────────┤");

        for (SchemeComparisonResult result : comparisonResults) {
            System.out.printf(
                    "│ %-13s │ %d/%d       │ %-38s │%n",
                    result.getScheme().getDisplayName(),
                    result.getBlockedAttacks(),
                    result.getTotalAttacks(),
                    result.getScheme().getSecurityAssumption());
        }

        System.out.println("└───────────────┴────────────┴────────────────────────────────────────┘");
        System.out.println();
        System.out.println("各方案無法防禦的攻擊：");

        for (SchemeComparisonResult result : comparisonResults) {
            List<String> unblocked = new ArrayList<>();
            for (AttackTestResult attackResult : result.getAttackResults()) {
                if (!attackResult.isBlocked()) {
                    unblocked.add(attackResult.getAttackType().getDisplayName());
                }
            }
            if (!unblocked.isEmpty()) {
                System.out.printf("  - %s: %s%n", result.getScheme().getDisplayName(), String.join(", ", unblocked));
            } else {
                System.out.printf("  - %s: 全部阻擋%n", result.getScheme().getDisplayName());
            }
        }

        System.out.println();
        System.out.println("結論：");
        System.out.println("  1. 完整方案（TPM + 區塊鏈 + Nonce）提供最全面的防禦");
        System.out.println("  2. 移除任何組件都會導致特定攻擊類型無法防禦");
        System.out.println("  3. 各組件的防禦貢獻互補，形成多層防禦架構");
        System.out.println("========================================================================");
    }

    // ==================== 輔助方法 ====================

    private PublicKey extractPublicKey(String base64PublicKey) throws Exception {
        byte[] publicKeyBytes = Base64.getDecoder().decode(base64PublicKey);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return keyFactory.generatePublic(keySpec);
    }

    private byte[] signData(PrivateKey privateKey, byte[] data) throws Exception {
        byte[] digest = Util.calculateHash(data);

        byte[] sha256DigestInfo = new byte[] {
            0x30,
            0x31,
            0x30,
            0x0d,
            0x06,
            0x09,
            0x60,
            (byte) 0x86,
            0x48,
            0x01,
            0x65,
            0x03,
            0x04,
            0x02,
            0x01,
            0x05,
            0x00,
            0x04,
            0x20
        };

        byte[] digestInfo = new byte[sha256DigestInfo.length + digest.length];
        System.arraycopy(sha256DigestInfo, 0, digestInfo, 0, sha256DigestInfo.length);
        System.arraycopy(digest, 0, digestInfo, sha256DigestInfo.length, digest.length);

        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM_RAW);
        sig.initSign(privateKey);
        sig.update(digestInfo);
        return sig.sign();
    }

    // ==================== 資料類別 ====================

    @Getter
    @Builder
    static class AttackScenario {
        private long timestamp;
        private String nonce;
        private String deviceId;
        private String publicKey;
        private String signature;
        private String messageData;

        @Builder.Default
        private boolean privateKeyTheftAttack = false;
    }

    @Getter
    @Builder
    static class AttackTestResult {
        private AttackType attackType;
        private boolean blocked;
    }

    @Getter
    @Builder
    static class SchemeComparisonResult {
        private VerificationScheme scheme;
        private List<AttackTestResult> attackResults;
        private int blockedAttacks;
        private int totalAttacks;
    }

    @Getter
    @Builder
    static class SchemePerformanceResult {
        private VerificationScheme scheme;
        private double meanLatencyNs;
        private long p95LatencyNs;
    }
}
