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
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.Getter;
import org.gslab.util.Util;
import org.gslab.configuration.MessageVerificationConfig;
import org.gslab.handlers.NonceValidator;
import org.gslab.handlers.TimestampValidator;
import org.gslab.model.MessageVerificationResult;
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
 * TPM 組合攻擊測試
 *
 * <p>
 * 此測試模擬攻擊者同時修改多個欄位的複雜攻擊場景。
 * 展示多層防禦機制的協同作用，證明單一防護層不足以應對複雜攻擊。
 * </p>
 *
 * <h2>測試場景</h2>
 * <ol>
 * <li><b>雙重竄改：</b>同時修改時間戳記與訊息資料</li>
 * <li><b>身份偽裝 + 資料竄改：</b>更換設備 ID 並修改訊息內容</li>
 * <li><b>完整偽造攻擊：</b>攻擊者用自己的私鑰簽章並替換公鑰</li>
 * <li><b>全欄位竄改：</b>修改所有欄位，模擬完全偽造的訊息</li>
 * </ol>
 *
 * @author TPM Test Project
 */
class TPMCombinationAttackTest {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM_RAW = "NONEwithRSA";

    /**
     * 驗證階段狀態
     */
    enum StageStatus {
        PASSED("通過"),
        FAILED("失敗"),
        NOT_EXECUTED("未執行");

        private final String displayName;

        StageStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // 原始 TPM 簽章資料
    private JSONObject tpmSignatureInfo;
    private String originalSignature;
    private String originalPublicKey;
    private String originalMessageData;
    private String originalNonce;
    private long originalTimestamp;
    private String originalDeviceId;

    // 驗證器
    private TimestampValidator timestampValidator;
    private NonceValidator nonceValidator;

    // 公鑰註冊表（可為 Mock 或真實區塊鏈）
    private TPMTestBlockchainSupport.PublicKeyRegistry publicKeyRegistry;

    // 測試結果收集
    private List<CombinationAttackResult> testResults;

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
        originalNonce = tpmSignatureInfo.getString("nonce");
        originalTimestamp = tpmSignatureInfo.getLong("signatureTimestamp");
        originalDeviceId = tpmSignatureInfo.getString("deviceId");

        MessageVerificationConfig config = MessageVerificationConfig.defaultConfig();
        timestampValidator = new TimestampValidator(config);
        nonceValidator = NonceValidator.getInstance();
        nonceValidator.clear();

        publicKeyRegistry = TPMTestBlockchainSupport.createRegistry(originalDeviceId, originalPublicKey);

        testResults = new ArrayList<>();

        printTestHeader();
    }

    @AfterEach
    void tearDown() {
        nonceValidator.clear();
        if (!testResults.isEmpty()) {
            printTestResultsSummary();
        }
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
        System.out.println("                       TPM 組合攻擊測試                                ");
        System.out.println("========================================================================");
        System.out.println("設備 ID: " + Util.truncateFirstAndLast(originalDeviceId, 8));
        System.out.println("TPM 版本: " + tpmSignatureInfo.getString("tpmVersion"));
        System.out.println();
        System.out.println("測試目的：驗證多層防禦機制能否阻擋同時修改多個欄位的複雜攻擊");
        System.out.println();
    }

    @Nested
    @DisplayName("組合攻擊測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CombinationAttackTests {

        @Test
        @Order(1)
        @DisplayName("雙重竄改攻擊：時間戳記 + 訊息資料")
        void testDoubleModification_TimestampAndMessage() throws Exception {
            System.out.println("\n=== 測試：雙重竄改攻擊（時間戳記 + 訊息資料）===\n");

            // 同時修改時間戳記（設為過期）和訊息資料
            long expiredTimestamp = System.currentTimeMillis() - (10 * 60 * 1000L);

            byte[] originalData = Base64.getDecoder().decode(originalMessageData);
            byte[] tamperedData = originalData.clone();
            tamperedData[0] = (byte) (tamperedData[0] ^ 0xFF);
            String tamperedMessageData = Base64.getEncoder().encodeToString(tamperedData);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(expiredTimestamp)
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(tamperedMessageData)
                    .build();

            printAttackDetails("雙重竄改", "時間戳記 + 訊息資料", "將時間戳記設為 10 分鐘前，並修改訊息資料的第一個位元組");

            VerificationResult result = executeFullVerification(scenario);

            CombinationAttackResult attackResult = CombinationAttackResult.builder()
                    .attackName("雙重竄改")
                    .modifiedFields("時間戳記 + 訊息資料")
                    .expectedBlockStage("階段 1（時間戳記）")
                    .actualBlockStage(result.getBlockedAtStage())
                    .blocked(!result.isSuccess())
                    .build();
            testResults.add(attackResult);

            printAttackResult(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("時間戳記驗證");
        }

        @Test
        @Order(2)
        @DisplayName("身份偽裝 + 資料竄改：設備ID + 訊息資料")
        void testIdentitySpoofingWithDataTampering() throws Exception {
            System.out.println("\n=== 測試：身份偽裝 + 資料竄改攻擊 ===\n");

            // 使用未註冊的設備 ID 並修改訊息資料
            String fakeDeviceId = "FAKE_DEVICE_" + System.currentTimeMillis();

            byte[] originalData = Base64.getDecoder().decode(originalMessageData);
            byte[] tamperedData = originalData.clone();
            tamperedData[tamperedData.length / 2] = (byte) (tamperedData[tamperedData.length / 2] ^ 0xFF);
            String tamperedMessageData = Base64.getEncoder().encodeToString(tamperedData);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(fakeDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(tamperedMessageData)
                    .build();

            printAttackDetails("身份偽裝 + 資料竄改", "設備ID + 訊息資料", "使用未在區塊鏈上註冊的設備 ID，並修改訊息資料的中間位元組");

            VerificationResult result = executeFullVerification(scenario);

            CombinationAttackResult attackResult = CombinationAttackResult.builder()
                    .attackName("身份偽裝 + 資料竄改")
                    .modifiedFields("設備ID + 訊息資料")
                    .expectedBlockStage("階段 3（公鑰）")
                    .actualBlockStage(result.getBlockedAtStage())
                    .blocked(!result.isSuccess())
                    .build();
            testResults.add(attackResult);

            printAttackResult(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("公鑰驗證");
        }

        @Test
        @Order(3)
        @DisplayName("完整偽造攻擊：公鑰替換 + 重新簽章")
        void testFullForgeryAttack() throws Exception {
            System.out.println("\n=== 測試：完整偽造攻擊（公鑰替換 + 重新簽章）===\n");

            // 攻擊者產生自己的金鑰對
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(2048);
            KeyPair attackerKeyPair = keyGen.generateKeyPair();

            // 攻擊者用自己的私鑰簽章原始訊息
            byte[] originalData = Base64.getDecoder().decode(originalMessageData);
            byte[] attackerSignature = signData(attackerKeyPair.getPrivate(), originalData);

            String attackerPublicKeyBase64 = Base64.getEncoder()
                    .encodeToString(attackerKeyPair.getPublic().getEncoded());
            String attackerSignatureBase64 = Base64.getEncoder().encodeToString(attackerSignature);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(attackerPublicKeyBase64)
                    .signature(attackerSignatureBase64)
                    .messageData(originalMessageData)
                    .build();

            printAttackDetails("完整偽造攻擊", "公鑰 + 簽章", "攻擊者用自己的私鑰重新簽章，並將公鑰替換為攻擊者的公鑰");

            VerificationResult result = executeFullVerification(scenario);

            CombinationAttackResult attackResult = CombinationAttackResult.builder()
                    .attackName("完整偽造攻擊")
                    .modifiedFields("公鑰 + 簽章")
                    .expectedBlockStage("階段 3（公鑰）")
                    .actualBlockStage(result.getBlockedAtStage())
                    .blocked(!result.isSuccess())
                    .build();
            testResults.add(attackResult);

            printAttackResult(result);

            // 即使簽章本身是有效的（用攻擊者私鑰簽章），但公鑰不匹配區塊鏈註冊
            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("公鑰驗證");
        }

        @Test
        @Order(4)
        @DisplayName("極端攻擊：全欄位竄改（完全偽造訊息）")
        void testAllFieldsTampering() throws Exception {
            System.out.println("\n=== 測試：全欄位竄改攻擊（完全偽造訊息）===\n");

            // 攻擊者產生全新的金鑰對
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(2048);
            KeyPair attackerKeyPair = keyGen.generateKeyPair();

            // 完全偽造的訊息資料
            String fakeMessage = "這是攻擊者完全偽造的訊息內容";
            byte[] fakeMessageBytes = fakeMessage.getBytes(StandardCharsets.UTF_8);
            byte[] attackerSignature = signData(attackerKeyPair.getPrivate(), fakeMessageBytes);

            String fakeDeviceId = "ATTACKER_DEVICE_" + System.currentTimeMillis();
            String attackerPublicKeyBase64 = Base64.getEncoder()
                    .encodeToString(attackerKeyPair.getPublic().getEncoded());
            String attackerSignatureBase64 = Base64.getEncoder().encodeToString(attackerSignature);
            String fakeMessageBase64 = Base64.getEncoder().encodeToString(fakeMessageBytes);

            // 使用過期時間戳記
            long expiredTimestamp = System.currentTimeMillis() - (10 * 60 * 1000L);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(expiredTimestamp)
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(fakeDeviceId)
                    .publicKey(attackerPublicKeyBase64)
                    .signature(attackerSignatureBase64)
                    .messageData(fakeMessageBase64)
                    .build();

            printAttackDetails("全欄位竄改", "時間戳記 + 設備ID + 公鑰 + 簽章 + 訊息資料", "攻擊者完全偽造一個新訊息，所有欄位都是偽造的");

            VerificationResult result = executeFullVerification(scenario);

            CombinationAttackResult attackResult = CombinationAttackResult.builder()
                    .attackName("全欄位竄改")
                    .modifiedFields("所有欄位")
                    .expectedBlockStage("階段 1（時間戳記）")
                    .actualBlockStage(result.getBlockedAtStage())
                    .blocked(!result.isSuccess())
                    .build();
            testResults.add(attackResult);

            printAttackResult(result);

            // 即使所有欄位都被偽造，攻擊仍會在第一個驗證階段被阻擋
            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("時間戳記驗證");
        }

        @Test
        @Order(5)
        @DisplayName("重放 + 資料竄改：重用 Nonce + 修改訊息")
        void testReplayWithDataTampering() throws Exception {
            System.out.println("\n=== 測試：重放 + 資料竄改攻擊 ===\n");

            String fixedNonce = NonceValidator.generateNonce();

            // 第一次請求（合法）
            AttackScenario firstRequest = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(fixedNonce)
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            System.out.println(">>> 第一次請求（合法）<<<");
            VerificationResult firstResult = executeFullVerification(firstRequest);
            assertThat(firstResult.isSuccess()).isTrue();

            // 重放攻擊 + 資料竄改
            System.out.println("\n>>> 第二次請求（重放 + 竄改）<<<\n");

            byte[] originalData = Base64.getDecoder().decode(originalMessageData);
            byte[] tamperedData = originalData.clone();
            tamperedData[tamperedData.length - 1] = (byte) (tamperedData[tamperedData.length - 1] ^ 0xFF);
            String tamperedMessageData = Base64.getEncoder().encodeToString(tamperedData);

            AttackScenario replayAttack = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(fixedNonce) // 重用 Nonce
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(tamperedMessageData) // 竄改資料
                    .build();

            printAttackDetails("重放 + 資料竄改", "Nonce（重用）+ 訊息資料", "攻擊者重用已使用過的 Nonce，並嘗試修改訊息資料");

            VerificationResult result = executeFullVerification(replayAttack);

            CombinationAttackResult attackResult = CombinationAttackResult.builder()
                    .attackName("重放 + 資料竄改")
                    .modifiedFields("Nonce（重用）+ 訊息資料")
                    .expectedBlockStage("階段 2（Nonce）")
                    .actualBlockStage(result.getBlockedAtStage())
                    .blocked(!result.isSuccess())
                    .build();
            testResults.add(attackResult);

            printAttackResult(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("Nonce 驗證");
        }

        @Test
        @Order(6)
        @DisplayName("降級攻擊：跳過 Nonce + 未來時間戳記")
        void testDowngradeAttack() throws Exception {
            System.out.println("\n=== 測試：降級攻擊（未來時間戳記）===\n");

            // 攻擊者嘗試用未來時間戳記繞過過期檢查
            long futureTimestamp = System.currentTimeMillis() + (10 * 60 * 1000L);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(futureTimestamp)
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printAttackDetails("降級攻擊", "時間戳記（未來）", "攻擊者將時間戳記設為未來 10 分鐘，嘗試繞過過期檢查");

            VerificationResult result = executeFullVerification(scenario);

            CombinationAttackResult attackResult = CombinationAttackResult.builder()
                    .attackName("降級攻擊")
                    .modifiedFields("時間戳記（未來）")
                    .expectedBlockStage("階段 1（時間戳記）")
                    .actualBlockStage(result.getBlockedAtStage())
                    .blocked(!result.isSuccess())
                    .build();
            testResults.add(attackResult);

            printAttackResult(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("時間戳記驗證");
        }
    }

    // ==================== 輔助方法 ====================

    private void printAttackDetails(String attackName, String modifiedFields, String description) {
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                         攻擊詳情                                    │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 攻擊名稱: " + attackName);
        System.out.println("│ 修改欄位: " + modifiedFields);
        System.out.println("│ 攻擊描述: " + description);
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private void printAttackResult(VerificationResult result) {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                         驗證結果                                    │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 階段 1（時間戳記）: " + formatStatus(result.getTimestampStatus()));
        System.out.println("│ 階段 2（Nonce）:    " + formatStatus(result.getNonceStatus()));
        System.out.println("│ 階段 3（公鑰）:     " + formatStatus(result.getPublicKeyStatus()));
        System.out.println("│ 階段 4（簽章）:     " + formatStatus(result.getSignatureStatus()));
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 阻擋於: " + result.getBlockedAtStage());
        if (result.getErrorMessage() != null) {
            System.out.println("│ 錯誤訊息: " + result.getErrorMessage());
        }
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 結果: " + (result.isSuccess() ? "攻擊成功（系統漏洞！）" : "攻擊已成功阻擋"));
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private void printTestResultsSummary() {
        System.out.println();
        System.out.println("========================================================================");
        System.out.println("                     組合攻擊測試結果摘要                              ");
        System.out.println("========================================================================");
        System.out.println();
        System.out.println("┌──────────────────────┬────────────────────────┬────────────────┬────────┐");
        System.out.println("│ 攻擊場景             │ 修改欄位               │ 阻擋階段       │ 結果   │");
        System.out.println("├──────────────────────┼────────────────────────┼────────────────┼────────┤");

        int blockedCount = 0;
        for (CombinationAttackResult result : testResults) {
            System.out.printf(
                    "│ %-20s │ %-22s │ %-14s │ %-6s │%n",
                    truncate(result.getAttackName(), 20),
                    truncate(result.getModifiedFields(), 22),
                    truncate(result.getActualBlockStage(), 14),
                    result.isBlocked() ? "已阻擋" : "失敗");
            if (result.isBlocked()) {
                blockedCount++;
            }
        }

        System.out.println("└──────────────────────┴────────────────────────┴────────────────┴────────┘");
        System.out.println();
        System.out.printf("總計: %d/%d 攻擊已成功阻擋%n", blockedCount, testResults.size());
        System.out.println();
        System.out.println("結論: 多層防禦機制確保即使攻擊者同時修改多個欄位，");
        System.out.println("      攻擊仍會在最早的驗證階段被阻擋。");
        System.out.println("========================================================================");
    }

    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "";
        }
        return str.length() <= maxLength ? str : str.substring(0, maxLength - 2) + "..";
    }

    private String formatStatus(StageStatus status) {
        switch (status) {
            case PASSED:
                return "通過";
            case FAILED:
                return "失敗 <--";
            case NOT_EXECUTED:
                return "未執行 (前階段已失敗)";
            default:
                return status.getDisplayName();
        }
    }

    /**
     * 執行完整的四階段驗證流程
     */
    private VerificationResult executeFullVerification(AttackScenario scenario) throws Exception {
        VerificationResult.VerificationResultBuilder resultBuilder = VerificationResult.builder();

        // 階段 1：時間戳記驗證
        MessageVerificationResult timestampResult = timestampValidator.validate(scenario.getTimestamp());

        if (!timestampResult.isSuccess()) {
            return resultBuilder
                    .timestampStatus(StageStatus.FAILED)
                    .nonceStatus(StageStatus.NOT_EXECUTED)
                    .publicKeyStatus(StageStatus.NOT_EXECUTED)
                    .signatureStatus(StageStatus.NOT_EXECUTED)
                    .blockedAtStage("時間戳記驗證")
                    .errorMessage(timestampResult.getMessage())
                    .success(false)
                    .build();
        }
        resultBuilder.timestampStatus(StageStatus.PASSED);

        // 階段 2：Nonce 驗證
        MessageVerificationResult nonceResult = nonceValidator.validateAndRecord(scenario.getNonce());

        if (!nonceResult.isSuccess()) {
            return resultBuilder
                    .nonceStatus(StageStatus.FAILED)
                    .publicKeyStatus(StageStatus.NOT_EXECUTED)
                    .signatureStatus(StageStatus.NOT_EXECUTED)
                    .blockedAtStage("Nonce 驗證")
                    .errorMessage(nonceResult.getMessage())
                    .success(false)
                    .build();
        }
        resultBuilder.nonceStatus(StageStatus.PASSED);

        // 階段 3：公鑰驗證
        Optional<String> registeredPublicKey = publicKeyRegistry.getPublicKey(scenario.getDeviceId());

        if (registeredPublicKey.isEmpty()) {
            return resultBuilder
                    .publicKeyStatus(StageStatus.FAILED)
                    .signatureStatus(StageStatus.NOT_EXECUTED)
                    .blockedAtStage("公鑰驗證")
                    .errorMessage("設備未在區塊鏈上註冊")
                    .success(false)
                    .build();
        }

        if (!registeredPublicKey.get().equals(scenario.getPublicKey())) {
            return resultBuilder
                    .publicKeyStatus(StageStatus.FAILED)
                    .signatureStatus(StageStatus.NOT_EXECUTED)
                    .blockedAtStage("公鑰驗證")
                    .errorMessage("公鑰不匹配（可能是中間人攻擊）")
                    .success(false)
                    .build();
        }
        resultBuilder.publicKeyStatus(StageStatus.PASSED);

        // 階段 4：簽章驗證
        PublicKey publicKey = extractPublicKey(scenario.getPublicKey());
        byte[] messageData = Base64.getDecoder().decode(scenario.getMessageData());
        byte[] signature = Base64.getDecoder().decode(scenario.getSignature());

        boolean signatureValid = TPMService.verifyWithJavaCrypto(publicKey, messageData, signature);

        if (!signatureValid) {
            return resultBuilder
                    .signatureStatus(StageStatus.FAILED)
                    .blockedAtStage("簽章驗證")
                    .errorMessage("簽章驗證失敗")
                    .success(false)
                    .build();
        }

        resultBuilder.signatureStatus(StageStatus.PASSED);

        return resultBuilder.blockedAtStage("無").success(true).build();
    }

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
    }

    @Getter
    @Builder
    static class VerificationResult {
        @Builder.Default
        private StageStatus timestampStatus = StageStatus.NOT_EXECUTED;

        @Builder.Default
        private StageStatus nonceStatus = StageStatus.NOT_EXECUTED;

        @Builder.Default
        private StageStatus publicKeyStatus = StageStatus.NOT_EXECUTED;

        @Builder.Default
        private StageStatus signatureStatus = StageStatus.NOT_EXECUTED;

        private String blockedAtStage;
        private String errorMessage;

        @Builder.Default
        private boolean success = false;
    }

    @Getter
    @Builder
    static class CombinationAttackResult {
        private String attackName;
        private String modifiedFields;
        private String expectedBlockStage;
        private String actualBlockStage;
        private boolean blocked;
    }
}
