package org.gslab.tpm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
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
 * TPM 邊界值測試
 *
 * <p>
 * 此測試驗證 TPM 驗證機制在邊界條件下的行為。
 * 透過精確的邊界測試，證明驗證邏輯的嚴謹性。
 * </p>
 *
 * <h2>測試項目</h2>
 * <ol>
 *   <li><b>時間戳記過期邊界：</b>299 秒、300 秒、301 秒</li>
 *   <li><b>時間戳記未來邊界：</b>29 秒、30 秒、31 秒</li>
 *   <li><b>Nonce 長度邊界：</b>空字串、1 字元、標準 22 字元</li>
 *   <li><b>簽章長度異常：</b>過短、標準、過長</li>
 * </ol>
 *
 * @author TPM Test Project
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TPMBoundaryValueTest {

    private static final String KEY_ALGORITHM = "RSA";

    // 預設配置值（來自 MessageVerificationConfig）
    private static final long DEFAULT_MAX_AGE_MS = 5 * 60 * 1000L; // 5 分鐘 = 300 秒
    private static final long DEFAULT_CLOCK_SKEW_MS = 30 * 1000L; // 30 秒

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

    // 測試結果收集
    private List<BoundaryTestResult> testResults;

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
        System.out.println("                       TPM 邊界值測試                                  ");
        System.out.println("========================================================================");
        System.out.println();
        System.out.println("測試目的：驗證驗證邏輯在邊界條件下的精確行為");
        System.out.println();
        System.out.println("預設配置：");
        System.out.printf("  - 時間戳記最大有效期：%d 秒 (%d 毫秒)%n", DEFAULT_MAX_AGE_MS / 1000, DEFAULT_MAX_AGE_MS);
        System.out.printf("  - 時鐘偏移容差：%d 秒 (%d 毫秒)%n", DEFAULT_CLOCK_SKEW_MS / 1000, DEFAULT_CLOCK_SKEW_MS);
        System.out.println();
    }

    // ==================== 時間戳記過期邊界測試 ====================

    @Nested
    @DisplayName("時間戳記過期邊界測試 (maxAgeMs = 300 秒)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TimestampExpiryBoundaryTests {

        @Test
        @Order(1)
        @DisplayName("299 秒前的時間戳記應該通過（邊界內）")
        void testTimestamp_299Seconds_ShouldPass() {
            System.out.println("\n=== 時間戳記過期邊界：299 秒（邊界內）===\n");

            long timestamp = System.currentTimeMillis() - (299 * 1000L);
            MessageVerificationResult result = timestampValidator.validate(timestamp);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("時間戳記過期 - 299 秒")
                    .boundaryType("邊界內")
                    .expectedResult("通過")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("299 秒前的時間戳記應該通過驗證").isTrue();
        }

        @Test
        @Order(2)
        @DisplayName("300 秒前的時間戳記應該通過（邊界上）")
        void testTimestamp_300Seconds_ShouldPass() {
            System.out.println("\n=== 時間戳記過期邊界：300 秒（邊界上）===\n");

            long timestamp = System.currentTimeMillis() - (300 * 1000L);
            MessageVerificationResult result = timestampValidator.validate(timestamp);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("時間戳記過期 - 300 秒")
                    .boundaryType("邊界上")
                    .expectedResult("通過")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("300 秒前的時間戳記應該通過驗證（邊界上）").isTrue();
        }

        @Test
        @Order(3)
        @DisplayName("301 秒前的時間戳記應該被拒絕（邊界外）")
        void testTimestamp_301Seconds_ShouldFail() {
            System.out.println("\n=== 時間戳記過期邊界：301 秒（邊界外）===\n");

            long timestamp = System.currentTimeMillis() - (301 * 1000L);
            MessageVerificationResult result = timestampValidator.validate(timestamp);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("時間戳記過期 - 301 秒")
                    .boundaryType("邊界外")
                    .expectedResult("拒絕")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(!result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("301 秒前的時間戳記應該被拒絕").isFalse();
            assertThat(result.getErrorCode()).isEqualTo(MessageVerificationResult.ErrorCode.TIMESTAMP_EXPIRED);
        }
    }

    // ==================== 時間戳記未來邊界測試 ====================

    @Nested
    @DisplayName("時間戳記未來邊界測試 (clockSkewMs = 30 秒)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TimestampFutureBoundaryTests {

        @Test
        @Order(1)
        @DisplayName("未來 29 秒的時間戳記應該通過（邊界內）")
        void testTimestamp_Future29Seconds_ShouldPass() {
            System.out.println("\n=== 時間戳記未來邊界：+29 秒（邊界內）===\n");

            long timestamp = System.currentTimeMillis() + (29 * 1000L);
            MessageVerificationResult result = timestampValidator.validate(timestamp);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("時間戳記未來 - +29 秒")
                    .boundaryType("邊界內")
                    .expectedResult("通過")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("未來 29 秒的時間戳記應該通過驗證").isTrue();
        }

        @Test
        @Order(2)
        @DisplayName("未來 30 秒的時間戳記應該通過（邊界上）")
        void testTimestamp_Future30Seconds_ShouldPass() {
            System.out.println("\n=== 時間戳記未來邊界：+30 秒（邊界上）===\n");

            long timestamp = System.currentTimeMillis() + (30 * 1000L);
            MessageVerificationResult result = timestampValidator.validate(timestamp);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("時間戳記未來 - +30 秒")
                    .boundaryType("邊界上")
                    .expectedResult("通過")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("未來 30 秒的時間戳記應該通過驗證（邊界上）").isTrue();
        }

        @Test
        @Order(3)
        @DisplayName("未來 31 秒的時間戳記應該被拒絕（邊界外）")
        void testTimestamp_Future31Seconds_ShouldFail() {
            System.out.println("\n=== 時間戳記未來邊界：+31 秒（邊界外）===\n");

            long timestamp = System.currentTimeMillis() + (31 * 1000L);
            MessageVerificationResult result = timestampValidator.validate(timestamp);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("時間戳記未來 - +31 秒")
                    .boundaryType("邊界外")
                    .expectedResult("拒絕")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(!result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("未來 31 秒的時間戳記應該被拒絕").isFalse();
            assertThat(result.getErrorCode()).isEqualTo(MessageVerificationResult.ErrorCode.TIMESTAMP_FUTURE);
        }
    }

    // ==================== Nonce 長度邊界測試 ====================

    @Nested
    @DisplayName("Nonce 長度邊界測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class NonceLengthBoundaryTests {

        @Test
        @Order(1)
        @DisplayName("空字串 Nonce 應該被拒絕")
        void testNonce_EmptyString_ShouldFail() {
            System.out.println("\n=== Nonce 長度邊界：空字串 ===\n");

            String nonce = "";
            MessageVerificationResult result = nonceValidator.validateAndRecord(nonce);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("Nonce 長度 - 空字串")
                    .boundaryType("無效輸入")
                    .expectedResult("拒絕")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(!result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("空字串 Nonce 應該被拒絕").isFalse();
            assertThat(result.getErrorCode()).isEqualTo(MessageVerificationResult.ErrorCode.NONCE_MISSING);
        }

        @Test
        @Order(2)
        @DisplayName("null Nonce 應該被拒絕")
        void testNonce_Null_ShouldFail() {
            System.out.println("\n=== Nonce 長度邊界：null ===\n");

            MessageVerificationResult result = nonceValidator.validateAndRecord(null);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("Nonce 長度 - null")
                    .boundaryType("無效輸入")
                    .expectedResult("拒絕")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(!result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("null Nonce 應該被拒絕").isFalse();
        }

        @Test
        @Order(3)
        @DisplayName("只有空白的 Nonce 應該被拒絕")
        void testNonce_WhitespaceOnly_ShouldFail() {
            System.out.println("\n=== Nonce 長度邊界：僅空白 ===\n");

            String nonce = "   ";
            MessageVerificationResult result = nonceValidator.validateAndRecord(nonce);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("Nonce 長度 - 僅空白")
                    .boundaryType("無效輸入")
                    .expectedResult("拒絕")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(!result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("僅空白的 Nonce 應該被拒絕").isFalse();
        }

        @Test
        @Order(4)
        @DisplayName("1 字元 Nonce 應該通過（格式有效）")
        void testNonce_OneChar_ShouldPass() {
            System.out.println("\n=== Nonce 長度邊界：1 字元 ===\n");

            String nonce = "A";
            MessageVerificationResult result = nonceValidator.validateAndRecord(nonce);

            // NonceValidator 目前只檢查是否為空，不驗證長度
            // 1 字元雖然不安全，但技術上可以接受
            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("Nonce 長度 - 1 字元")
                    .boundaryType("最小有效")
                    .expectedResult("通過")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("1 字元 Nonce 應該通過（格式有效）").isTrue();
        }

        @Test
        @Order(5)
        @DisplayName("標準 22 字元 Nonce 應該通過")
        void testNonce_Standard22Chars_ShouldPass() {
            System.out.println("\n=== Nonce 長度邊界：標準 22 字元 ===\n");

            String nonce = NonceValidator.generateNonce();
            System.out.println("產生的 Nonce: " + nonce);
            System.out.println("Nonce 長度: " + nonce.length() + " 字元");

            MessageVerificationResult result = nonceValidator.validateAndRecord(nonce);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("Nonce 長度 - 標準 22 字元")
                    .boundaryType("標準值")
                    .expectedResult("通過")
                    .actualResult(result.isSuccess() ? "通過" : "拒絕")
                    .passed(result.isSuccess())
                    .build();
            testResults.add(testResult);

            printBoundaryTestResult(testResult, result);

            assertThat(result.isSuccess()).as("標準 22 字元 Nonce 應該通過").isTrue();
        }
    }

    // ==================== 簽章長度邊界測試 ====================

    @Nested
    @DisplayName("簽章長度邊界測試 (2048-bit RSA)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SignatureLengthBoundaryTests {

        @Test
        @Order(1)
        @DisplayName("過短簽章（255 bytes）應該驗證失敗")
        void testSignature_TooShort_ShouldFail() throws Exception {
            System.out.println("\n=== 簽章長度邊界：過短（255 bytes）===\n");

            byte[] shortSignature = new byte[255];
            for (int i = 0; i < shortSignature.length; i++) {
                shortSignature[i] = (byte) i;
            }

            boolean result = verifySignature(
                    originalPublicKey, originalMessageData, Base64.getEncoder().encodeToString(shortSignature));

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("簽章長度 - 255 bytes（過短）")
                    .boundaryType("邊界外")
                    .expectedResult("拒絕")
                    .actualResult(result ? "通過" : "拒絕")
                    .passed(!result)
                    .build();
            testResults.add(testResult);

            printSignatureTestResult(testResult, 255, 256);

            assertThat(result).as("過短簽章應該驗證失敗").isFalse();
        }

        @Test
        @Order(2)
        @DisplayName("標準簽章長度（256 bytes）- 原始簽章驗證")
        void testSignature_StandardLength_ShouldPass() throws Exception {
            System.out.println("\n=== 簽章長度邊界：標準（256 bytes）===\n");

            byte[] signatureBytes = Base64.getDecoder().decode(originalSignature);
            System.out.println("原始簽章長度: " + signatureBytes.length + " bytes");

            boolean result = verifySignature(originalPublicKey, originalMessageData, originalSignature);

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("簽章長度 - 256 bytes（標準）")
                    .boundaryType("標準值")
                    .expectedResult("通過")
                    .actualResult(result ? "通過" : "拒絕")
                    .passed(result)
                    .build();
            testResults.add(testResult);

            printSignatureTestResult(testResult, signatureBytes.length, 256);

            assertThat(result).as("標準長度簽章應該驗證通過").isTrue();
        }

        @Test
        @Order(3)
        @DisplayName("過長簽章（257 bytes）應該驗證失敗")
        void testSignature_TooLong_ShouldFail() throws Exception {
            System.out.println("\n=== 簽章長度邊界：過長（257 bytes）===\n");

            byte[] longSignature = new byte[257];
            for (int i = 0; i < longSignature.length; i++) {
                longSignature[i] = (byte) i;
            }

            boolean result = verifySignature(
                    originalPublicKey, originalMessageData, Base64.getEncoder().encodeToString(longSignature));

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("簽章長度 - 257 bytes（過長）")
                    .boundaryType("邊界外")
                    .expectedResult("拒絕")
                    .actualResult(result ? "通過" : "拒絕")
                    .passed(!result)
                    .build();
            testResults.add(testResult);

            printSignatureTestResult(testResult, 257, 256);

            assertThat(result).as("過長簽章應該驗證失敗").isFalse();
        }
    }

    // ==================== 完整驗證流程邊界測試 ====================

    @Nested
    @DisplayName("完整驗證流程邊界測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class FullVerificationBoundaryTests {

        @Test
        @Order(1)
        @DisplayName("時間戳記正好在邊界上的完整驗證")
        void testFullVerification_TimestampAtBoundary() throws Exception {
            System.out.println("\n=== 完整驗證 - 時間戳記正好在 300 秒邊界 ===\n");

            long boundaryTimestamp = System.currentTimeMillis() - (300 * 1000L);

            boolean timestampPassed =
                    timestampValidator.validate(boundaryTimestamp).isSuccess();
            boolean noncePassed = nonceValidator
                    .validateAndRecord(NonceValidator.generateNonce())
                    .isSuccess();
            boolean publicKeyPassed = publicKeyRegistry
                    .getPublicKey(originalDeviceId)
                    .map(pk -> pk.equals(originalPublicKey))
                    .orElse(false);
            boolean signaturePassed = verifySignature(originalPublicKey, originalMessageData, originalSignature);

            boolean overallResult = timestampPassed && noncePassed && publicKeyPassed && signaturePassed;

            System.out.println("階段結果：");
            System.out.printf("  - 時間戳記驗證: %s%n", timestampPassed ? "通過" : "失敗");
            System.out.printf("  - Nonce 驗證: %s%n", noncePassed ? "通過" : "失敗");
            System.out.printf("  - 公鑰驗證: %s%n", publicKeyPassed ? "通過" : "失敗");
            System.out.printf("  - 簽章驗證: %s%n", signaturePassed ? "通過" : "失敗");
            System.out.printf("  - 整體結果: %s%n", overallResult ? "通過" : "失敗");

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("完整驗證 - 時間戳記邊界")
                    .boundaryType("邊界上")
                    .expectedResult("通過")
                    .actualResult(overallResult ? "通過" : "拒絕")
                    .passed(overallResult)
                    .build();
            testResults.add(testResult);

            assertThat(overallResult).as("時間戳記正好在邊界上的完整驗證應該通過").isTrue();
        }

        @Test
        @Order(2)
        @DisplayName("時間戳記剛超過邊界的完整驗證")
        void testFullVerification_TimestampJustPastBoundary() throws Exception {
            System.out.println("\n=== 完整驗證 - 時間戳記剛超過 300 秒邊界 ===\n");

            long pastBoundaryTimestamp = System.currentTimeMillis() - (301 * 1000L);

            MessageVerificationResult timestampResult = timestampValidator.validate(pastBoundaryTimestamp);

            System.out.println("時間戳記驗證結果: " + (timestampResult.isSuccess() ? "通過" : "失敗"));
            if (!timestampResult.isSuccess()) {
                System.out.println("錯誤訊息: " + timestampResult.getMessage());
            }

            BoundaryTestResult testResult = BoundaryTestResult.builder()
                    .testCase("完整驗證 - 時間戳記超過邊界")
                    .boundaryType("邊界外")
                    .expectedResult("拒絕")
                    .actualResult(timestampResult.isSuccess() ? "通過" : "拒絕")
                    .passed(!timestampResult.isSuccess())
                    .build();
            testResults.add(testResult);

            assertThat(timestampResult.isSuccess()).as("時間戳記剛超過邊界應該在第一階段被拒絕").isFalse();
        }
    }

    // ==================== 輔助方法 ====================

    private boolean verifySignature(String publicKeyBase64, String messageDataBase64, String signatureBase64) {
        try {
            PublicKey publicKey = extractPublicKey(publicKeyBase64);
            byte[] messageData = Base64.getDecoder().decode(messageDataBase64);
            byte[] signature = Base64.getDecoder().decode(signatureBase64);
            return TPMService.verifyWithJavaCrypto(publicKey, messageData, signature);
        } catch (Exception e) {
            System.out.println("簽章驗證例外: " + e.getMessage());
            return false;
        }
    }

    private PublicKey extractPublicKey(String base64PublicKey) throws Exception {
        byte[] publicKeyBytes = Base64.getDecoder().decode(base64PublicKey);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return keyFactory.generatePublic(keySpec);
    }

    private void printBoundaryTestResult(BoundaryTestResult testResult, MessageVerificationResult verificationResult) {
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.printf("│ 測試案例: %-56s │%n", testResult.getTestCase());
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ 邊界類型: %-56s │%n", testResult.getBoundaryType());
        System.out.printf("│ 預期結果: %-56s │%n", testResult.getExpectedResult());
        System.out.printf("│ 實際結果: %-56s │%n", testResult.getActualResult());
        if (!verificationResult.isSuccess() && verificationResult.getMessage() != null) {
            System.out.printf("│ 錯誤訊息: %-56s │%n", truncate(verificationResult.getMessage(), 56));
        }
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ 測試結果: %-56s │%n", testResult.isPassed() ? "通過 ✓" : "失敗 ✗");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private void printSignatureTestResult(BoundaryTestResult testResult, int actualLength, int expectedLength) {
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.printf("│ 測試案例: %-56s │%n", testResult.getTestCase());
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ 簽章長度: %d bytes（預期: %d bytes）%n", actualLength, expectedLength);
        System.out.printf("│ 差異: %+d bytes%n", actualLength - expectedLength);
        System.out.printf("│ 預期結果: %-56s │%n", testResult.getExpectedResult());
        System.out.printf("│ 實際結果: %-56s │%n", testResult.getActualResult());
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ 測試結果: %-56s │%n", testResult.isPassed() ? "通過 ✓" : "失敗 ✗");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private void printTestResultsSummary() {
        int passed =
                (int) testResults.stream().filter(BoundaryTestResult::isPassed).count();
        int total = testResults.size();

        System.out.println();
        System.out.println("========================================================================");
        System.out.println("                     邊界值測試結果摘要                                ");
        System.out.println("========================================================================");
        System.out.println();
        System.out.println("┌──────────────────────────────────┬────────────┬──────────┬──────────┐");
        System.out.println("│ 測試案例                         │ 邊界類型   │ 預期     │ 結果     │");
        System.out.println("├──────────────────────────────────┼────────────┼──────────┼──────────┤");

        for (BoundaryTestResult result : testResults) {
            System.out.printf(
                    "│ %-32s │ %-10s │ %-8s │ %-8s │%n",
                    truncate(result.getTestCase(), 32),
                    truncate(result.getBoundaryType(), 10),
                    result.getExpectedResult(),
                    result.isPassed() ? "通過" : "失敗");
        }

        System.out.println("└──────────────────────────────────┴────────────┴──────────┴──────────┘");
        System.out.println();
        System.out.printf("總計: %d/%d 測試通過 (%.1f%%)%n", passed, total, (passed * 100.0 / total));
        System.out.println();
        System.out.println("結論: 邊界值測試證明驗證邏輯在邊界條件下行為精確且一致。");
        System.out.println("========================================================================");
    }

    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "";
        }
        return str.length() <= maxLength ? str : str.substring(0, maxLength - 2) + "..";
    }

    // ==================== 資料類別 ====================

    @Getter
    @Builder
    static class BoundaryTestResult {
        private String testCase;
        private String boundaryType;
        private String expectedResult;
        private String actualResult;
        private boolean passed;
    }
}
