package org.gslab.fileProtection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Optional;
import lombok.Builder;
import lombok.Getter;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.util.HashUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * FileMetadata 完整性測試
 *
 * <p>此測試驗證 FileMetadata 的完整性驗證機制，模擬各種攻擊場景並確認系統能正確阻擋。
 *
 * <h2>四階段驗證流程</h2>
 * <ol>
 *   <li><b>階段 1：區塊鏈註冊驗證</b> - 確認 fileHash 已在區塊鏈上註冊</li>
 *   <li><b>階段 2：Metadata 快速比對</b> - 比對 fileName、fileSize、filePath</li>
 *   <li><b>階段 3：上傳者驗證</b> - 驗證 uploader 是否為已註冊的設備</li>
 *   <li><b>階段 4：檔案完整性驗證</b> - 重新計算 hash 並比對</li>
 * </ol>
 *
 * <h2>測試項目</h2>
 * <ul>
 *   <li>基礎攻擊測試（8 項）</li>
 *   <li>邊界值測試（6 項）</li>
 * </ul>
 *
 * @author FileMetadata Integrity Test
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FileMetadataIntegrityTest {

    @TempDir
    Path tempDir;

    private FileMetadataTestBlockchainSupport.FileBlockchainRegistry blockchainRegistry;
    private FileMetadataTestBlockchainSupport.DeviceRegistry deviceRegistry;

    // 測試檔案與 metadata
    private File validFile;
    private FileMetadata validMetadata;
    private static final String VALID_UPLOADER = "device-001";
    private static final String VALID_FILE_CONTENT = "This is a valid test file content for integrity testing.";

    // ==================== 輔助方法：建立 FileMetadata ====================

    /**
     * 建立 FileMetadata（使用建構子）
     */
    private FileMetadata buildFileMetadata(
            String id,
            String fileName,
            String fileHash,
            LocalDateTime uploadTime,
            String uploader,
            long fileSize,
            String filePath,
            String description) {
        return new FileMetadata(id, fileName, fileHash, uploadTime, uploader, fileSize, filePath, description);
    }

    @BeforeEach
    void setUp() throws Exception {
        blockchainRegistry = FileMetadataTestBlockchainSupport.createFileRegistry();
        deviceRegistry = FileMetadataTestBlockchainSupport.createDeviceRegistry();

        // 註冊有效的設備
        deviceRegistry.registerDevice(VALID_UPLOADER);

        // 建立測試檔案
        validFile = createTestFile("valid_test_file.txt", VALID_FILE_CONTENT);

        // 建立並註冊有效的 metadata
        validMetadata = createFileMetadata(validFile, VALID_UPLOADER, "Valid test file");
        blockchainRegistry.registerFile(validMetadata);

        printTestHeader();
    }

    @AfterEach
    void tearDown() {
        if (blockchainRegistry != null) {
            blockchainRegistry.clear();
            try {
                blockchainRegistry.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (deviceRegistry != null) {
            deviceRegistry.clear();
            try {
                deviceRegistry.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    // ==================== 基礎攻擊測試 ====================

    @Nested
    @DisplayName("基礎攻擊測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BasicAttackTests {

        @Test
        @Order(1)
        @DisplayName("1. 有效檔案（基準測試）- 應通過所有階段")
        void test_01_ValidFile_ShouldPassAllStages() throws Exception {
            IntegrityVerificationResult result = verifyFileIntegrity(validMetadata, validFile);

            printTestResult(1, "有效檔案", "通過", result);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getFailedStage()).isEqualTo(0);
            assertThat(result.getMessage()).contains("通過");
        }

        @Test
        @Order(2)
        @DisplayName("2. 未註冊檔案攻擊 - 應在階段 1 失敗")
        void test_02_UnregisteredFile_ShouldFailAtStage1() throws Exception {
            // 建立一個未註冊的檔案
            File unregisteredFile = createTestFile("unregistered_file.txt", "Unregistered content");
            FileMetadata unregisteredMetadata = createFileMetadata(unregisteredFile, VALID_UPLOADER, "Unregistered");

            // 不註冊到區塊鏈
            IntegrityVerificationResult result = verifyFileIntegrity(unregisteredMetadata, unregisteredFile);

            printTestResult(2, "未註冊檔案攻擊", "階段 1", result);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(1);
            assertThat(result.getMessage()).contains("未在區塊鏈上註冊");
        }

        @Test
        @Order(3)
        @DisplayName("3. 檔名竄改攻擊 - 應在階段 2 失敗")
        void test_03_FileNameTampering_ShouldFailAtStage2() throws Exception {
            // 使用有效的 fileHash 但修改 fileName
            FileMetadata tamperedMetadata = buildFileMetadata(
                    validMetadata.getId(),
                    "tampered_filename.txt", // 竄改檔名
                    validMetadata.getFileHash(),
                    validMetadata.getUploadTime(),
                    validMetadata.getUploader(),
                    validMetadata.getFileSize(),
                    validMetadata.getFilePath(),
                    validMetadata.getDescription());

            IntegrityVerificationResult result = verifyFileIntegrity(tamperedMetadata, validFile);

            printTestResult(3, "檔名竄改攻擊", "階段 2", result);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(2);
            assertThat(result.getMessage()).contains("fileName");
        }

        @Test
        @Order(4)
        @DisplayName("4. 檔案大小竄改攻擊 - 應在階段 2 失敗")
        void test_04_FileSizeTampering_ShouldFailAtStage2() throws Exception {
            // 使用有效的 fileHash 但修改 fileSize
            FileMetadata tamperedMetadata = buildFileMetadata(
                    validMetadata.getId(),
                    validMetadata.getFileName(),
                    validMetadata.getFileHash(),
                    validMetadata.getUploadTime(),
                    validMetadata.getUploader(),
                    99999L, // 竄改檔案大小
                    validMetadata.getFilePath(),
                    validMetadata.getDescription());

            IntegrityVerificationResult result = verifyFileIntegrity(tamperedMetadata, validFile);

            printTestResult(4, "檔案大小竄改攻擊", "階段 2", result);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(2);
            assertThat(result.getMessage()).contains("fileSize");
        }

        @Test
        @Order(5)
        @DisplayName("5. 檔案路徑竄改攻擊 - 應在階段 2 失敗")
        void test_05_FilePathTampering_ShouldFailAtStage2() throws Exception {
            // 使用有效的 fileHash 但修改 filePath
            FileMetadata tamperedMetadata = buildFileMetadata(
                    validMetadata.getId(),
                    validMetadata.getFileName(),
                    validMetadata.getFileHash(),
                    validMetadata.getUploadTime(),
                    validMetadata.getUploader(),
                    validMetadata.getFileSize(),
                    "/fake/path/to/file.txt", // 竄改路徑
                    validMetadata.getDescription());

            IntegrityVerificationResult result = verifyFileIntegrity(tamperedMetadata, validFile);

            printTestResult(5, "檔案路徑竄改攻擊", "階段 2", result);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(2);
            assertThat(result.getMessage()).contains("filePath");
        }

        @Test
        @Order(6)
        @DisplayName("6. 上傳者偽造攻擊 - 應在階段 3 失敗")
        void test_06_UploaderForging_ShouldFailAtStage3() throws Exception {
            // 使用有效的 fileHash 但修改 uploader 為未註冊的設備
            FileMetadata tamperedMetadata = buildFileMetadata(
                    validMetadata.getId(),
                    validMetadata.getFileName(),
                    validMetadata.getFileHash(),
                    validMetadata.getUploadTime(),
                    "fake-device-999", // 偽造上傳者
                    validMetadata.getFileSize(),
                    validMetadata.getFilePath(),
                    validMetadata.getDescription());

            IntegrityVerificationResult result = verifyFileIntegrity(tamperedMetadata, validFile);

            printTestResult(6, "上傳者偽造攻擊", "階段 3", result);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(3);
            assertThat(result.getMessage()).contains("uploader");
        }

        @Test
        @Order(7)
        @DisplayName("7. 檔案內容竄改攻擊 - 應在階段 4 失敗")
        void test_07_FileContentTampering_ShouldFailAtStage4() throws Exception {
            // 建立一個內容被竄改的檔案（但使用相同的 metadata）
            File tamperedFile =
                    createTestFile(validMetadata.getFileName(), "This content has been TAMPERED with malicious data!");

            IntegrityVerificationResult result = verifyFileIntegrity(validMetadata, tamperedFile);

            printTestResult(7, "檔案內容竄改攻擊", "階段 4", result);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(4);
            assertThat(result.getMessage()).contains("Hash");
        }

        @Test
        @Order(8)
        @DisplayName("8. 檔案替換攻擊 - 應在階段 4 失敗")
        void test_08_FileReplacement_ShouldFailAtStage4() throws Exception {
            // 建立一個完全不同的檔案來替換原檔案
            File replacementFile =
                    createTestFile(validMetadata.getFileName(), "COMPLETELY DIFFERENT FILE - This is a replacement!");

            IntegrityVerificationResult result = verifyFileIntegrity(validMetadata, replacementFile);

            printTestResult(8, "檔案替換攻擊", "階段 4", result);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(4);
            assertThat(result.getMessage()).contains("Hash");
        }
    }

    // ==================== 邊界值測試 ====================

    @Nested
    @DisplayName("邊界值測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BoundaryValueTests {

        @Test
        @Order(1)
        @DisplayName("1. 空檔名 - 應被拒絕")
        void test_01_EmptyFileName_ShouldBeRejected() throws Exception {
            FileMetadata invalidMetadata = buildFileMetadata(
                    HashUtil.generateUniqueId(),
                    "", // 空檔名
                    "abc123",
                    LocalDateTime.now(),
                    VALID_UPLOADER,
                    100L,
                    "/path/to/file",
                    null);

            ValidationResult result = validateMetadataFields(invalidMetadata);

            printBoundaryTestResult(1, "空檔名", "拒絕", result);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorField()).isEqualTo("fileName");
        }

        @Test
        @Order(2)
        @DisplayName("2. 空 Hash - 應被拒絕")
        void test_02_EmptyFileHash_ShouldBeRejected() throws Exception {
            FileMetadata invalidMetadata = buildFileMetadata(
                    HashUtil.generateUniqueId(),
                    "test.txt",
                    "", // 空 Hash
                    LocalDateTime.now(),
                    VALID_UPLOADER,
                    100L,
                    "/path/to/file",
                    null);

            ValidationResult result = validateMetadataFields(invalidMetadata);

            printBoundaryTestResult(2, "空 Hash", "拒絕", result);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorField()).isEqualTo("fileHash");
        }

        @Test
        @Order(3)
        @DisplayName("3. 空上傳者 - 應被拒絕")
        void test_03_EmptyUploader_ShouldBeRejected() throws Exception {
            FileMetadata invalidMetadata = buildFileMetadata(
                    HashUtil.generateUniqueId(),
                    "test.txt",
                    "abc123",
                    LocalDateTime.now(),
                    "", // 空上傳者
                    100L,
                    "/path/to/file",
                    null);

            ValidationResult result = validateMetadataFields(invalidMetadata);

            printBoundaryTestResult(3, "空上傳者", "拒絕", result);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorField()).isEqualTo("uploader");
        }

        @Test
        @Order(4)
        @DisplayName("4. 零位元組檔案 - 應通過")
        void test_04_ZeroByteFile_ShouldPass() throws Exception {
            // 建立空檔案
            File emptyFile = createTestFile("empty_file.txt", "");
            FileMetadata emptyMetadata = createFileMetadata(emptyFile, VALID_UPLOADER, "Empty file");
            blockchainRegistry.registerFile(emptyMetadata);

            IntegrityVerificationResult result = verifyFileIntegrity(emptyMetadata, emptyFile);

            printBoundaryTestResult(4, "零位元組檔案", "通過", result);

            assertThat(result.isSuccess()).isTrue();
            assertThat(emptyMetadata.getFileSize()).isEqualTo(0L);
        }

        @Test
        @Order(5)
        @DisplayName("5. 特殊字元檔名 - 應通過")
        void test_05_SpecialCharacterFileName_ShouldPass() throws Exception {
            // 建立含特殊字元的檔名
            String specialFileName = "測試文件 (v1.0) [final].txt";
            File specialFile = createTestFile(specialFileName, "Content with special filename");
            FileMetadata specialMetadata = createFileMetadata(specialFile, VALID_UPLOADER, "Special characters");
            blockchainRegistry.registerFile(specialMetadata);

            IntegrityVerificationResult result = verifyFileIntegrity(specialMetadata, specialFile);

            printBoundaryTestResult(5, "特殊字元檔名", "通過", result);

            assertThat(result.isSuccess()).isTrue();
            assertThat(specialMetadata.getFileName()).isEqualTo(specialFileName);
        }

        @Test
        @Order(6)
        @DisplayName("6. Hash 長度驗證 - 標準 64 字元 SHA-256 應通過")
        void test_06_ValidSHA256HashLength_ShouldPass() throws Exception {
            // 驗證產生的 hash 長度正確
            assertThat(validMetadata.getFileHash()).hasSize(64);

            // 確認是有效的十六進位字串
            assertThat(validMetadata.getFileHash()).matches("[0-9a-f]{64}");

            IntegrityVerificationResult result = verifyFileIntegrity(validMetadata, validFile);

            printBoundaryTestResult(6, "Hash 長度驗證 (64 字元)", "通過", result);

            assertThat(result.isSuccess()).isTrue();
        }
    }

    // ==================== 外部驗證測試 ====================

    /**
     * 外部驗證測試
     *
     * <p>支援從外部 JSON 檔案讀取 fileProtectionInfo 並驗證指定的檔案。
     *
     * <h3>使用方式</h3>
     * <pre>
     * mvn test -Dtest=FileMetadataIntegrityTest$ExternalVerificationTests \
     *   -Dexternal.json=/path/to/tpm_verification_sample.json \
     *   -Dexternal.file=/path/to/file_to_verify.txt
     * </pre>
     */
    @Nested
    @DisplayName("外部驗證測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ExternalVerificationTests {

        private static final String JSON_PATH_PROPERTY = "external.json";
        private static final String FILE_PATH_PROPERTY = "external.file";
        private static final String TRANSFER_INDEX_PROPERTY = "external.transfer.index";
        private static final String FAKE_FILE_PATH_PROPERTY = "external.fake.file";

        @Test
        @Order(1)
        @DisplayName("從外部 JSON 驗證檔案完整性")
        void testExternalFileVerification() throws Exception {
            String jsonPath = System.getProperty(JSON_PATH_PROPERTY);
            String filePath = System.getProperty(FILE_PATH_PROPERTY);

            if (jsonPath == null || jsonPath.isEmpty()) {
                System.out.println("========================================================================");
                System.out.println("  外部驗證測試 - 跳過（未指定 JSON 路徑）");
                System.out.println("========================================================================");
                System.out.println();
                System.out.println("使用方式:");
                System.out.println("  mvn test -Dtest=FileMetadataIntegrityTest\\$ExternalVerificationTests \\");
                System.out.printf("    -D%s=/path/to/json \\%n", JSON_PATH_PROPERTY);
                System.out.printf("    -D%s=/path/to/file%n", FILE_PATH_PROPERTY);
                System.out.println();
                return;
            }

            if (filePath == null || filePath.isEmpty()) {
                System.out.println("========================================================================");
                System.out.println("  外部驗證測試 - 錯誤：未指定檔案路徑");
                System.out.println("========================================================================");
                System.out.printf("請使用 -D%s=/path/to/file 指定要驗證的檔案%n", FILE_PATH_PROPERTY);
                return;
            }

            int transferIndex = Integer.parseInt(System.getProperty(TRANSFER_INDEX_PROPERTY, "0"));

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                    外部驗證測試                                        ");
            System.out.println("========================================================================");
            System.out.println();
            System.out.printf("JSON 路徑:      %s%n", jsonPath);
            System.out.printf("檔案路徑:       %s%n", filePath);
            System.out.printf("Transfer 索引: %d%n", transferIndex);
            System.out.println();

            // 讀取 JSON 並解析 fileMetadata
            FileMetadata metadataFromJson = loadFileMetadataFromJson(jsonPath, transferIndex);
            File fileToVerify = new File(filePath);

            if (!fileToVerify.exists()) {
                System.out.println("❌ 錯誤：指定的檔案不存在: " + filePath);
                assertThat(fileToVerify.exists()).isTrue();
                return;
            }

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                    從 JSON 讀取的 Metadata                          │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf("│ fileName:    %s%n", metadataFromJson.getFileName());
            System.out.printf("│ fileSize:    %d bytes%n", metadataFromJson.getFileSize());
            System.out.printf("│ fileHash:    %s%n", metadataFromJson.getFileHash());
            System.out.printf("│ uploader:    %s%n", metadataFromJson.getUploader());
            System.out.printf("│ filePath:    %s%n", metadataFromJson.getFilePath());
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            // 清除並重新註冊 metadata 到模擬區塊鏈
            blockchainRegistry.clear();
            deviceRegistry.clear();
            blockchainRegistry.registerFile(metadataFromJson);
            deviceRegistry.registerDevice(metadataFromJson.getUploader());

            // 執行四階段驗證（啟用 verbose 模式顯示詳細過程）
            IntegrityVerificationResult result = verifyFileIntegrity(metadataFromJson, fileToVerify, true);

            // 結果已在 verbose 模式中顯示，此處僅執行斷言
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @Order(2)
        @DisplayName("顯示 JSON 中的所有 transfers")
        void testListTransfersFromJson() throws Exception {
            String jsonPath = System.getProperty(JSON_PATH_PROPERTY);

            if (jsonPath == null || jsonPath.isEmpty()) {
                System.out.println("跳過：未指定 JSON 路徑");
                return;
            }

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("                    JSON 中的 Transfers 列表                           ");
            System.out.println("========================================================================");
            System.out.println();

            Path path = Path.of(jsonPath);
            String jsonContent = Files.readString(path, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(jsonContent);

            if (!root.has("fileProtectionInfo")) {
                System.out.println("❌ JSON 中沒有 fileProtectionInfo 欄位");
                return;
            }

            JSONObject fileProtectionInfo = root.getJSONObject("fileProtectionInfo");
            if (!fileProtectionInfo.has("transfers")) {
                System.out.println("❌ fileProtectionInfo 中沒有 transfers 欄位");
                return;
            }

            JSONArray transfers = fileProtectionInfo.getJSONArray("transfers");
            System.out.printf("共有 %d 個 transfer(s)%n%n", transfers.length());

            for (int i = 0; i < transfers.length(); i++) {
                JSONObject transfer = transfers.getJSONObject(i);
                JSONObject fileMetadata = transfer.getJSONObject("fileMetadata");

                System.out.printf("─── Transfer[%d] ───────────────────────────────────────────────────%n", i);
                System.out.printf("  fileName:  %s%n", fileMetadata.getString("fileName"));
                System.out.printf("  fileSize:  %d bytes%n", fileMetadata.getLong("fileSize"));
                System.out.printf("  fileHash:  %s%n", fileMetadata.getString("fileHash"));
                System.out.printf("  uploader:  %s%n", fileMetadata.getString("uploader"));
                System.out.printf("  filePath:  %s%n", fileMetadata.optString("filePath", "N/A"));
                System.out.println();
            }
        }

        // ==================== 外部攻擊測試 ====================

        @Test
        @Order(3)
        @DisplayName("攻擊測試：未註冊檔案 - 應在階段 1 被阻擋")
        void testExternalAttack_UnregisteredFile() throws Exception {
            if (!shouldRunAttackTests()) return;

            System.out.println("\n=== 攻擊測試：未註冊檔案攻擊 ===\n");

            FileMetadata originalMetadata = loadOriginalMetadata();
            File actualFile = getActualFile();

            // 建立攻擊場景：不註冊到區塊鏈
            FileMetadata attackMetadata = buildFileMetadata(
                    originalMetadata.getId(),
                    originalMetadata.getFileName(),
                    originalMetadata.getFileHash(),
                    originalMetadata.getUploadTime(),
                    originalMetadata.getUploader(),
                    originalMetadata.getFileSize(),
                    originalMetadata.getFilePath(),
                    originalMetadata.getDescription());

            printFileMetadataContent(originalMetadata, attackMetadata, "none", "查詢不存在於區塊鏈的 fileHash（不註冊到區塊鏈）");

            // 只註冊設備，不註冊 metadata
            deviceRegistry.registerDevice(originalMetadata.getUploader());
            // 不呼叫 blockchainRegistry.registerFile()

            IntegrityVerificationResult result = verifyFileIntegrity(attackMetadata, actualFile, true);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(1);
        }

        @Test
        @Order(4)
        @DisplayName("攻擊測試：檔名竄改 - 應在階段 2 被阻擋")
        void testExternalAttack_FileNameTampering() throws Exception {
            if (!shouldRunAttackTests()) return;

            System.out.println("\n=== 攻擊測試：檔名竄改攻擊 ===\n");

            FileMetadata originalMetadata = loadOriginalMetadata();
            File actualFile = getActualFile();

            // 註冊原始 metadata 到區塊鏈
            blockchainRegistry.registerFile(originalMetadata);
            deviceRegistry.registerDevice(originalMetadata.getUploader());

            // 建立攻擊場景：修改 fileName
            String tamperedFileName = "tampered_" + originalMetadata.getFileName();
            FileMetadata attackMetadata = buildFileMetadata(
                    originalMetadata.getId(),
                    tamperedFileName, // 竄改
                    originalMetadata.getFileHash(),
                    originalMetadata.getUploadTime(),
                    originalMetadata.getUploader(),
                    originalMetadata.getFileSize(),
                    originalMetadata.getFilePath(),
                    originalMetadata.getDescription());

            printFileMetadataContent(originalMetadata, attackMetadata, "fileName", "修改 fileName 欄位");

            IntegrityVerificationResult result = verifyFileIntegrity(attackMetadata, actualFile, true);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(2);
        }

        @Test
        @Order(5)
        @DisplayName("攻擊測試：檔案大小竄改 - 應在階段 2 被阻擋")
        void testExternalAttack_FileSizeTampering() throws Exception {
            if (!shouldRunAttackTests()) return;

            System.out.println("\n=== 攻擊測試：檔案大小竄改攻擊 ===\n");

            FileMetadata originalMetadata = loadOriginalMetadata();
            File actualFile = getActualFile();

            // 註冊原始 metadata 到區塊鏈
            blockchainRegistry.registerFile(originalMetadata);
            deviceRegistry.registerDevice(originalMetadata.getUploader());

            // 建立攻擊場景：修改 fileSize
            long tamperedFileSize = originalMetadata.getFileSize() + 999;
            FileMetadata attackMetadata = buildFileMetadata(
                    originalMetadata.getId(),
                    originalMetadata.getFileName(),
                    originalMetadata.getFileHash(),
                    originalMetadata.getUploadTime(),
                    originalMetadata.getUploader(),
                    tamperedFileSize, // 竄改
                    originalMetadata.getFilePath(),
                    originalMetadata.getDescription());

            printFileMetadataContent(originalMetadata, attackMetadata, "fileSize", "修改 fileSize 欄位");

            IntegrityVerificationResult result = verifyFileIntegrity(attackMetadata, actualFile, true);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(2);
        }

        @Test
        @Order(6)
        @DisplayName("攻擊測試：檔案路徑竄改 - 應在階段 2 被阻擋")
        void testExternalAttack_FilePathTampering() throws Exception {
            if (!shouldRunAttackTests()) return;

            System.out.println("\n=== 攻擊測試：檔案路徑竄改攻擊 ===\n");

            FileMetadata originalMetadata = loadOriginalMetadata();
            File actualFile = getActualFile();

            // 註冊原始 metadata 到區塊鏈
            blockchainRegistry.registerFile(originalMetadata);
            deviceRegistry.registerDevice(originalMetadata.getUploader());

            // 建立攻擊場景：修改 filePath
            String tamperedFilePath = "/fake/malicious/path/" + originalMetadata.getFileName();
            FileMetadata attackMetadata = buildFileMetadata(
                    originalMetadata.getId(),
                    originalMetadata.getFileName(),
                    originalMetadata.getFileHash(),
                    originalMetadata.getUploadTime(),
                    originalMetadata.getUploader(),
                    originalMetadata.getFileSize(),
                    tamperedFilePath, // 竄改
                    originalMetadata.getDescription());

            printFileMetadataContent(originalMetadata, attackMetadata, "filePath", "修改 filePath 欄位");

            IntegrityVerificationResult result = verifyFileIntegrity(attackMetadata, actualFile, true);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(2);
        }

        @Test
        @Order(7)
        @DisplayName("攻擊測試：上傳者偽造 - 應在階段 3 被阻擋")
        void testExternalAttack_UploaderForging() throws Exception {
            if (!shouldRunAttackTests()) return;

            System.out.println("\n=== 攻擊測試：上傳者偽造攻擊 ===\n");

            FileMetadata originalMetadata = loadOriginalMetadata();
            File actualFile = getActualFile();

            // 註冊原始 metadata 到區塊鏈（但不註冊偽造的設備）
            blockchainRegistry.registerFile(originalMetadata);
            deviceRegistry.registerDevice(originalMetadata.getUploader());

            // 建立攻擊場景：使用未註冊的 uploader
            String fakeUploader = "FAKE_DEVICE_" + System.currentTimeMillis();
            FileMetadata attackMetadata = buildFileMetadata(
                    originalMetadata.getId(),
                    originalMetadata.getFileName(),
                    originalMetadata.getFileHash(),
                    originalMetadata.getUploadTime(),
                    fakeUploader, // 偽造
                    originalMetadata.getFileSize(),
                    originalMetadata.getFilePath(),
                    originalMetadata.getDescription());

            printFileMetadataContent(originalMetadata, attackMetadata, "uploader", "使用未註冊的 uploader (deviceId)");

            IntegrityVerificationResult result = verifyFileIntegrity(attackMetadata, actualFile, true);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(3);
        }

        @Test
        @Order(8)
        @DisplayName("攻擊測試：檔案內容竄改 - 應在階段 4 被阻擋")
        void testExternalAttack_FileContentTampering() throws Exception {
            if (!shouldRunAttackTests()) return;

            System.out.println("\n=== 攻擊測試：檔案內容竄改攻擊 ===\n");

            FileMetadata originalMetadata = loadOriginalMetadata();
            File actualFile = getActualFile();

            // 註冊原始 metadata 到區塊鏈
            blockchainRegistry.registerFile(originalMetadata);
            deviceRegistry.registerDevice(originalMetadata.getUploader());

            // 1. 讀取原始檔案內容
            byte[] originalContent = Files.readAllBytes(actualFile.toPath());

            // 2. 竄改第一個位元組 (Flip bits)
            byte[] tamperedContent = originalContent.clone();
            if (tamperedContent.length > 0) {
                tamperedContent[0] = (byte) (tamperedContent[0] ^ 0xFF);
            } else {
                // 如果是空檔案，增加一個位元組
                tamperedContent = new byte[] {(byte) 0xFF};
            }

            // 3. 寫入到新檔案 (確保二進位安全)
            Path tamperedPath = tempDir.resolve("tampered_content_" + originalMetadata.getFileName());
            Files.write(tamperedPath, tamperedContent);
            File tamperedFile = tamperedPath.toFile();

            // 4. 準備顯示內容 (Base64)
            String originalBase64 = Base64.getEncoder().encodeToString(originalContent);
            String tamperedBase64 = Base64.getEncoder().encodeToString(tamperedContent);

            printFileMetadataContent(originalMetadata, originalMetadata, "fileContent", "修改檔案內容的第一個位元組");

            // 5. 顯示詳細修改資訊 (依照 TPMMultiStageVerificationTest 格式)
            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                         攻擊修改詳情                                │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.println("│ 攻擊方式: 修改檔案內容的第一個位元組");
            System.out.println("│ 修改欄位: 檔案內容 (file content)");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.println("│ 原始值: " + org.gslab.Tool.Util.truncateFirstAndLast(originalBase64, 24));
            System.out.println("│ 修改後: " + org.gslab.Tool.Util.truncateFirstAndLast(tamperedBase64, 24) + " [已修改]");
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            IntegrityVerificationResult result = verifyFileIntegrity(originalMetadata, tamperedFile, true);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(4);
        }

        @Test
        @Order(9)
        @DisplayName("攻擊測試：檔案替換 - 應在階段 4 被阻擋")
        void testExternalAttack_FileReplacement() throws Exception {
            if (!shouldRunAttackTests()) return;

            System.out.println("\n=== 攻擊測試：檔案替換攻擊 ===\n");

            FileMetadata originalMetadata = loadOriginalMetadata();

            // 註冊原始 metadata 到區塊鏈
            blockchainRegistry.registerFile(originalMetadata);
            deviceRegistry.registerDevice(originalMetadata.getUploader());

            // 取得替換檔案：優先使用外部提供的假檔案，否則使用內建產生的
            File replacementFile = getFakeFile("replacement_" + originalMetadata.getFileName());
            boolean isExternalFakeFile = isUsingExternalFakeFile();

            printFileMetadataContent(originalMetadata, originalMetadata, "fileReplacement", "用另一個完全不同的檔案替換原檔案");

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                         攻擊修改詳情                                │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.println("│ 攻擊類型: 檔案替換攻擊 (File Replacement)");
            System.out.printf("│ 假檔案來源: %s%n", isExternalFakeFile ? "外部提供" : "內建產生");
            if (isExternalFakeFile) {
                System.out.printf("│ 假檔案路徑: %s%n", replacementFile.getAbsolutePath());
            }
            System.out.printf("│ 原始檔名: %s%n", originalMetadata.getFileName());
            System.out.printf("│ 原始檔案大小: %d bytes%n", originalMetadata.getFileSize());
            System.out.printf("│ 替換檔案大小: %d bytes%n", replacementFile.length());
            System.out.println("│ 說明: 使用完全不同的檔案，hash 完全不同");
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            IntegrityVerificationResult result = verifyFileIntegrity(originalMetadata, replacementFile, true);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailedStage()).isEqualTo(4);
        }

        // ==================== 攻擊測試輔助方法 ====================

        /**
         * 檢查是否應該執行攻擊測試
         */
        private boolean shouldRunAttackTests() {
            String jsonPath = System.getProperty(JSON_PATH_PROPERTY);
            String filePath = System.getProperty(FILE_PATH_PROPERTY);

            if (jsonPath == null || jsonPath.isEmpty() || filePath == null || filePath.isEmpty()) {
                System.out.println("跳過攻擊測試：未指定 JSON 或檔案路徑");
                return false;
            }

            // 每次測試前清除註冊表
            blockchainRegistry.clear();
            deviceRegistry.clear();

            return true;
        }

        /**
         * 從系統屬性載入原始 metadata
         */
        private FileMetadata loadOriginalMetadata() throws IOException {
            String jsonPath = System.getProperty(JSON_PATH_PROPERTY);
            int transferIndex = Integer.parseInt(System.getProperty(TRANSFER_INDEX_PROPERTY, "0"));
            return loadFileMetadataFromJson(jsonPath, transferIndex);
        }

        /**
         * 取得實際檔案
         */
        private File getActualFile() {
            String filePath = System.getProperty(FILE_PATH_PROPERTY);
            return new File(filePath);
        }

        /**
         * 檢查是否使用外部假檔案
         */
        private boolean isUsingExternalFakeFile() {
            String fakeFilePath = System.getProperty(FAKE_FILE_PATH_PROPERTY);
            return fakeFilePath != null && !fakeFilePath.isEmpty() && new File(fakeFilePath).exists();
        }

        /**
         * 取得假檔案：優先使用外部提供的，否則內建產生
         *
         * @param fallbackFileName 如果沒有外部假檔案，用此名稱產生內建檔案
         * @return 假檔案
         */
        private File getFakeFile(String fallbackFileName) throws IOException {
            String fakeFilePath = System.getProperty(FAKE_FILE_PATH_PROPERTY);

            if (fakeFilePath != null && !fakeFilePath.isEmpty()) {
                File externalFakeFile = new File(fakeFilePath);
                if (externalFakeFile.exists()) {
                    return externalFakeFile;
                }
            }

            // 沒有外部假檔案，使用內建產生的
            return createTestFile(
                    fallbackFileName, "FAKE CONTENT - This is a malicious file! " + System.currentTimeMillis());
        }

        /**
         * 顯示 FileMetadata 內容和攻擊詳情
         */
        private void printFileMetadataContent(
                FileMetadata original, FileMetadata attack, String modifiedField, String attackDescription) {
            // 顯示原始內容
            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                   原始 FileMetadata 內容                            │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.println("│ 欄位            │ 值                                                │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf("│ fileName        │ %s%n", original.getFileName());
            System.out.printf("│ fileSize        │ %d bytes%n", original.getFileSize());
            System.out.printf("│ fileHash        │ %s%n", truncate(original.getFileHash(), 50));
            System.out.printf("│ uploader        │ %s%n", truncate(original.getUploader(), 50));
            System.out.printf("│ filePath        │ %s%n", truncate(original.getFilePath(), 50));
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");

            // 如果有修改欄位，顯示攻擊詳情
            if (!modifiedField.equals("none")
                    && !modifiedField.equals("fileContent")
                    && !modifiedField.equals("fileReplacement")) {
                System.out.println();
                System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
                System.out.println("│                         攻擊修改詳情                                │");
                System.out.println("├─────────────────────────────────────────────────────────────────────┤");
                System.out.println("│ 攻擊方式: " + attackDescription);
                System.out.println("│ 修改欄位: " + getFileMetadataFieldDisplayName(modifiedField));
                System.out.println("├─────────────────────────────────────────────────────────────────────┤");

                // 顯示修改前後的值
                switch (modifiedField) {
                    case "fileName":
                        System.out.println("│ 原始值: " + original.getFileName());
                        System.out.println("│ 修改後: " + attack.getFileName() + " [已竄改]");
                        break;
                    case "fileSize":
                        System.out.println("│ 原始值: " + original.getFileSize() + " bytes");
                        System.out.println("│ 修改後: " + attack.getFileSize() + " bytes [已竄改]");
                        break;
                    case "filePath":
                        System.out.println("│ 原始值: " + original.getFilePath());
                        System.out.println("│ 修改後: " + attack.getFilePath() + " [已竄改]");
                        break;
                    case "uploader":
                        System.out.println("│ 原始值: " + truncate(original.getUploader(), 40));
                        System.out.println("│ 修改後: " + truncate(attack.getUploader(), 40) + " [偽造]");
                        break;
                    case "fileHash":
                        System.out.println("│ 原始值: " + truncate(original.getFileHash(), 40));
                        System.out.println("│ 修改後: " + truncate(attack.getFileHash(), 40) + " [已竄改]");
                        break;
                }

                System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            }
            System.out.println();
        }

        /**
         * 取得欄位中文名稱
         */
        private String getFileMetadataFieldDisplayName(String fieldName) {
            switch (fieldName) {
                case "fileName":
                    return "檔案名稱 (fileName)";
                case "fileSize":
                    return "檔案大小 (fileSize)";
                case "fileHash":
                    return "檔案雜湊 (fileHash)";
                case "filePath":
                    return "檔案路徑 (filePath)";
                case "uploader":
                    return "上傳者 (uploader)";
                case "fileContent":
                    return "檔案內容 (file content)";
                case "fileReplacement":
                    return "檔案替換 (file replacement)";
                default:
                    return fieldName;
            }
        }
    }

    // ==================== JSON 解析輔助方法 ====================

    /**
     * 從 JSON 檔案讀取 FileMetadata
     *
     * @param jsonPath      JSON 檔案路徑
     * @param transferIndex transfers 陣列中的索引
     * @return FileMetadata 物件
     */
    private FileMetadata loadFileMetadataFromJson(String jsonPath, int transferIndex) throws IOException {
        Path path = Path.of(jsonPath);
        String jsonContent = Files.readString(path, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(jsonContent);

        JSONObject fileProtectionInfo = root.getJSONObject("fileProtectionInfo");
        JSONArray transfers = fileProtectionInfo.getJSONArray("transfers");
        JSONObject transfer = transfers.getJSONObject(transferIndex);
        JSONObject fileMetadataJson = transfer.getJSONObject("fileMetadata");

        String id = fileMetadataJson.optString("id", HashUtil.generateUniqueId());
        String fileName = fileMetadataJson.getString("fileName");
        String fileHash = fileMetadataJson.getString("fileHash");
        String uploader = fileMetadataJson.getString("uploader");
        long fileSize = fileMetadataJson.getLong("fileSize");
        String filePath = fileMetadataJson.optString("filePath", "");
        String description = fileMetadataJson.optString("description", "");

        // 解析 uploadTime
        LocalDateTime uploadTime;
        String uploadTimeStr = fileMetadataJson.optString("uploadTime", null);
        if (uploadTimeStr != null && !uploadTimeStr.isEmpty()) {
            uploadTime = LocalDateTime.parse(uploadTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } else {
            uploadTime = LocalDateTime.now();
        }

        return buildFileMetadata(id, fileName, fileHash, uploadTime, uploader, fileSize, filePath, description);
    }

    // ==================== 四階段驗證邏輯 ====================

    /**
     * 驗證檔案完整性（四階段驗證）- 簡潔版本
     */
    private IntegrityVerificationResult verifyFileIntegrity(FileMetadata submittedMetadata, File actualFile)
            throws IOException, NoSuchAlgorithmException {
        return verifyFileIntegrity(submittedMetadata, actualFile, false);
    }

    /**
     * 驗證檔案完整性（四階段驗證）
     *
     * @param submittedMetadata 提交的 metadata
     * @param actualFile        實際檔案
     * @param verbose           是否顯示詳細驗證過程
     * @return 驗證結果
     */
    private IntegrityVerificationResult verifyFileIntegrity(
            FileMetadata submittedMetadata, File actualFile, boolean verbose)
            throws IOException, NoSuchAlgorithmException {

        IntegrityVerificationResult.IntegrityVerificationResultBuilder resultBuilder =
                IntegrityVerificationResult.builder();

        if (verbose) {
            System.out.println();
            System.out.println("--- 開始四階段驗證流程 ---");
        }

        // 階段 1：區塊鏈註冊驗證
        if (verbose) {
            System.out.println();
            System.out.println("階段 1：區塊鏈註冊驗證");
            System.out.println("   提交的 fileHash: " + submittedMetadata.getFileHash());
        }

        Optional<FileMetadata> registeredMetadata = blockchainRegistry.getFile(submittedMetadata.getFileHash());

        if (registeredMetadata.isEmpty()) {
            if (verbose) {
                System.out.println("   查詢結果: 未找到註冊記錄");
                System.out.println("   結果: 失敗 - fileHash 未在區塊鏈上註冊");
                System.out.println("   攻擊在階段 1（區塊鏈註冊驗證）被阻擋");
                printVerboseStageSummary(resultBuilder
                        .stage1Status(StageStatus.FAILED)
                        .stage2Status(StageStatus.NOT_EXECUTED)
                        .stage3Status(StageStatus.NOT_EXECUTED)
                        .stage4Status(StageStatus.NOT_EXECUTED)
                        .build());
            }
            return resultBuilder
                    .success(false)
                    .failedStage(1)
                    .stage1Status(StageStatus.FAILED)
                    .stage2Status(StageStatus.NOT_EXECUTED)
                    .stage3Status(StageStatus.NOT_EXECUTED)
                    .stage4Status(StageStatus.NOT_EXECUTED)
                    .message("階段 1 失敗：fileHash 未在區塊鏈上註冊")
                    .build();
        }

        if (verbose) {
            System.out.println("   查詢結果: 找到註冊記錄");
            System.out.println("   結果: 通過");
        }
        resultBuilder.stage1Status(StageStatus.PASSED);

        FileMetadata registered = registeredMetadata.get();

        // 階段 2：Metadata 快速比對
        if (verbose) {
            System.out.println();
            System.out.println("階段 2：Metadata 快速比對");
            System.out.println(
                    "   ┌───────────────┬────────────────────────────────────────────────────────────────────────┬────────────────────────────────────────────────────────────────────────┐");
            System.out.println(
                    "   │ 欄位          │ 提交值                                                                 │ 註冊值                                                                 │");
            System.out.println(
                    "   ├───────────────┼────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────┤");
            System.out.printf(
                    "   │ fileName      │ %-70s │ %-70s │%n",
                    truncate(submittedMetadata.getFileName(), 70), truncate(registered.getFileName(), 70));
            System.out.printf(
                    "   │ fileSize      │ %-70s │ %-70s │%n",
                    submittedMetadata.getFileSize() + " bytes", registered.getFileSize() + " bytes");
            System.out.printf(
                    "   │ filePath      │ %-70s │ %-70s │%n",
                    truncate(submittedMetadata.getFilePath(), 70), truncate(registered.getFilePath(), 70));
            System.out.println(
                    "   └───────────────┴────────────────────────────────────────────────────────────────────────┴────────────────────────────────────────────────────────────────────────┘");
        }

        if (!submittedMetadata.getFileName().equals(registered.getFileName())) {
            if (verbose) {
                System.out.println("   比對結果: fileName 不符 ❌");
                System.out.println("   結果: 失敗 - fileName 不符");
                System.out.println("   攻擊在階段 2（Metadata 快速比對）被阻擋");
                printVerboseStageSummary(resultBuilder
                        .stage2Status(StageStatus.FAILED)
                        .stage3Status(StageStatus.NOT_EXECUTED)
                        .stage4Status(StageStatus.NOT_EXECUTED)
                        .build());
            }
            return resultBuilder
                    .success(false)
                    .failedStage(2)
                    .stage2Status(StageStatus.FAILED)
                    .stage3Status(StageStatus.NOT_EXECUTED)
                    .stage4Status(StageStatus.NOT_EXECUTED)
                    .message("階段 2 失敗：fileName 不符")
                    .build();
        }

        if (submittedMetadata.getFileSize() != registered.getFileSize()) {
            if (verbose) {
                System.out.println("   比對結果: fileSize 不符 ❌");
                System.out.println("   結果: 失敗 - fileSize 不符");
                System.out.println("   攻擊在階段 2（Metadata 快速比對）被阻擋");
                printVerboseStageSummary(resultBuilder
                        .stage2Status(StageStatus.FAILED)
                        .stage3Status(StageStatus.NOT_EXECUTED)
                        .stage4Status(StageStatus.NOT_EXECUTED)
                        .build());
            }
            return resultBuilder
                    .success(false)
                    .failedStage(2)
                    .stage2Status(StageStatus.FAILED)
                    .stage3Status(StageStatus.NOT_EXECUTED)
                    .stage4Status(StageStatus.NOT_EXECUTED)
                    .message("階段 2 失敗：fileSize 不符")
                    .build();
        }

        if (!submittedMetadata.getFilePath().equals(registered.getFilePath())) {
            if (verbose) {
                System.out.println("   比對結果: filePath 不符 ❌");
                System.out.println("   結果: 失敗 - filePath 不符");
                System.out.println("   攻擊在階段 2（Metadata 快速比對）被阻擋");
                printVerboseStageSummary(resultBuilder
                        .stage2Status(StageStatus.FAILED)
                        .stage3Status(StageStatus.NOT_EXECUTED)
                        .stage4Status(StageStatus.NOT_EXECUTED)
                        .build());
            }
            return resultBuilder
                    .success(false)
                    .failedStage(2)
                    .stage2Status(StageStatus.FAILED)
                    .stage3Status(StageStatus.NOT_EXECUTED)
                    .stage4Status(StageStatus.NOT_EXECUTED)
                    .message("階段 2 失敗：filePath 不符")
                    .build();
        }

        if (verbose) {
            System.out.println("   比對結果: 所有欄位一致 ✓");
            System.out.println("   結果: 通過");
        }
        resultBuilder.stage2Status(StageStatus.PASSED);

        // 階段 3：上傳者驗證
        if (verbose) {
            System.out.println();
            System.out.println("階段 3：上傳者驗證");
            System.out.println("   提交的 uploader: " + submittedMetadata.getUploader());
            System.out.println("   註冊的 uploader: " + registered.getUploader());
        }

        boolean uploaderRegistered = deviceRegistry.isDeviceRegistered(submittedMetadata.getUploader());
        if (verbose) {
            System.out.println("   設備註冊狀態: " + (uploaderRegistered ? "已註冊 ✓" : "未註冊 ❌"));
        }

        if (!uploaderRegistered) {
            if (verbose) {
                System.out.println("   結果: 失敗 - uploader 未註冊");
                System.out.println("   攻擊在階段 3（上傳者驗證）被阻擋");
                printVerboseStageSummary(resultBuilder
                        .stage3Status(StageStatus.FAILED)
                        .stage4Status(StageStatus.NOT_EXECUTED)
                        .build());
            }
            return resultBuilder
                    .success(false)
                    .failedStage(3)
                    .stage3Status(StageStatus.FAILED)
                    .stage4Status(StageStatus.NOT_EXECUTED)
                    .message("階段 3 失敗：uploader 未註冊")
                    .build();
        }

        if (!submittedMetadata.getUploader().equals(registered.getUploader())) {
            if (verbose) {
                System.out.println("   uploader 比對: 不符 ❌");
                System.out.println("   結果: 失敗 - uploader 與註冊記錄不符");
                System.out.println("   攻擊在階段 3（上傳者驗證）被阻擋");
                printVerboseStageSummary(resultBuilder
                        .stage3Status(StageStatus.FAILED)
                        .stage4Status(StageStatus.NOT_EXECUTED)
                        .build());
            }
            return resultBuilder
                    .success(false)
                    .failedStage(3)
                    .stage3Status(StageStatus.FAILED)
                    .stage4Status(StageStatus.NOT_EXECUTED)
                    .message("階段 3 失敗：uploader 與註冊記錄不符")
                    .build();
        }

        if (verbose) {
            System.out.println("   uploader 比對: 一致 ✓");
            System.out.println("   結果: 通過");
        }
        resultBuilder.stage3Status(StageStatus.PASSED);

        // 階段 4：檔案完整性驗證（重新計算 hash）
        if (verbose) {
            System.out.println();
            System.out.println("階段 4：檔案完整性驗證");
            System.out.println("   檔案路徑: " + actualFile.getAbsolutePath());
            System.out.println("   檔案大小: " + actualFile.length() + " bytes");
            System.out.println("   正在重新計算 hash...");
        }

        String recalculatedHash = calculateFileHash(actualFile, submittedMetadata);

        if (verbose) {
            System.out.println("   重新計算的 hash: " + recalculatedHash);
            System.out.println("   註冊的 hash:     " + registered.getFileHash());
            boolean hashMatch = recalculatedHash.equals(registered.getFileHash());
            System.out.println("   hash 比對: " + (hashMatch ? "一致 ✓" : "不一致 ❌"));
        }

        if (!recalculatedHash.equals(registered.getFileHash())) {
            if (verbose) {
                System.out.println("   結果: 失敗 - Hash 不符（檔案可能被竄改）");
                System.out.println("   攻擊在階段 4（檔案完整性驗證）被阻擋");
                printVerboseStageSummary(
                        resultBuilder.stage4Status(StageStatus.FAILED).build());
            }
            return resultBuilder
                    .success(false)
                    .failedStage(4)
                    .stage4Status(StageStatus.FAILED)
                    .message("階段 4 失敗：Hash 不符（檔案可能被竄改）")
                    .build();
        }

        if (verbose) {
            System.out.println("   結果: 通過");
            System.out.println();
            System.out.println("--- 所有驗證階段皆通過 ---");
            printVerboseStageSummary(
                    resultBuilder.stage4Status(StageStatus.PASSED).build());
        }

        return resultBuilder
                .success(true)
                .failedStage(0)
                .stage4Status(StageStatus.PASSED)
                .message("驗證通過：所有階段均成功")
                .build();
    }

    /**
     * 打印詳細的階段狀態摘要
     */
    private void printVerboseStageSummary(IntegrityVerificationResult result) {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│                         驗證結果摘要                                │");
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ 階段 1（區塊鏈註冊）:   " + formatStageStatus(result.getStage1Status()));
        System.out.println("│ 階段 2（Metadata 比對）: " + formatStageStatus(result.getStage2Status()));
        System.out.println("│ 階段 3（上傳者驗證）:   " + formatStageStatus(result.getStage3Status()));
        System.out.println("│ 階段 4（檔案完整性）:   " + formatStageStatus(result.getStage4Status()));
        System.out.println("├─────────────────────────────────────────────────────────────────────┤");

        // 判斷是否所有階段都通過
        boolean allPassed = result.getStage1Status() == StageStatus.PASSED
                && result.getStage2Status() == StageStatus.PASSED
                && result.getStage3Status() == StageStatus.PASSED
                && result.getStage4Status() == StageStatus.PASSED;

        if (allPassed) {
            System.out.println("│ 結果: 驗證成功，檔案完整性確認 ✅                                    │");
        } else {
            int failedStage = 0;
            String failedMessage = "";
            if (result.getStage1Status() == StageStatus.FAILED) {
                failedStage = 1;
                failedMessage = "區塊鏈註冊驗證失敗";
            } else if (result.getStage2Status() == StageStatus.FAILED) {
                failedStage = 2;
                failedMessage = "Metadata 比對失敗";
            } else if (result.getStage3Status() == StageStatus.FAILED) {
                failedStage = 3;
                failedMessage = "上傳者驗證失敗";
            } else if (result.getStage4Status() == StageStatus.FAILED) {
                failedStage = 4;
                failedMessage = "檔案完整性驗證失敗";
            }
            System.out.println("│ 阻擋於: 階段 " + failedStage);
            System.out.println("│ 錯誤訊息: " + failedMessage);
            System.out.println("│ 結果: 驗證失敗 ❌                                                    │");
        }
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private String formatStageStatus(StageStatus status) {
        if (status == null) {
            return "未執行 (前階段已失敗)";
        }
        switch (status) {
            case PASSED:
                return "通過 ✓";
            case FAILED:
                return "失敗 ❌ <--";
            case NOT_EXECUTED:
                return "未執行 (前階段已失敗)";
            default:
                return status.getDisplayName();
        }
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength - 3) + "...";
    }

    /**
     * 驗證 metadata 欄位（邊界值檢查）
     */
    private ValidationResult validateMetadataFields(FileMetadata metadata) {
        if (metadata.getFileName() == null || metadata.getFileName().isEmpty()) {
            return ValidationResult.builder()
                    .valid(false)
                    .errorField("fileName")
                    .message("fileName 不可為空")
                    .build();
        }

        if (metadata.getFileHash() == null || metadata.getFileHash().isEmpty()) {
            return ValidationResult.builder()
                    .valid(false)
                    .errorField("fileHash")
                    .message("fileHash 不可為空")
                    .build();
        }

        if (metadata.getUploader() == null || metadata.getUploader().isEmpty()) {
            return ValidationResult.builder()
                    .valid(false)
                    .errorField("uploader")
                    .message("uploader 不可為空")
                    .build();
        }

        return ValidationResult.builder()
                .valid(true)
                .errorField(null)
                .message("驗證通過")
                .build();
    }

    // ==================== 輔助方法 ====================

    private File createTestFile(String fileName, String content) throws IOException {
        Path filePath = tempDir.resolve(fileName);
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
        return filePath.toFile();
    }

    private FileMetadata createFileMetadata(File file, String uploader, String description)
            throws IOException, NoSuchAlgorithmException {
        String fileName = file.getName();
        long fileSize = file.length();
        String fileHash = HashUtil.calculateFileHash(
                file,
                fileName,
                Long.toString(fileSize),
                HashUtil.getHashAlgorithm(),
                Integer.toString(HashUtil.getBufferSize()));

        return buildFileMetadata(
                HashUtil.generateUniqueId(),
                fileName,
                fileHash,
                LocalDateTime.now(),
                uploader,
                fileSize,
                file.getAbsolutePath(),
                description);
    }

    private String calculateFileHash(File file, FileMetadata metadata) throws IOException, NoSuchAlgorithmException {
        return HashUtil.calculateFileHash(
                file,
                metadata.getFileName(),
                Long.toString(metadata.getFileSize()),
                HashUtil.getHashAlgorithm(),
                Integer.toString(HashUtil.getBufferSize()));
    }

    // ==================== 輸出格式化 ====================

    private void printTestHeader() {
        System.out.println();
        System.out.println("========================================================================");
        System.out.println("                  FileMetadata 完整性測試報告                          ");
        System.out.println("========================================================================");
        System.out.println();
    }

    private void printTestResult(
            int testNumber, String testName, String expectedStage, IntegrityVerificationResult result) {
        String actualStage = result.isSuccess() ? "通過" : "階段 " + result.getFailedStage();
        String status = expectedStage.equals(actualStage) ? "✅ PASS" : "❌ FAIL";

        System.out.printf(
                "│ %d │ %-18s │ %-8s │ %-8s │ %s%n", testNumber, testName, expectedStage, actualStage, status);
    }

    private void printBoundaryTestResult(int testNumber, String testName, String expected, Object result) {
        String actual;
        String status;

        if (result instanceof IntegrityVerificationResult verificationResult) {
            actual = verificationResult.isSuccess() ? "通過" : "失敗";
            status = expected.equals(actual) ? "✅ PASS" : "❌ FAIL";
        } else if (result instanceof ValidationResult validationResult) {
            actual = validationResult.isValid() ? "通過" : "拒絕";
            status = expected.equals(actual) ? "✅ PASS" : "❌ FAIL";
        } else {
            actual = "未知";
            status = "❓";
        }

        System.out.printf("│ %d │ %-20s │ %-6s │ %-6s │ %s%n", testNumber, testName, expected, actual, status);
    }

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

    // ==================== FileMetadata 效能測試 ====================

    @Nested
    @DisplayName("FileMetadata 效能測試")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PerformanceTests {

        // 系統屬性：是否執行效能測試
        private static final boolean RUN_PERFORMANCE_TESTS = Boolean.getBoolean("filemetadata.test.performance");

        private File perfTestFile;
        private FileMetadata perfTestMetadata;
        private static final int PERF_TEST_FILE_SIZE = 1024 * 10; // 10 KB 測試檔案

        @BeforeEach
        void setUpPerformanceTest() throws Exception {
            if (!RUN_PERFORMANCE_TESTS) {
                return;
            }

            // 建立較大的測試檔案
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < PERF_TEST_FILE_SIZE; i++) {
                content.append((char) ('A' + (i % 26)));
            }
            perfTestFile = createTestFile("perf_test_file.txt", content.toString());
            perfTestMetadata = createFileMetadata(perfTestFile, VALID_UPLOADER, "Performance test file");
            blockchainRegistry.registerFile(perfTestMetadata);
        }

        @Test
        @Order(1)
        @DisplayName("1000 次驗證延遲統計")
        void testOverallVerificationLatency() throws Exception {
            if (!shouldRunTest()) return;

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("              FileMetadata 整體驗證延遲測試                            ");
            System.out.println("========================================================================");
            printPerformanceTestTargetInfo();

            // 預熱
            for (int i = 0; i < 100; i++) {
                verifyFileIntegrity(perfTestMetadata, perfTestFile);
            }

            int iterations = 1000;
            long[] latencies = new long[iterations];

            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                verifyFileIntegrity(perfTestMetadata, perfTestFile);
                latencies[i] = System.nanoTime() - start;
            }

            PerformanceStats stats = calculatePerformanceStats(latencies);
            printPerformanceStatisticsTable(stats, iterations, "整體驗證延遲統計");

            // 確保平均延遲在合理範圍內（少於 50ms）
            assertThat(stats.getMeanMs()).isLessThan(50.0);
        }

        @Test
        @Order(2)
        @DisplayName("四階段驗證耗時分佈")
        void testStageLatencyBreakdown() throws Exception {
            if (!shouldRunTest()) return;

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("              FileMetadata 各階段耗時分析                              ");
            System.out.println("========================================================================");
            printPerformanceTestTargetInfo();

            int iterations = 1000;
            StageLatencies[] results = new StageLatencies[iterations];

            for (int i = 0; i < iterations; i++) {
                results[i] = measureStageLatencies(perfTestMetadata, perfTestFile);
            }

            printStageLatencyChart(results);

            // 驗證所有階段都有執行
            for (StageLatencies result : results) {
                assertThat(result.getStage1LatencyNs()).isPositive();
                assertThat(result.getStage2LatencyNs()).isPositive();
                assertThat(result.getStage3LatencyNs()).isPositive();
                assertThat(result.getStage4LatencyNs()).isPositive();
            }
        }

        @Test
        @Order(3)
        @DisplayName("每秒驗證請求數（吞吐量）")
        void testThroughput() throws Exception {
            if (!shouldRunTest()) return;

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("              FileMetadata 吞吐量測試                                  ");
            System.out.println("========================================================================");
            printPerformanceTestTargetInfo();

            int durationSeconds = 5;
            int completedVerifications = 0;

            long startTime = System.currentTimeMillis();
            long endTime = startTime + (durationSeconds * 1000L);

            while (System.currentTimeMillis() < endTime) {
                verifyFileIntegrity(perfTestMetadata, perfTestFile);
                completedVerifications++;
            }

            long actualDuration = System.currentTimeMillis() - startTime;
            double throughput = completedVerifications / (actualDuration / 1000.0);

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                   FileMetadata 吞吐量結果                           │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf("│ 測試時長:           %,d 毫秒%n", actualDuration);
            System.out.printf("│ 完成驗證數:         %,d 次%n", completedVerifications);
            System.out.printf("│ 吞吐量:             %.2f 驗證/秒%n", throughput);
            System.out.printf("│ 平均延遲:           %.3f 毫秒/驗證%n", actualDuration / (double) completedVerifications);
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            // 確保吞吐量大於 100 次/秒
            assertThat(throughput).isGreaterThan(100.0);
        }

        @Test
        @Order(4)
        @DisplayName("不同檔案大小的驗證效能比較")
        void testDifferentFileSizes() throws Exception {
            if (!shouldRunTest()) return;

            System.out.println();
            System.out.println("========================================================================");
            System.out.println("              不同檔案大小的驗證效能比較                               ");
            System.out.println("========================================================================");
            System.out.println();

            // 100 KB, 1 MB, 10 MB, 100 MB, 1 GB
            long[] fileSizes = {
                100L * 1024, // 100 KB
                1L * 1024 * 1024, // 1 MB
                10L * 1024 * 1024, // 10 MB
                100L * 1024 * 1024, // 100 MB
                1024L * 1024 * 1024 // 1 GB
            };
            String[] sizeLabels = {"100 KB", "1 MB", "10 MB", "100 MB", "1 GB"};

            System.out.println(
                    "┌───────────────────────────────────────────────────────────────────────────────────────────────────────────┐");
            System.out.println(
                    "│                                   檔案大小 vs 驗證延遲                                                   │");
            System.out.println(
                    "├───────────────────────────────────────────────────────────────────────────────────────────────────────────┤");
            System.out.println("│ 檔案大小   │ 迭代 │ Hash(ms)  │ 階段1-3(ms) │ 合計平均(ms) │ 標準差(ms) │ P95(ms)    │ 區塊鏈(ms) │");
            System.out.println(
                    "├───────────────────────────────────────────────────────────────────────────────────────────────────────────┤");

            for (int idx = 0; idx < fileSizes.length; idx++) {
                long fileSize = fileSizes[idx];
                String sizeLabel = sizeLabels[idx];

                // 調整迭代次數：最少 10 次以確保統計有意義
                // P95 理想上需要 20+ 樣本，但考慮大檔案時間成本，折衷使用 10 次
                int iterationsPerSize;
                if (fileSize >= 100L * 1024 * 1024) { // 100MB+
                    iterationsPerSize = 10; // 增加到 10 次（原本 3 次太少）
                } else if (fileSize >= 10L * 1024 * 1024) { // 10MB+
                    iterationsPerSize = 15;
                } else {
                    iterationsPerSize = 30;
                }

                System.out.printf("│ 正在測試 %-6s (%d 次)...%n", sizeLabel, iterationsPerSize);

                // 建立指定大小的測試檔案
                File testFile = createLargeTestFile("size_test_" + sizeLabel.replace(" ", "") + ".bin", fileSize);
                FileMetadata testMetadata = createFileMetadata(testFile, VALID_UPLOADER, "Size test " + sizeLabel);
                blockchainRegistry.registerFile(testMetadata);

                long[] verifyLatencies = new long[iterationsPerSize];
                long[] hashOnlyLatencies = new long[iterationsPerSize];
                long[] blockchainLatencies = new long[iterationsPerSize];

                for (int i = 0; i < iterationsPerSize; i++) {
                    // 1. 測量區塊鏈查詢時間
                    long blockchainStart = System.nanoTime();
                    blockchainRegistry.getFile(testMetadata.getFileHash());
                    blockchainLatencies[i] = System.nanoTime() - blockchainStart;

                    // 2. 測量單純 Hash 計算時間（參考）
                    long hashStart = System.nanoTime();
                    calculateFileHash(testFile, testMetadata);
                    hashOnlyLatencies[i] = System.nanoTime() - hashStart;

                    // 3. 測量完整四階段驗證時間
                    long verifyStart = System.nanoTime();
                    verifyFileIntegrity(testMetadata, testFile);
                    verifyLatencies[i] = System.nanoTime() - verifyStart;
                }

                PerformanceStats hashOnlyStats = calculatePerformanceStats(hashOnlyLatencies);
                PerformanceStats verifyStats = calculatePerformanceStats(verifyLatencies);
                PerformanceStats blockchainStats = calculatePerformanceStats(blockchainLatencies);

                // 階段 1-3 時間 ≈ 驗證時間 - Hash 時間
                double otherStagesMs = Math.max(0, verifyStats.getMeanMs() - hashOnlyStats.getMeanMs());

                System.out.printf(
                        "│ %-10s │ %4d │ %9.2f │ %11.3f │ %12.2f │ %10.2f │ %10.2f │ %10.3f │%n",
                        sizeLabel,
                        iterationsPerSize,
                        hashOnlyStats.getMeanMs(),
                        otherStagesMs,
                        verifyStats.getMeanMs(),
                        verifyStats.getStdDevMs(),
                        verifyStats.getP95Ms(),
                        blockchainStats.getMeanMs());

                // 刪除大型測試檔案以釋放空間
                testFile.delete();
            }

            System.out.println(
                    "└───────────────────────────────────────────────────────────────────────────────────────────────────────────┘");
            System.out.println();
            System.out.println("說明：");
            System.out.println("  - Hash:       單純 SHA-256 Hash 計算時間（參考值）");
            System.out.println("  - 階段1-3:    區塊鏈查詢 + Metadata比對 + 上傳者驗證");
            System.out.println("  - 合計平均:   四階段驗證平均時間（已含階段4 Hash）");
            System.out.println("  - 標準差:     驗證時間的標準差（越小越穩定）");
            System.out.println("  - P95:        95%的請求在此時間內完成");
            System.out.println("  - 區塊鏈:     區塊鏈查詢延遲（Mock ~0ms / 真實 ~400ms）");
            System.out.println();
        }

        /**
         * 建立大型測試檔案（高效方式，使用 byte 緩衝區）
         */
        private File createLargeTestFile(String fileName, long size) throws IOException {
            Path filePath = tempDir.resolve(fileName);
            byte[] buffer = new byte[8192]; // 8KB buffer
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = (byte) ('A' + (i % 26));
            }

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(filePath.toFile())) {
                long remaining = size;
                while (remaining > 0) {
                    int writeSize = (int) Math.min(buffer.length, remaining);
                    fos.write(buffer, 0, writeSize);
                    remaining -= writeSize;
                }
            }
            return filePath.toFile();
        }

        // ==================== 輔助方法 ====================

        private boolean shouldRunTest() {
            if (!RUN_PERFORMANCE_TESTS) {
                System.out.println("========================================================================");
                System.out.println("  FileMetadata 效能測試 - 跳過（未啟用）");
                System.out.println("========================================================================");
                System.out.println();
                System.out.println("使用方式:");
                System.out.println("  mvn test -Dtest=FileMetadataIntegrityTest\\$PerformanceTests \\");
                System.out.println("    -Dfilemetadata.test.performance=true");
                System.out.println();
                return false;
            }
            return true;
        }

        private void printPerformanceTestTargetInfo() {
            System.out.println();
            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                         測試目標資訊                                │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf("│ 檔案名稱:           %s%n", perfTestMetadata.getFileName());
            System.out.printf("│ 檔案大小:           %,d bytes%n", perfTestMetadata.getFileSize());
            System.out.printf("│ 檔案路徑:           %s%n", perfTestMetadata.getFilePath());
            System.out.printf("│ 上傳者:             %s%n", perfTestMetadata.getUploader());
            System.out.printf(
                    "│ 檔案 Hash:          %s...%n",
                    perfTestMetadata
                            .getFileHash()
                            .substring(
                                    0,
                                    Math.min(32, perfTestMetadata.getFileHash().length())));
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();
        }

        private StageLatencies measureStageLatencies(FileMetadata metadata, File file) throws Exception {
            // 階段 1：區塊鏈註冊驗證
            long stage1Start = System.nanoTime();
            Optional<FileMetadata> registered = blockchainRegistry.getFile(metadata.getFileHash());
            long stage1End = System.nanoTime();

            // 階段 2：Metadata 快速比對
            long stage2Start = System.nanoTime();
            if (registered.isPresent()) {
                FileMetadata reg = registered.get();
                boolean match = metadata.getFileName().equals(reg.getFileName())
                        && metadata.getFileSize() == reg.getFileSize()
                        && metadata.getFilePath().equals(reg.getFilePath());
            }
            long stage2End = System.nanoTime();

            // 階段 3：上傳者驗證
            long stage3Start = System.nanoTime();
            boolean uploaderValid = deviceRegistry.isDeviceRegistered(metadata.getUploader());
            long stage3End = System.nanoTime();

            // 階段 4：檔案完整性驗證（Hash 計算）
            long stage4Start = System.nanoTime();
            String calculatedHash = HashUtil.calculateFileHash(file);
            boolean hashMatch = calculatedHash.equals(metadata.getFileHash());
            long stage4End = System.nanoTime();

            return StageLatencies.builder()
                    .stage1LatencyNs(stage1End - stage1Start)
                    .stage2LatencyNs(stage2End - stage2Start)
                    .stage3LatencyNs(stage3End - stage3Start)
                    .stage4LatencyNs(stage4End - stage4Start)
                    .build();
        }

        private PerformanceStats calculatePerformanceStats(long[] latencies) {
            long[] sorted = latencies.clone();
            java.util.Arrays.sort(sorted);

            double sum = 0;
            for (long latency : latencies) {
                sum += latency;
            }
            double mean = sum / latencies.length;

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

        private void printPerformanceStatisticsTable(PerformanceStats stats, int samples, String title) {
            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.printf("│                      %s                         │%n", title);
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
            double avgStage1 = 0, avgStage2 = 0, avgStage3 = 0, avgStage4 = 0;
            for (StageLatencies result : results) {
                avgStage1 += result.getStage1LatencyNs();
                avgStage2 += result.getStage2LatencyNs();
                avgStage3 += result.getStage3LatencyNs();
                avgStage4 += result.getStage4LatencyNs();
            }
            avgStage1 /= results.length;
            avgStage2 /= results.length;
            avgStage3 /= results.length;
            avgStage4 /= results.length;

            double total = avgStage1 + avgStage2 + avgStage3 + avgStage4;

            // 計算百分比
            double pctStage1 = (avgStage1 / total) * 100;
            double pctStage2 = (avgStage2 / total) * 100;
            double pctStage3 = (avgStage3 / total) * 100;
            double pctStage4 = (avgStage4 / total) * 100;

            System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
            System.out.println("│                      各階段耗時分析                                 │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.println("│ 階段                │ 平均耗時      │ 佔比    │ 視覺化             │");
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf(
                    "│ 1. 區塊鏈註冊驗證   │ %8.3f ms   │ %5.1f%%  │ %s%n",
                    avgStage1 / 1_000_000.0, pctStage1, generateBar(pctStage1));
            System.out.printf(
                    "│ 2. Metadata 比對    │ %8.3f ms   │ %5.1f%%  │ %s%n",
                    avgStage2 / 1_000_000.0, pctStage2, generateBar(pctStage2));
            System.out.printf(
                    "│ 3. 上傳者驗證       │ %8.3f ms   │ %5.1f%%  │ %s%n",
                    avgStage3 / 1_000_000.0, pctStage3, generateBar(pctStage3));
            System.out.printf(
                    "│ 4. 檔案 Hash 驗證   │ %8.3f ms   │ %5.1f%%  │ %s%n",
                    avgStage4 / 1_000_000.0, pctStage4, generateBar(pctStage4));
            System.out.println("├─────────────────────────────────────────────────────────────────────┤");
            System.out.printf(
                    "│ 總計                │ %8.3f ms   │ 100.0%%  │                    │%n", total / 1_000_000.0);
            System.out.println("└─────────────────────────────────────────────────────────────────────┘");
            System.out.println();
        }

        private String generateBar(double percentage) {
            int barLength = (int) (percentage / 5); // 每 5% 一個區塊
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < barLength; i++) {
                bar.append("█");
            }
            while (bar.length() < 20) {
                bar.append(" ");
            }
            return bar.toString();
        }

        @Getter
        @Builder
        static class StageLatencies {
            private long stage1LatencyNs;
            private long stage2LatencyNs;
            private long stage3LatencyNs;
            private long stage4LatencyNs;

            public long getTotalLatencyNs() {
                return stage1LatencyNs + stage2LatencyNs + stage3LatencyNs + stage4LatencyNs;
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
    }

    @Getter
    @Builder
    static class IntegrityVerificationResult {
        private boolean success;
        private int failedStage;
        private String message;

        @Builder.Default
        private StageStatus stage1Status = StageStatus.NOT_EXECUTED;

        @Builder.Default
        private StageStatus stage2Status = StageStatus.NOT_EXECUTED;

        @Builder.Default
        private StageStatus stage3Status = StageStatus.NOT_EXECUTED;

        @Builder.Default
        private StageStatus stage4Status = StageStatus.NOT_EXECUTED;
    }

    @Getter
    @Builder
    static class ValidationResult {
        private boolean valid;
        private String errorField;
        private String message;
    }
}
