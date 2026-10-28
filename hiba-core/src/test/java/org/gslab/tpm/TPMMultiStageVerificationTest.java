package org.gslab.tpm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.IOException;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import org.gslab.util.Util;
import org.gslab.configuration.MessageVerificationConfig;
import org.gslab.handlers.NonceValidator;
import org.gslab.handlers.TimestampValidator;
import org.gslab.model.MessageVerificationResult;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * TPM 多階段驗證攻擊測試
 *
 * <p>
 * 此測試展示每種攻擊如何在特定驗證階段被阻擋。
 * 每次攻擊都會經過完整的四階段驗證流程：
 * <ol>
 *   <li><b>時間戳記驗證：</b>檢查訊息時間戳記是否在可接受範圍內（±5 分鐘）</li>
 *   <li><b>Nonce 驗證：</b>檢查 Nonce 是否已被使用過（防止重放攻擊）</li>
 *   <li><b>公鑰驗證：</b>驗證公鑰是否與區塊鏈上已註冊的公鑰一致</li>
 *   <li><b>簽章驗證：</b>使用已驗證的公鑰驗證簽章</li>
 * </ol>
 * </p>
 *
 * <p>
 * 每個測試都會清楚顯示攻擊在哪個階段被阻擋，
 * 提供多層安全機制的完整展示。
 * </p>
 */
class TPMMultiStageVerificationTest {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM_RAW = "NONEwithRSA";
    private static final String REPORT_DIR = "verification_reports";
    private static final List<TestReportEntry> REPORT_ENTRIES = Collections.synchronizedList(new ArrayList<>());

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

    // 來自 TPM 簽章訊息的原始資料
    private JSONObject tpmSignatureInfo;
    private String originalSignature;
    private String originalPublicKey;
    private String originalMessageData;
    private String originalNonce;
    private long originalTimestamp;
    private String originalDeviceId;

    // 目前測試資訊與摘要
    private String currentTestClassName;
    private String currentTestMethodName;
    private String currentDisplayName;
    private SignedMessageSummary currentSignedMessageSummary;

    // 驗證器
    private TimestampValidator timestampValidator;
    private NonceValidator nonceValidator;

    // 公鑰註冊表（可為 Mock 或真實區塊鏈）
    private TPMTestBlockchainSupport.PublicKeyRegistry publicKeyRegistry;

    // JSON 資源路徑（可透過系統屬性 tpm.test.json 指定，預設為 /tpm_verification_sample.json）
    private static final String JSON_RESOURCE_PATH =
            System.getProperty("tpm.test.json", "/tpm_verification_sample.json");

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        currentTestClassName = testInfo.getTestClass().map(Class::getSimpleName).orElse("UnknownClass");
        currentTestMethodName =
                testInfo.getTestMethod().map(method -> method.getName()).orElse("unknownMethod");
        currentDisplayName = testInfo.getDisplayName();
        currentSignedMessageSummary = null;

        // 支援絕對路徑或 classpath 資源
        InputStream is;
        if (JSON_RESOURCE_PATH.startsWith("/") && Files.exists(Path.of(JSON_RESOURCE_PATH))) {
            // 絕對路徑的檔案
            is = new FileInputStream(JSON_RESOURCE_PATH);
            System.out.println("使用外部檔案: " + JSON_RESOURCE_PATH);
        } else {
            // classpath 資源
            is = getClass().getResourceAsStream(JSON_RESOURCE_PATH);
            System.out.println("使用 classpath 資源: " + JSON_RESOURCE_PATH);
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

        // 使用預設配置初始化驗證器
        MessageVerificationConfig config = MessageVerificationConfig.defaultConfig();
        timestampValidator = new TimestampValidator(config);
        nonceValidator = NonceValidator.getInstance();
        nonceValidator.clear();

        // 初始化公鑰註冊表（根據系統屬性選擇 Mock 或真實區塊鏈）
        publicKeyRegistry = TPMTestBlockchainSupport.createRegistry(originalDeviceId, originalPublicKey);

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
        System.out.println("                    TPM 多階段驗證攻擊測試                              ");
        System.out.println("========================================================================");
        System.out.println("設備 ID: " + Util.truncateFirstAndLast(originalDeviceId, 8));
        System.out.println("TPM 版本: " + tpmSignatureInfo.getString("tpmVersion"));
        System.out.println();
    }

    /**
     * 顯示 SignedMessage 的內容（先顯示原始內容，再顯示修改的欄位）
     */
    private void printSignedMessageContent(AttackScenario scenario, String modifiedField, String attackDescription) {
        // 先顯示原始的 SignedMessage 內容
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                   原始 SignedMessage 內容                           │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 欄位            │ 值                                                │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ 時間戳記        │ %s%n", originalTimestamp);
        System.out.printf("│ Nonce           │ %s%n", Util.truncateFirstAndLast(originalNonce, 24));
        System.out.printf("│ 設備 ID         │ %s%n", Util.truncateFirstAndLast(originalDeviceId, 8));
        System.out.printf("│ 公鑰            │ %s%n", Util.truncateFirstAndLast(originalPublicKey, 24));
        System.out.printf("│ 簽章            │ %s%n", Util.truncateFirstAndLast(originalSignature, 24));
        System.out.printf("│ 訊息資料        │ %s%n", Util.truncateFirstAndLast(originalMessageData, 24));
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        // 如果有修改欄位，顯示攻擊詳情
        if (!modifiedField.equals("none")) {
            System.out.println();
            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                         攻擊修改詳情                                │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.println("│ 攻擊方式: " + attackDescription);
            System.out.println("│ 修改欄位: " + getFieldDisplayName(modifiedField));
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");

            // 顯示修改前後的值
            switch (modifiedField) {
                case "timestamp":
                    System.out.println("│ 原始值: " + originalTimestamp);
                    System.out.println("│ 修改後: " + scenario.getTimestamp() + " [已修改]");
                    break;
                case "nonce":
                    System.out.println("│ 原始值: " + Util.truncateFirstAndLast(originalNonce, 24));
                    System.out.println("│ 修改後: " + Util.truncateFirstAndLast(scenario.getNonce(), 24) + " [已修改]");
                    break;
                case "deviceId":
                    System.out.println("│ 原始值: " + Util.truncateFirstAndLast(originalDeviceId, 8));
                    System.out.println("│ 修改後: " + Util.truncateFirstAndLast(scenario.getDeviceId(), 8) + " [已修改]");
                    break;
                case "publicKey":
                    System.out.println("│ 原始值: " + Util.truncateFirstAndLast(originalPublicKey, 24));
                    System.out.println("│ 修改後: " + Util.truncateFirstAndLast(scenario.getPublicKey(), 24) + " [已修改]");
                    break;
                case "signature":
                    System.out.println("│ 原始值: " + Util.truncateFirstAndLast(originalSignature, 24));
                    System.out.println("│ 修改後: " + Util.truncateFirstAndLast(scenario.getSignature(), 24) + " [已修改]");
                    break;
                case "messageData":
                    System.out.println("│ 原始值: " + Util.truncateFirstAndLast(originalMessageData, 24));
                    System.out.println("│ 修改後: " + Util.truncateFirstAndLast(scenario.getMessageData(), 24) + " [已修改]");
                    break;
            }

            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        }
        System.out.println();
        recordSignedMessageSummary(scenario, modifiedField, attackDescription);
    }

    private String getFieldDisplayName(String fieldName) {
        switch (fieldName) {
            case "timestamp":
                return "時間戳記 (timestamp)";
            case "nonce":
                return "Nonce";
            case "deviceId":
                return "設備 ID (deviceId)";
            case "publicKey":
                return "公鑰 (publicKey)";
            case "signature":
                return "簽章 (signature)";
            case "messageData":
                return "訊息資料 (messageData)";
            default:
                return fieldName;
        }
    }

    private void recordSignedMessageSummary(AttackScenario scenario, String modifiedField, String attackDescription) {
        if (scenario == null) {
            currentSignedMessageSummary = SignedMessageSummary.empty();
            return;
        }

        boolean isNone = "none".equals(modifiedField);
        String displayFieldName = isNone ? "無" : getFieldDisplayName(modifiedField);
        String originalValue = isNone ? "-" : getOriginalFieldValue(modifiedField);
        String modifiedValue = isNone ? "-" : getModifiedFieldValue(modifiedField, scenario);

        currentSignedMessageSummary =
                new SignedMessageSummary(displayFieldName, attackDescription, originalValue, modifiedValue);
    }

    private String getOriginalFieldValue(String modifiedField) {
        switch (modifiedField) {
            case "timestamp":
                return String.valueOf(originalTimestamp);
            case "nonce":
                return Util.truncateFirstAndLast(originalNonce, 24);
            case "deviceId":
                return Util.truncateFirstAndLast(originalDeviceId, 8);
            case "publicKey":
                return Util.truncateFirstAndLast(originalPublicKey, 24);
            case "signature":
                return Util.truncateFirstAndLast(originalSignature, 24);
            case "messageData":
                return Util.truncateFirstAndLast(originalMessageData, 24);
            default:
                return "-";
        }
    }

    private String getModifiedFieldValue(String modifiedField, AttackScenario scenario) {
        switch (modifiedField) {
            case "timestamp":
                return String.valueOf(scenario.getTimestamp());
            case "nonce":
                return Util.truncateFirstAndLast(scenario.getNonce(), 24);
            case "deviceId":
                return Util.truncateFirstAndLast(scenario.getDeviceId(), 8);
            case "publicKey":
                return Util.truncateFirstAndLast(scenario.getPublicKey(), 24);
            case "signature":
                return Util.truncateFirstAndLast(scenario.getSignature(), 24);
            case "messageData":
                return Util.truncateFirstAndLast(scenario.getMessageData(), 24);
            default:
                return "-";
        }
    }

    /**
     * 執行完整的四階段驗證流程
     */
    private VerificationResult executeFullVerification(AttackScenario scenario) throws Exception {
        VerificationResult.VerificationResultBuilder resultBuilder = VerificationResult.builder();

        System.out.println("--- 開始多階段驗證流程 ---");

        // 階段 1：時間戳記驗證
        System.out.println();
        System.out.println("階段 1：時間戳記驗證");
        System.out.println("   訊息時間戳記: " + scenario.getTimestamp());
        System.out.println("   目前時間: " + System.currentTimeMillis());
        System.out.println("   時間差: " + (System.currentTimeMillis() - scenario.getTimestamp()) + " 毫秒");

        MessageVerificationResult timestampResult = timestampValidator.validate(scenario.getTimestamp());

        if (!timestampResult.isSuccess()) {
            System.out.println("   結果: 失敗 - " + timestampResult.getMessage());
            System.out.println("   攻擊在階段 1（時間戳記驗證）被阻擋");
            return resultBuilder
                    .timestampStatus(StageStatus.FAILED)
                    .nonceStatus(StageStatus.NOT_EXECUTED)
                    .publicKeyStatus(StageStatus.NOT_EXECUTED)
                    .signatureStatus(StageStatus.NOT_EXECUTED)
                    .blockedAtStage("時間戳記驗證")
                    .errorMessage(timestampResult.getMessage())
                    .build();
        }
        System.out.println("   結果: 通過");
        resultBuilder.timestampStatus(StageStatus.PASSED);

        // 階段 2：Nonce 驗證
        System.out.println();
        System.out.println("階段 2：Nonce 驗證");
        System.out.println("   Nonce: " + Util.truncateFirstAndLast(scenario.getNonce(), 20));

        MessageVerificationResult nonceResult = nonceValidator.validateAndRecord(scenario.getNonce());

        if (!nonceResult.isSuccess()) {
            System.out.println("   結果: 失敗 - " + nonceResult.getMessage());
            System.out.println("   攻擊在階段 2（Nonce 驗證）被阻擋");
            return resultBuilder
                    .nonceStatus(StageStatus.FAILED)
                    .publicKeyStatus(StageStatus.NOT_EXECUTED)
                    .signatureStatus(StageStatus.NOT_EXECUTED)
                    .blockedAtStage("Nonce 驗證")
                    .errorMessage(nonceResult.getMessage())
                    .build();
        }
        System.out.println("   結果: 通過");
        resultBuilder.nonceStatus(StageStatus.PASSED);

        // 階段 3：公鑰驗證
        System.out.println();
        System.out.println("階段 3：公鑰驗證");
        System.out.println("   設備 ID: " + Util.truncateFirstAndLast(scenario.getDeviceId(), 8));

        Optional<String> registeredPublicKey = publicKeyRegistry.getPublicKey(scenario.getDeviceId());
        boolean publicKeyValid = false;
        String publicKeyMessage = "";

        if (registeredPublicKey.isEmpty()) {
            publicKeyMessage = "設備未在區塊鏈上註冊";
            System.out.println("   查詢結果: 設備未註冊");
        } else if (!registeredPublicKey.get().equals(scenario.getPublicKey())) {
            publicKeyMessage = "公鑰不匹配（可能是中間人攻擊）";
            System.out.println("   查詢結果: 公鑰不匹配");
            System.out.println("   區塊鏈公鑰: " + Util.truncateFirstAndLast(registeredPublicKey.get(), 16));
            System.out.println("   訊息公鑰:   " + Util.truncateFirstAndLast(scenario.getPublicKey(), 16));
        } else {
            publicKeyValid = true;
            System.out.println("   查詢結果: 公鑰驗證通過");
        }

        if (!publicKeyValid) {
            System.out.println("   結果: 失敗 - " + publicKeyMessage);
            System.out.println("   攻擊在階段 3（公鑰驗證）被阻擋");
            return resultBuilder
                    .publicKeyStatus(StageStatus.FAILED)
                    .signatureStatus(StageStatus.NOT_EXECUTED)
                    .blockedAtStage("公鑰驗證")
                    .errorMessage(publicKeyMessage)
                    .build();
        }
        System.out.println("   結果: 通過");
        resultBuilder.publicKeyStatus(StageStatus.PASSED);

        // 階段 4：簽章驗證
        System.out.println();
        System.out.println("階段 4：簽章驗證");
        System.out.println("   簽章長度: " + scenario.getSignature().length() + " 字元 (Base64)");
        System.out.println("   訊息資料長度: " + scenario.getMessageData().length() + " 字元 (Base64)");

        PublicKey publicKey = extractPublicKey(scenario.getPublicKey());
        byte[] messageData = Base64.getDecoder().decode(scenario.getMessageData());
        byte[] signature = Base64.getDecoder().decode(scenario.getSignature());

        boolean signatureValid = TPMService.verifyWithJavaCrypto(publicKey, messageData, signature);

        if (!signatureValid) {
            System.out.println("   結果: 失敗 - 簽章驗證失敗");
            System.out.println("   攻擊在階段 4（簽章驗證）被阻擋");
            return resultBuilder
                    .signatureStatus(StageStatus.FAILED)
                    .blockedAtStage("簽章驗證")
                    .errorMessage("簽章驗證失敗")
                    .build();
        }

        System.out.println("   結果: 通過");
        resultBuilder.signatureStatus(StageStatus.PASSED);

        System.out.println();
        System.out.println("--- 所有驗證階段皆通過 ---");

        return resultBuilder.blockedAtStage("無").build();
    }

    @Nested
    @DisplayName("基準測試 - 有效訊息驗證")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BaselineTests {

        @Test
        @Order(1)
        @DisplayName("有效訊息應通過所有四個階段")
        void testValidMessagePassesAllStages() throws Exception {
            System.out.println("\n=== 測試：有效訊息（基準測試）===\n");

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "none", "無（正常訊息）");

            VerificationResult result = executeFullVerification(scenario);

            printSuccessSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getBlockedAtStage()).isEqualTo("無");
        }
    }

    @Nested
    @DisplayName("攻擊模擬 - 在階段 1（時間戳記）被阻擋")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TimestampAttackTests {

        @Test
        @Order(1)
        @DisplayName("過期訊息應在階段 1（時間戳記驗證）被阻擋")
        void testExpiredMessage() throws Exception {
            System.out.println("\n=== 測試：過期訊息攻擊 ===\n");

            long expiredTimestamp = System.currentTimeMillis() - (10 * 60 * 1000L);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(expiredTimestamp)
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "timestamp", "將時間戳記設為 10 分鐘之前");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getBlockedAtStage()).isEqualTo("時間戳記驗證");
        }

        @Test
        @Order(2)
        @DisplayName("未來訊息應在階段 1（時間戳記驗證）被阻擋")
        void testFutureMessage() throws Exception {
            System.out.println("\n=== 測試：未來訊息攻擊 ===\n");

            long futureTimestamp = System.currentTimeMillis() + (10 * 60 * 1000L);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(futureTimestamp)
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "timestamp", "將時間戳記設為 10 分鐘之後");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getBlockedAtStage()).isEqualTo("時間戳記驗證");
        }
    }

    @Nested
    @DisplayName("攻擊模擬 - 在階段 2（Nonce）被阻擋")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class NonceAttackTests {

        @Test
        @Order(1)
        @DisplayName("重用 Nonce 的重放攻擊應在階段 2 被阻擋")
        void testReplayAttackReusedNonce() throws Exception {
            System.out.println("\n=== 測試：重放攻擊（重用 Nonce）===\n");

            String fixedNonce = NonceValidator.generateNonce();

            // 第一次請求 - 應通過所有階段
            System.out.println(">>> 第一次請求（合法）<<<\n");
            AttackScenario firstRequest = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(fixedNonce)
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(firstRequest, "none", "正常的第一次請求");

            VerificationResult firstResult = executeFullVerification(firstRequest);
            assertThat(firstResult.getBlockedAtStage()).isEqualTo("無");

            System.out.println("\n>>> 第二次請求（重放攻擊）<<<\n");

            // 重放攻擊 - 相同的 Nonce 應被阻擋
            AttackScenario replayRequest = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(fixedNonce)
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(replayRequest, "nonce", "攻擊者重用已使用過的 Nonce");

            VerificationResult replayResult = executeFullVerification(replayRequest);

            printAttackSummary(replayResult);

            assertThat(replayResult.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(replayResult.getNonceStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(replayResult.getPublicKeyStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(replayResult.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(replayResult.getBlockedAtStage()).isEqualTo("Nonce 驗證");
        }

        @Test
        @Order(2)
        @DisplayName("缺少 Nonce 應在階段 2 被阻擋")
        void testMissingNonce() throws Exception {
            System.out.println("\n=== 測試：缺少 Nonce 攻擊 ===\n");

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(null)
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "nonce", "移除 Nonce 欄位（設為 null）");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getBlockedAtStage()).isEqualTo("Nonce 驗證");
        }
    }

    @Nested
    @DisplayName("攻擊模擬 - 在階段 3（公鑰）被阻擋")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PublicKeyAttackTests {

        @Test
        @Order(1)
        @DisplayName("中間人攻擊（替換公鑰）應在階段 3 被阻擋")
        void testMitmPublicKeySubstitution() throws Exception {
            System.out.println("\n=== 測試：中間人攻擊（公鑰替換）===\n");

            // 產生攻擊者的金鑰對
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(2048);
            KeyPair attackerKeyPair = keyGen.generateKeyPair();
            String attackerPublicKey = Base64.getEncoder()
                    .encodeToString(attackerKeyPair.getPublic().getEncoded());

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(attackerPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "publicKey", "攻擊者在傳輸過程中替換為自己的公鑰");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getBlockedAtStage()).isEqualTo("公鑰驗證");
        }

        @Test
        @Order(2)
        @DisplayName("未註冊設備應在階段 3 被阻擋")
        void testUnregisteredDevice() throws Exception {
            System.out.println("\n=== 測試：未註冊設備攻擊 ===\n");

            String fakeDeviceId = "FAKE_DEVICE_" + System.currentTimeMillis();

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(fakeDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "deviceId", "使用未在區塊鏈上註冊的設備 ID");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getBlockedAtStage()).isEqualTo("公鑰驗證");
        }
    }

    @Nested
    @DisplayName("攻擊模擬 - 在階段 4（簽章）被阻擋")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SignatureAttackTests {

        @Test
        @Order(1)
        @DisplayName("竄改訊息資料應在階段 4 被阻擋")
        void testTamperedMessageData() throws Exception {
            System.out.println("\n=== 測試：資料完整性攻擊（竄改訊息）===\n");

            // 竄改訊息資料
            byte[] originalData = Base64.getDecoder().decode(originalMessageData);
            byte[] tamperedData = originalData.clone();
            tamperedData[0] = (byte) (tamperedData[0] ^ 0xFF);
            String tamperedMessageData = Base64.getEncoder().encodeToString(tamperedData);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(tamperedMessageData)
                    .build();

            printSignedMessageContent(scenario, "messageData", "修改訊息資料的第一個位元組");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("簽章驗證");
        }

        @Test
        @Order(2)
        @DisplayName("偽造簽章應在階段 4 被阻擋")
        void testForgedSignature() throws Exception {
            System.out.println("\n=== 測試：簽章偽造攻擊 ===\n");

            // 建立偽造的簽章（隨機位元組）
            byte[] forgedSignature = new byte[256];
            for (int i = 0; i < forgedSignature.length; i++) {
                forgedSignature[i] = (byte) (Math.random() * 256);
            }
            String forgedSignatureBase64 = Base64.getEncoder().encodeToString(forgedSignature);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(forgedSignatureBase64)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "signature", "使用隨機產生的偽造簽章");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("簽章驗證");
        }

        @Test
        @Order(3)
        @DisplayName("跨訊息簽章攻擊應在階段 4 被阻擋")
        void testCrossMessageSignatureAttack() throws Exception {
            System.out.println("\n=== 測試：跨訊息簽章攻擊 ===\n");

            // 使用原始簽章搭配不同的訊息資料
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            byte[] messageA = "原始訊息 A".getBytes(StandardCharsets.UTF_8);
            byte[] signatureA = signData(keyPair.getPrivate(), messageA);

            byte[] messageB = "不同的訊息 B".getBytes(StandardCharsets.UTF_8);

            String publicKeyBase64 =
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            String signatureBase64 = Base64.getEncoder().encodeToString(signatureA);
            String messageBBase64 = Base64.getEncoder().encodeToString(messageB);

            // 在區塊鏈上註冊此金鑰對（僅 Mock 模式）
            String testDeviceId = "TEST_DEVICE_" + System.currentTimeMillis();
            if (publicKeyRegistry instanceof TPMTestBlockchainSupport.MockBlockchainRegistry) {
                ((TPMTestBlockchainSupport.MockBlockchainRegistry) publicKeyRegistry)
                        .registerDevice(testDeviceId, publicKeyBase64);
            }

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(testDeviceId)
                    .publicKey(publicKeyBase64)
                    .signature(signatureBase64)
                    .messageData(messageBBase64)
                    .build();

            printSignedMessageContent(scenario, "messageData", "使用訊息 A 的簽章來驗證訊息 B");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("簽章驗證");
        }
    }

    /**
     * 完整攻擊模擬測試 - 論文展示用
     *
     * <p>
     * 此區塊集中展示五種主要攻擊類型，每種攻擊均使用統一格式，
     * 便於論文答辯時進行完整展示。
     * </p>
     *
     * <table>
     * <tr>
     * <th>攻擊類型</th>
     * <th>攻擊方式</th>
     * <th>預期阻擋階段</th>
     * </tr>
     * <tr>
     * <td>中間人攻擊</td>
     * <td>替換公鑰並重新簽章</td>
     * <td>階段 3（公鑰驗證）</td>
     * </tr>
     * <tr>
     * <td>重送攻擊（Nonce）</td>
     * <td>重用已使用過的 Nonce</td>
     * <td>階段 2（Nonce 驗證）</td>
     * </tr>
     * <tr>
     * <td>重送攻擊（過期訊息）</td>
     * <td>將時間戳記設為 10 分鐘之前</td>
     * <td>階段 1（時間戳記驗證）</td>
     * </tr>
     * <tr>
     * <td>身份偽造</td>
     * <td>使用未註冊的硬體 ID</td>
     * <td>階段 3（公鑰驗證）</td>
     * </tr>
     * <tr>
     * <td>資料竄改</td>
     * <td>修改訊息第一個位元組</td>
     * <td>階段 4（簽章驗證）</td>
     * </tr>
     * </table>
     */
    @Nested
    @DisplayName("完整攻擊模擬 - 論文展示用")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CompleteAttackSimulationTests {

        @Test
        @Order(1)
        @DisplayName("正常流程 - 基準測試")
        void testNormalVerificationFlow() throws Exception {
            System.out.println("\n=== 正常流程 - 基準測試 ===\n");
            System.out.println("攻擊類型: 無（正常訊息）");
            System.out.println("攻擊方式: 無");
            System.out.println();

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "none", "無（正常訊息）");

            VerificationResult result = executeFullVerification(scenario);

            printSuccessSummary(result);

            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getBlockedAtStage()).isEqualTo("無");
        }

        @Test
        @Order(2)
        @DisplayName("中間人攻擊 - 替換公鑰並重新簽章")
        void testMitmWithResign() throws Exception {
            System.out.println("\n=== 中間人攻擊 - 替換公鑰並重新簽章 ===\n");
            System.out.println("攻擊類型: 中間人攻擊");
            System.out.println("攻擊方式: 在傳輸過程中替換為攻擊者的公鑰，重新簽章");
            System.out.println();

            // 產生攻擊者的金鑰對
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(2048);
            KeyPair attackerKeyPair = keyGen.generateKeyPair();
            String attackerPublicKey = Base64.getEncoder()
                    .encodeToString(attackerKeyPair.getPublic().getEncoded());

            // 攻擊者使用自己的私鑰重新簽章原始訊息
            byte[] originalData = Base64.getDecoder().decode(originalMessageData);
            byte[] attackerSignature = signData(attackerKeyPair.getPrivate(), originalData);
            String attackerSignatureBase64 = Base64.getEncoder().encodeToString(attackerSignature);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(attackerPublicKey)
                    .signature(attackerSignatureBase64)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "publicKey", "攻擊者替換公鑰並使用自己的私鑰重新簽章");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            // 應在階段 3（公鑰驗證）被阻擋，因為區塊鏈上的公鑰與攻擊者公鑰不匹配
            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getBlockedAtStage()).isEqualTo("公鑰驗證");
        }

        @Test
        @Order(3)
        @DisplayName("重送攻擊 - 過期訊息（時間戳記設為 10 分鐘之前）")
        void testReplayAttackExpiredMessage() throws Exception {
            System.out.println("\n=== 重送攻擊 - 過期訊息 ===\n");
            System.out.println("攻擊類型: 重送攻擊（過期訊息）");
            System.out.println("攻擊方式: 將時間戳記設為 10 分鐘之前");
            System.out.println();

            // 攻擊者嘗試重送一個 10 分鐘前攔截的訊息
            long expiredTimestamp = System.currentTimeMillis() - (10 * 60 * 1000L);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(expiredTimestamp)
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "timestamp", "將時間戳記設為 10 分鐘之前（過期訊息）");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            // 應在階段 1（時間戳記驗證）被阻擋
            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getBlockedAtStage()).isEqualTo("時間戳記驗證");
        }

        @Test
        @Order(4)
        @DisplayName("重送攻擊 - 重用已使用過的 Nonce")
        void testReplayAttack() throws Exception {
            System.out.println("\n=== 重送攻擊 - 重用已使用過的 Nonce ===\n");
            System.out.println("攻擊類型: 重送攻擊");
            System.out.println("攻擊方式: 重用已使用過的 Nonce");
            System.out.println();

            String fixedNonce = NonceValidator.generateNonce();

            // 第一次請求 - 應通過所有階段
            System.out.println(">>> 第一次請求（合法）<<<\n");
            AttackScenario firstRequest = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(fixedNonce)
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(firstRequest, "none", "正常的第一次請求");

            VerificationResult firstResult = executeFullVerification(firstRequest);
            assertThat(firstResult.getBlockedAtStage()).isEqualTo("無");

            System.out.println("\n>>> 第二次請求（重送攻擊）<<<\n");

            // 重送攻擊 - 相同的 Nonce 應被阻擋
            AttackScenario replayRequest = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(fixedNonce)
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(replayRequest, "nonce", "攻擊者重用已使用過的 Nonce");

            VerificationResult replayResult = executeFullVerification(replayRequest);

            printAttackSummary(replayResult);

            // 應在階段 2（Nonce 驗證）被阻擋
            assertThat(replayResult.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(replayResult.getNonceStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(replayResult.getPublicKeyStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(replayResult.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(replayResult.getBlockedAtStage()).isEqualTo("Nonce 驗證");
        }

        @Test
        @Order(5)
        @DisplayName("身份偽造 - 使用未註冊的硬體 ID")
        void testIdentityForgery() throws Exception {
            System.out.println("\n=== 身份偽造 - 使用未註冊的硬體 ID ===\n");
            System.out.println("攻擊類型: 身份偽造");
            System.out.println("攻擊方式: 使用未在區塊鏈上註冊的硬體 ID");
            System.out.println();

            String fakeDeviceId = "FAKE_DEVICE_" + System.currentTimeMillis();

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(fakeDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(originalMessageData)
                    .build();

            printSignedMessageContent(scenario, "deviceId", "使用未在區塊鏈上註冊的硬體 ID");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            // 應在階段 3（公鑰驗證）被阻擋，因為設備未註冊
            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.NOT_EXECUTED);
            assertThat(result.getBlockedAtStage()).isEqualTo("公鑰驗證");
        }

        @Test
        @Order(6)
        @DisplayName("資料竄改 - 修改訊息第一個位元組")
        void testDataTampering() throws Exception {
            System.out.println("\n=== 資料竄改 - 修改訊息第一個位元組 ===\n");
            System.out.println("攻擊類型: 資料竄改");
            System.out.println("攻擊方式: 修改訊息資料的第一個位元組");
            System.out.println();

            // 竄改訊息資料
            byte[] originalData = Base64.getDecoder().decode(originalMessageData);
            byte[] tamperedData = originalData.clone();
            tamperedData[0] = (byte) (tamperedData[0] ^ 0xFF);
            String tamperedMessageData = Base64.getEncoder().encodeToString(tamperedData);

            AttackScenario scenario = AttackScenario.builder()
                    .timestamp(System.currentTimeMillis())
                    .nonce(NonceValidator.generateNonce())
                    .deviceId(originalDeviceId)
                    .publicKey(originalPublicKey)
                    .signature(originalSignature)
                    .messageData(tamperedMessageData)
                    .build();

            printSignedMessageContent(scenario, "messageData", "修改訊息資料的第一個位元組");

            VerificationResult result = executeFullVerification(scenario);

            printAttackSummary(result);

            // 應在階段 4（簽章驗證）被阻擋
            assertThat(result.getTimestampStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getNonceStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getPublicKeyStatus()).isEqualTo(StageStatus.PASSED);
            assertThat(result.getSignatureStatus()).isEqualTo(StageStatus.FAILED);
            assertThat(result.getBlockedAtStage()).isEqualTo("簽章驗證");
        }
    }

    // 輔助方法

    private void printSuccessSummary(VerificationResult result) {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                         驗證結果摘要                                │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 階段 1（時間戳記）: " + formatStatus(result.getTimestampStatus()));
        System.out.println("│ 階段 2（Nonce）:    " + formatStatus(result.getNonceStatus()));
        System.out.println("│ 階段 3（公鑰）:     " + formatStatus(result.getPublicKeyStatus()));
        System.out.println("│ 階段 4（簽章）:     " + formatStatus(result.getSignatureStatus()));
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 結果: 訊息驗證成功，已接受                                          │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        addReportEntry("success", result);
    }

    private void printAttackSummary(VerificationResult result) {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                         攻擊結果摘要                                │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 階段 1（時間戳記）: " + formatStatus(result.getTimestampStatus()));
        System.out.println("│ 階段 2（Nonce）:    " + formatStatus(result.getNonceStatus()));
        System.out.println("│ 階段 3（公鑰）:     " + formatStatus(result.getPublicKeyStatus()));
        System.out.println("│ 階段 4（簽章）:     " + formatStatus(result.getSignatureStatus()));
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 阻擋於: " + result.getBlockedAtStage());
        System.out.println("│ 錯誤訊息: " + result.getErrorMessage());
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 結果: 攻擊已成功阻擋                                                │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        addReportEntry("attack", result);
    }

    private void addReportEntry(String resultType, VerificationResult result) {
        if (result == null) {
            return;
        }

        SignedMessageSummary summary =
                currentSignedMessageSummary != null ? currentSignedMessageSummary : SignedMessageSummary.empty();

        TestReportEntry entry = new TestReportEntry(
                currentTestClassName,
                currentTestMethodName,
                currentDisplayName,
                result.getTimestampStatus(),
                result.getNonceStatus(),
                result.getPublicKeyStatus(),
                result.getSignatureStatus(),
                result.getBlockedAtStage(),
                result.getErrorMessage(),
                summary,
                resultType);

        REPORT_ENTRIES.add(entry);
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

    @AfterAll
    static void writeHtmlReport() throws IOException {
        if (REPORT_ENTRIES.isEmpty()) {
            return;
        }

        HtmlReportExporter exporter = new HtmlReportExporter();
        exporter.exportAll(REPORT_ENTRIES, Path.of(REPORT_DIR));
    }

    /**
     * 攻擊場景資料容器
     */
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

    /**
     * 驗證結果資料容器
     */
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
    }

    static class SignedMessageSummary {
        private final String modifiedField;
        private final String attackDescription;
        private final String originalValue;
        private final String modifiedValue;

        SignedMessageSummary(
                String modifiedField, String attackDescription, String originalValue, String modifiedValue) {
            this.modifiedField = modifiedField;
            this.attackDescription = attackDescription;
            this.originalValue = originalValue;
            this.modifiedValue = modifiedValue;
        }

        static SignedMessageSummary empty() {
            return new SignedMessageSummary("無", "-", "-", "-");
        }

        String getModifiedField() {
            return modifiedField;
        }

        String getAttackDescription() {
            return attackDescription;
        }

        String getOriginalValue() {
            return originalValue;
        }

        String getModifiedValue() {
            return modifiedValue;
        }
    }

    static class TestReportEntry {
        private final String testClassName;
        private final String testMethodName;
        private final String displayName;
        private final StageStatus timestampStatus;
        private final StageStatus nonceStatus;
        private final StageStatus publicKeyStatus;
        private final StageStatus signatureStatus;
        private final String blockedAtStage;
        private final String errorMessage;
        private final SignedMessageSummary signedMessageSummary;
        private final String resultType;

        TestReportEntry(
                String testClassName,
                String testMethodName,
                String displayName,
                StageStatus timestampStatus,
                StageStatus nonceStatus,
                StageStatus publicKeyStatus,
                StageStatus signatureStatus,
                String blockedAtStage,
                String errorMessage,
                SignedMessageSummary signedMessageSummary,
                String resultType) {
            this.testClassName = testClassName;
            this.testMethodName = testMethodName;
            this.displayName = displayName;
            this.timestampStatus = timestampStatus;
            this.nonceStatus = nonceStatus;
            this.publicKeyStatus = publicKeyStatus;
            this.signatureStatus = signatureStatus;
            this.blockedAtStage = blockedAtStage;
            this.errorMessage = errorMessage;
            this.signedMessageSummary = signedMessageSummary;
            this.resultType = resultType;
        }

        String getTestClassName() {
            return testClassName;
        }

        String getTestMethodName() {
            return testMethodName;
        }

        String getDisplayName() {
            return displayName;
        }

        StageStatus getTimestampStatus() {
            return timestampStatus;
        }

        StageStatus getNonceStatus() {
            return nonceStatus;
        }

        StageStatus getPublicKeyStatus() {
            return publicKeyStatus;
        }

        StageStatus getSignatureStatus() {
            return signatureStatus;
        }

        String getBlockedAtStage() {
            return blockedAtStage;
        }

        String getErrorMessage() {
            return errorMessage;
        }

        SignedMessageSummary getSignedMessageSummary() {
            return signedMessageSummary;
        }

        String getResultType() {
            return resultType;
        }
    }

    static class HtmlReportExporter {

        void exportAll(List<TestReportEntry> entries, Path outputDir) throws IOException {
            if (entries == null || entries.isEmpty()) {
                return;
            }

            Files.createDirectories(outputDir);

            Map<String, List<TestReportEntry>> entriesByClass =
                    entries.stream().collect(Collectors.groupingBy(TestReportEntry::getTestClassName));

            for (Map.Entry<String, List<TestReportEntry>> entry : entriesByClass.entrySet()) {
                String className = entry.getKey();
                List<TestReportEntry> classEntries = entry.getValue();
                String classHtml = buildClassPage(className, classEntries);
                Files.writeString(outputDir.resolve(className + ".html"), classHtml);
            }

            String indexHtml =
                    buildIndexPage(entries, entriesByClass.keySet().stream().toList());
            Files.writeString(outputDir.resolve("index.html"), indexHtml);
        }

        private String buildIndexPage(List<TestReportEntry> entries, List<String> classNames) {
            StringBuilder html = new StringBuilder();
            appendHtmlHeader(html, "TPM 多階段驗證報告 - 索引");
            html.append("        <h1>TPM 多階段驗證報告 - 索引</h1>\n");
            html.append("        <p class=\"timestamp\">生成時間: ")
                    .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .append("</p>\n");

            html.append("        <div class=\"summary\">\n");
            html.append("            <h2>測試概覽</h2>\n");
            html.append("            <div class=\"metric\"><span class=\"metric-label\">總測試數:</span> ")
                    .append("<span class=\"metric-value\">")
                    .append(entries.size())
                    .append("</span></div>\n");
            html.append("            <div class=\"metric\"><span class=\"metric-label\">測試類別數:</span> ")
                    .append("<span class=\"metric-value\">")
                    .append(classNames.size())
                    .append("</span></div>\n");
            html.append("        </div>\n");

            html.append("        <h2>測試結果索引</h2>\n");
            html.append("        <table>\n");
            html.append("            <thead>\n");
            html.append("                <tr>\n");
            html.append("                    <th>類別</th>\n");
            html.append("                    <th>方法</th>\n");
            html.append("                    <th>顯示名稱</th>\n");
            html.append("                    <th>階段 1</th>\n");
            html.append("                    <th>階段 2</th>\n");
            html.append("                    <th>階段 3</th>\n");
            html.append("                    <th>階段 4</th>\n");
            html.append("                    <th>阻擋於</th>\n");
            html.append("                    <th>錯誤訊息</th>\n");
            html.append("                </tr>\n");
            html.append("            </thead>\n");
            html.append("            <tbody>\n");

            for (TestReportEntry entry : entries) {
                String classFile = entry.getTestClassName() + ".html";
                String anchor = sanitizeId(entry.getTestMethodName());
                html.append("                <tr>\n");
                html.append("                    <td><a href=\"")
                        .append(escapeHtml(classFile))
                        .append("#")
                        .append(anchor)
                        .append("\">")
                        .append(escapeHtml(entry.getTestClassName()))
                        .append("</a></td>\n");
                html.append("                    <td>")
                        .append(escapeHtml(entry.getTestMethodName()))
                        .append("</td>\n");
                html.append("                    <td>")
                        .append(escapeHtml(entry.getDisplayName()))
                        .append("</td>\n");
                appendStatusCell(html, entry.getTimestampStatus());
                appendStatusCell(html, entry.getNonceStatus());
                appendStatusCell(html, entry.getPublicKeyStatus());
                appendStatusCell(html, entry.getSignatureStatus());
                html.append("                    <td>")
                        .append(escapeHtml(nullToDash(entry.getBlockedAtStage())))
                        .append("</td>\n");
                html.append("                    <td>")
                        .append(escapeHtml(nullToDash(entry.getErrorMessage())))
                        .append("</td>\n");
                html.append("                </tr>\n");
            }

            html.append("            </tbody>\n");
            html.append("        </table>\n");
            appendHtmlFooter(html);
            return html.toString();
        }

        private String buildClassPage(String className, List<TestReportEntry> entries) {
            StringBuilder html = new StringBuilder();
            appendHtmlHeader(html, "TPM 多階段驗證報告 - " + className);
            html.append("        <h1>TPM 多階段驗證報告 - ")
                    .append(escapeHtml(className))
                    .append("</h1>\n");
            html.append("        <p class=\"timestamp\">生成時間: ")
                    .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .append("</p>\n");

            html.append("        <h2>測試案例明細</h2>\n");

            for (TestReportEntry entry : entries) {
                String anchor = sanitizeId(entry.getTestMethodName());
                html.append("        <div class=\"card\" id=\"").append(anchor).append("\">\n");
                html.append("            <h3>")
                        .append(escapeHtml(entry.getDisplayName()))
                        .append("</h3>\n");
                html.append("            <p class=\"meta\">方法: ")
                        .append(escapeHtml(entry.getTestMethodName()))
                        .append(" | 類型: ")
                        .append(escapeHtml(entry.getResultType()))
                        .append("</p>\n");

                html.append("            <table>\n");
                html.append("                <thead>\n");
                html.append("                    <tr>\n");
                html.append("                        <th>階段 1</th>\n");
                html.append("                        <th>階段 2</th>\n");
                html.append("                        <th>階段 3</th>\n");
                html.append("                        <th>階段 4</th>\n");
                html.append("                        <th>阻擋於</th>\n");
                html.append("                        <th>錯誤訊息</th>\n");
                html.append("                    </tr>\n");
                html.append("                </thead>\n");
                html.append("                <tbody>\n");
                html.append("                    <tr>\n");
                appendStatusCell(html, entry.getTimestampStatus());
                appendStatusCell(html, entry.getNonceStatus());
                appendStatusCell(html, entry.getPublicKeyStatus());
                appendStatusCell(html, entry.getSignatureStatus());
                html.append("                        <td>")
                        .append(escapeHtml(nullToDash(entry.getBlockedAtStage())))
                        .append("</td>\n");
                html.append("                        <td>")
                        .append(escapeHtml(nullToDash(entry.getErrorMessage())))
                        .append("</td>\n");
                html.append("                    </tr>\n");
                html.append("                </tbody>\n");
                html.append("            </table>\n");

                SignedMessageSummary summary = entry.getSignedMessageSummary();
                html.append("            <h4>SignedMessage 變更摘要</h4>\n");
                html.append("            <table>\n");
                html.append("                <thead>\n");
                html.append("                    <tr>\n");
                html.append("                        <th>修改欄位</th>\n");
                html.append("                        <th>攻擊方式</th>\n");
                html.append("                        <th>原始值</th>\n");
                html.append("                        <th>修改後</th>\n");
                html.append("                    </tr>\n");
                html.append("                </thead>\n");
                html.append("                <tbody>\n");
                html.append("                    <tr>\n");
                html.append("                        <td>")
                        .append(escapeHtml(summary.getModifiedField()))
                        .append("</td>\n");
                html.append("                        <td>")
                        .append(escapeHtml(summary.getAttackDescription()))
                        .append("</td>\n");
                html.append("                        <td>")
                        .append(escapeHtml(summary.getOriginalValue()))
                        .append("</td>\n");
                html.append("                        <td>")
                        .append(escapeHtml(summary.getModifiedValue()))
                        .append("</td>\n");
                html.append("                    </tr>\n");
                html.append("                </tbody>\n");
                html.append("            </table>\n");

                html.append("        </div>\n");
            }

            appendHtmlFooter(html);
            return html.toString();
        }

        private void appendHtmlHeader(StringBuilder html, String title) {
            html.append("<!DOCTYPE html>\n");
            html.append("<html lang=\"zh-TW\">\n");
            html.append("<head>\n");
            html.append("    <meta charset=\"UTF-8\">\n");
            html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
            html.append("    <title>").append(escapeHtml(title)).append("</title>\n");
            html.append("    <style>\n");
            html.append(
                    "        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 20px; background: #f5f5f5; }\n");
            html.append(
                    "        .container { max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
            html.append("        h1 { color: #333; border-bottom: 3px solid #4CAF50; padding-bottom: 10px; }\n");
            html.append("        h2 { color: #555; margin-top: 30px; }\n");
            html.append("        h3 { color: #444; margin-top: 0; }\n");
            html.append("        h4 { color: #666; margin-top: 20px; }\n");
            html.append(
                    "        .summary { background: #f9f9f9; padding: 15px; border-left: 4px solid #4CAF50; margin: 20px 0; }\n");
            html.append(
                    "        .card { background: #fafafa; border: 1px solid #eee; border-radius: 6px; padding: 20px; margin: 20px 0; }\n");
            html.append("        .meta { color: #777; font-size: 0.95em; }\n");
            html.append("        table { width: 100%; border-collapse: collapse; margin: 12px 0 24px; }\n");
            html.append("        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }\n");
            html.append("        th { background: #4CAF50; color: white; }\n");
            html.append("        tr:hover { background: #f5f5f5; }\n");
            html.append("        .metric { display: inline-block; margin: 10px 20px 10px 0; }\n");
            html.append("        .metric-label { font-weight: bold; color: #666; }\n");
            html.append("        .metric-value { color: #333; font-size: 1.2em; }\n");
            html.append("        .timestamp { color: #999; font-size: 0.9em; }\n");
            html.append("        .status-pass { color: #4CAF50; font-weight: bold; }\n");
            html.append("        .status-fail { color: #f44336; font-weight: bold; }\n");
            html.append("        .status-skip { color: #999; font-weight: bold; }\n");
            html.append("    </style>\n");
            html.append("</head>\n");
            html.append("<body>\n");
            html.append("    <div class=\"container\">\n");
        }

        private void appendHtmlFooter(StringBuilder html) {
            html.append("    </div>\n");
            html.append("</body>\n");
            html.append("</html>\n");
        }

        private void appendStatusCell(StringBuilder html, StageStatus status) {
            html.append("                        <td class=\"")
                    .append(statusClass(status))
                    .append("\">")
                    .append(escapeHtml(statusDisplay(status)))
                    .append("</td>\n");
        }

        private String statusClass(StageStatus status) {
            if (status == StageStatus.PASSED) {
                return "status-pass";
            }
            if (status == StageStatus.FAILED) {
                return "status-fail";
            }
            return "status-skip";
        }

        private String statusDisplay(StageStatus status) {
            if (status == null) {
                return "-";
            }
            switch (status) {
                case PASSED:
                    return "通過";
                case FAILED:
                    return "失敗";
                case NOT_EXECUTED:
                    return "未執行";
                default:
                    return status.getDisplayName();
            }
        }

        private String escapeHtml(String text) {
            if (text == null) {
                return "";
            }
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
        }

        private String nullToDash(String value) {
            return value == null || value.isBlank() ? "-" : value;
        }

        private String sanitizeId(String value) {
            if (value == null || value.isBlank()) {
                return "section";
            }
            return value.replaceAll("[^a-zA-Z0-9_-]", "-");
        }
    }
}
