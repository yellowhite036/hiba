package org.gslab.tpm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.gslab.Tool.Util;
import org.gslab.tpm.exception.TPMConnectionException;
import tss.*;
import tss.tpm.*;

/**
 * TPM (Trusted Platform Module) 服務類別 - 封裝所有 TPM 2.0 相關操作。
 *
 * <p>
 * 此類別提供與 TPM 硬體或模擬器互動的完整功能，包括：
 * </p>
 * <ul>
 * <li><b>連接管理</b>：自動偵測並連接到實體 TPM 或 TPM 模擬器</li>
 * <li><b>金鑰管理</b>：在 TPM 內建立和管理簽署金鑰（私鑰永遠不離開 TPM）</li>
 * <li><b>數位簽章</b>：使用 TPM 內的金鑰進行 RSASSA-PKCS1-v1.5-SHA256 簽章</li>
 * <li><b>簽章驗證</b>：支援 TPM 硬體驗證和 Java Crypto API 驗證（適用於遠端驗證）</li>
 * <li><b>Endorsement Key (EK)</b>：讀取 TPM 出廠時內建的唯一識別金鑰</li>
 * </ul>
 *
 * <h2>安全特性</h2>
 * <ul>
 * <li>私鑰永遠儲存在 TPM 硬體內，無法被軟體提取</li>
 * <li>簽章操作在 TPM 硬體內完成，防止中間人攻擊</li>
 * <li>EK 提供設備唯一識別，支援遠端認證 (Remote Attestation)</li>
 * </ul>
 *
 * <h2>使用範例</h2>
 *
 * <pre>{@code
 * // 使用 try-with-resources 確保資源正確釋放
 * try (TPMService tpmService = new TPMService()) {
 *     // 建立簽署金鑰
 *     tpmService.createSigningKey();
 *
 *     // 簽署資料
 *     byte[] data = "Hello TPM!".getBytes();
 *     byte[] signature = tpmService.sign(data);
 *
 *     // 驗證簽章
 *     boolean isValid = tpmService.verify(data, signature);
 *
 *     // 取得公鑰供遠端驗證使用
 *     PublicKey publicKey = tpmService.getSigningPublicKey();
 * }
 * }</pre>
 *
 * <h2>TPM 連接優先順序</h2>
 * <ol>
 * <li>優先嘗試連接實體 TPM 裝置（透過 /dev/tpm0 或 Windows TBS）</li>
 * <li>若無實體 TPM，則嘗試連接 TPM 模擬器（localhost:2321）</li>
 * </ol>
 *
 * <h2>執行緒安全性</h2>
 * <p>
 * 此類別<b>非執行緒安全</b>。若需在多執行緒環境中使用，請為每個執行緒建立獨立的實例，
 * 或使用適當的同步機制。
 * </p>
 *
 * @author TPM Test Project
 * @version 1.0
 * @since 2024
 * @see TPMSignAndVerify 簽章與驗證示範程式
 * @see TPMEndorsementKey EK 讀取示範程式
 * @see <a href=
 *      "https://trustedcomputinggroup.org/resource/tpm-library-specification/">TCG
 *      TPM 2.0 規範</a>
 */
@Slf4j
public class TPMService implements AutoCloseable {

    /**
     * RSA 金鑰演算法名稱。
     * <p>
     * 用於 {@link KeyFactory#getInstance(String)} 建立 RSA 金鑰工廠。
     * </p>
     *
     * @see KeyFactory
     */
    public static final String KEY_ALGORITHM = "RSA";

    /**
     * 標準 RSA 簽章演算法（SHA256withRSA）。
     * <p>
     * 此演算法會先對資料進行 SHA-256 雜湊，再使用 RSA 私鑰簽章。
     * 適用於一般的簽章驗證場景。
     * </p>
     *
     * @see Signature#getInstance(String)
     */
    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /**
     * 原始 RSA 簽章演算法（NONEwithRSA）。
     * <p>
     * 此演算法不進行雜湊處理，直接對輸入資料進行 RSA 簽章。
     * 適用於已經過雜湊處理的資料，如 TPM 簽章驗證場景。
     * </p>
     * <p>
     * <b>注意：</b>使用此演算法時，需手動加入 PKCS#1 v1.5 DigestInfo 前綴。
     * </p>
     *
     * @see #verifyDigestWithJavaCrypto(PublicKey, byte[], byte[])
     */
    public static final String SIGNATURE_ALGORITHM_RAW = "NONEwithRSA";

    /**
     * RSA EK Certificate 在 TPM NV 儲存區的標準索引位址。
     * <p>
     * 根據 TCG EK Credential Profile 規範（TPM 2.0），
     * RSA 2048 EK 憑證儲存於此 NV 索引。
     * </p>
     * <p>
     * 十六進位值：{@code 0x01C00002}
     * </p>
     *
     * @see #readEKCertificate(int)
     * @see <a href=
     *      "https://trustedcomputinggroup.org/resource/tcg-ek-credential-profile/">TCG
     *      EK Credential Profile</a>
     */
    public static final int NV_INDEX_RSA_EK_CERT = 0x01C00002;

    /**
     * ECC EK Certificate 在 TPM NV 儲存區的標準索引位址。
     * <p>
     * 根據 TCG EK Credential Profile 規範（TPM 2.0），
     * ECC P-256 EK 憑證儲存於此 NV 索引。
     * </p>
     * <p>
     * 十六進位值：{@code 0x01C0000A}
     * </p>
     *
     * @see #readEKCertificate(int)
     */
    public static final int NV_INDEX_ECC_EK_CERT = 0x01C0000A;

    /**
     * 預設的持久化簽章金鑰 Handle。
     * <p>
     * 此 Handle 對應透過 tpm2-tools 持久化的簽章金鑰。
     * 使用 {@code tpm_init_key.sh} 腳本建立的金鑰會儲存於此 Handle。
     * </p>
     * <p>
     * 十六進位值：{@code 0x81000001}
     * </p>
     *
     * @see #loadPersistentSigningKey(int)
     */
    public static final int DEFAULT_PERSISTENT_HANDLE = 0x81000001;

    /**
     * TCG 規範的標準 EK Policy Digest（SHA-256）。
     * <p>
     * 此 Policy Digest 用於建立符合 TCG 規範的 Endorsement Key。
     * 它是固定的 32 位元組值，定義於 TCG EK Credential Profile。
     * </p>
     * <p>
     * 此 Policy 確保 EK 只能透過 TPM 內部的 Policy Session 使用，
     * 提供額外的安全保護層。
     * </p>
     */
    private static final byte[] EK_POLICY_DIGEST =
            Util.hexStringToByteArray("837197674484b3f81a90cc8d46a5d724fd52d76e06520b64f2a1da1b331469aa");

    @Getter
    private String tpmVersion = "2.0";
    /**
     * TPM 實例，用於與 TPM 硬體或模擬器通訊。
     * <p>
     * 透過 {@link TpmFactory} 建立，可能是實體 TPM 或模擬器連接。
     * </p>
     */
    @Getter
    private Tpm tpm;

    /**
     * Primary Key 的 TPM Handle。
     * <p>
     * Primary Key 是在 Owner Hierarchy 下建立的根金鑰，
     * 用作 Signing Key 的父金鑰。
     * </p>
     */
    private TPM_HANDLE primaryHandle;

    /**
     * Signing Key 的 TPM Handle。
     * <p>
     * 此金鑰用於執行數位簽章操作，是 Primary Key 的子金鑰。
     * </p>
     */
    private TPM_HANDLE signingKeyHandle;

    /**
     * Signing Key 的公鑰區域（TPMT_PUBLIC）。
     * <p>
     * 包含簽署金鑰的公開部分，可用於驗證簽章或匯出給遠端使用。
     * </p>
     *
     * @see #getSigningPublicKey()
     * @see #convertTPMPublicKeyToJava(TPMT_PUBLIC)
     */
    @Getter
    private TPMT_PUBLIC signingKeyPublicArea;

    /**
     * TPM 連接狀態標記。
     * <p>
     * {@code true} 表示已成功連接到 TPM，{@code false} 表示未連接或已斷開。
     * </p>
     */
    private boolean connected = false;

    /**
     * 模擬器使用標記。
     * <p>
     * {@code true} 表示目前連接到 TPM 模擬器（僅供測試），
     * {@code false} 表示連接到實體 TPM 硬體。
     * </p>
     */
    private boolean usingSimulator = false;

    /**
     * 持久化金鑰使用標記。
     * <p>
     * {@code true} 表示使用 TPM 持久化金鑰（透過 tpm2-tools 預先建立），
     * {@code false} 表示使用動態建立的金鑰。
     * </p>
     */
    private boolean usingPersistentKey = false;

    /**
     * EK Fingerprint 快取。
     * <p>
     * 快取 EK 指紋以避免重複讀取 EK，減少 TPM 物件記憶體使用。
     * </p>
     */
    private String cachedEKFingerprint = null;

    // TPM 設備可能在連續操作時需要較長恢復時間，加入重試機制
    private final int maxRetries = 5;

    private final long baseDelayMs = 100L;

    /**
     * 建構 TPMService 實例並自動連接到 TPM。
     *
     * <p>
     * 建構時會自動嘗試連接到 TPM 裝置：
     * </p>
     * <ol>
     * <li>連接實體 TPM（透過作業系統的 TPM 驅動程式）</li>
     * </ol>
     *
     * <p>
     * <b>使用建議：</b>建議使用 try-with-resources 語法以確保資源正確釋放：
     * </p>
     *
     * <pre>{@code
     * try (TPMService tpm = new TPMService()) {
     *     // 使用 TPM 服務
     * }
     * }</pre>
     *
     * @throws TPMConnectionException 當無法連接到實體 TPM 拋出
     * @see #connect()
     * @see #close()
     */
    public TPMService() throws TPMConnectionException {
        connect();
        loadPersistentSigningKey(DEFAULT_PERSISTENT_HANDLE);
    }

    /**
     * 建構 TPMService 實例，可選擇強制使用模擬器。
     *
     * <p>
     * 此建構子允許明確指定連接目標：
     * </p>
     * <ul>
     * <li>{@code useSimulator = true}：直接連接 TPM 模擬器，不嘗試實體 TPM</li>
     * <li>{@code useSimulator = false}：與預設建構子相同，優先嘗試實體 TPM</li>
     * </ul>
     *
     * <p>
     * <b>安全警告：</b>模擬器僅適用於開發和測試環境。
     * 在正式環境中，請使用實體 TPM 以確保金鑰的硬體保護。
     * </p>
     *
     * <h3>使用範例</h3>
     *
     * <pre>{@code
     * // 開發環境：強制使用模擬器
     * try (TPMService tpm = new TPMService(true)) {
     *     // 使用 TPM 模擬器進行測試
     * }
     *
     * // 正式環境：優先使用實體 TPM
     * try (TPMService tpm = new TPMService(false)) {
     *     // 使用實體 TPM 或降級到模擬器
     * }
     * }</pre>
     *
     * @param usingSimulator 若為 {@code true}，強制使用 TPM 模擬器；
     *                       若為 {@code false}，優先嘗試實體 TPM
     * @throws TPMConnectionException 當無法連接到指定的 TPM 裝置時拋出
     * @see #TPMService()
     * @see #connectToSimulator()
     * @see #connect()
     */
    public TPMService(boolean usingSimulator) throws TPMConnectionException {
        if (usingSimulator) {
            connectToSimulator();
            // 模擬器使用動態建立金鑰
            createSigningKey();
        } else {
            connect();
            // 實體 TPM 使用持久化金鑰
            loadPersistentSigningKey(DEFAULT_PERSISTENT_HANDLE);
        }
    }

    /**
     * 連接到 TPM 裝置。
     *
     * <p>
     * 此方法會依照以下順序嘗試建立連接：
     * </p>
     * <ol>
     * <li><b>實體 TPM</b>：連接系統的 TPM 裝置
     * <ul>
     * <li>Linux: 優先使用 /dev/tpmrm0（Resource Manager），確保與 tpm2-tools 相容</li>
     * <li>Windows: 透過 TPM Base Services (TBS)</li>
     * </ul>
     * </li>
     * </ol>
     *
     * <p>
     * <b>注意事項：</b>
     * </p>
     * <ul>
     * <li>若已經連接，此方法會記錄警告訊息並直接返回，不會重新連接</li>
     * </ul>
     *
     * @throws TPMConnectionException 當無法連接到任何 TPM 裝置時拋出，
     *                                包含詳細的錯誤訊息和建議的解決方案
     * @see #isConnected()
     * @see #isUsingSimulator()
     */
    public void connect() throws TPMConnectionException {
        if (connected) {
            log.warn("TPM already connected");
            return;
        }

        try {
            // Linux: 優先使用 /dev/tpmrm0 (Resource Manager)，確保與 tpm2-tools 相容
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("linux")) {
                tpm = connectToLinuxTpm();
            } else {
                // Windows 或其他平台使用預設方式
                tpm = TpmFactory.platformTpm();
            }

            if (tpm == null) {
                throw new TPMConnectionException("無法建立 TPM 連接");
            }
            usingSimulator = false;
            connected = true;
            log.debug("Connected to physical TPM device");
        } catch (Exception e) {
            log.error("Physical TPM not found ({})", e.getMessage());
            throw new TPMConnectionException("Cannot connect to TPM. Please ensure:\n"
                    + "   System has TPM chip enabled, or\n" + "   Error: " + e.getMessage());
        }
    }

    /**
     * 連接到 Linux TPM 裝置。
     *
     * <p>
     * 使用 TpmDeviceLinux，其 connect() 方法會自動優先嘗試 /dev/tpmrm0（Resource Manager），
     * 若失敗則嘗試 /dev/tpm0。使用 Resource Manager 可確保與 tpm2-tools 產生一致的結果（如 EK
     * Fingerprint）。
     * </p>
     *
     * @return 連接成功的 Tpm 實例
     * @throws Exception 當無法連接時拋出
     */
    private Tpm connectToLinuxTpm() throws Exception {
        // TpmDeviceLinux.connect() 會自動優先嘗試 /dev/tpmrm0，然後才是 /dev/tpm0
        TpmDeviceLinux linuxDevice = new TpmDeviceLinux();
        if (!linuxDevice.connect()) {
            throw new RuntimeException("Cannot connect to Linux TPM device (/dev/tpmrm0 or /dev/tpm0)");
        }

        Tpm tpmInstance = new Tpm();
        tpmInstance._setDevice(linuxDevice);
        log.debug("Connected to Linux TPM (via TpmDeviceLinux)");
        return tpmInstance;
    }

    /**
     * 直接連接到 TPM 模擬器。
     *
     * <p>
     * 此方法直接連接到 localhost:2321 的 TPM 模擬器，
     * 不會嘗試連接實體 TPM。適用於開發和測試環境。
     * </p>
     *
     * <p>
     * <b>安全警告：</b>模擬器的金鑰儲存在軟體記憶體中，
     * 不具備實體 TPM 的硬體安全保護。請勿在正式環境中使用。
     * </p>
     *
     * @throws TPMConnectionException 當無法連接到 TPM 模擬器時拋出
     * @see #connect()
     */
    public void connectToSimulator() throws TPMConnectionException {
        if (connected) {
            log.warn("TPM already connected");
            return;
        }

        try {
            tpm = TpmFactory.localTpmSimulator();
            if (tpm == null) {
                throw new TPMConnectionException("TpmFactory.localTpmSimulator() 返回 null");
            }
            usingSimulator = true;
            connected = true;
            log.debug("Connected to TPM simulator (for testing only)");
            log.debug(
                    "Security warning: Simulator does not provide hardware-level key protection, for development/testing only");
        } catch (Exception e) {
            throw new TPMConnectionException("Cannot connect to TPM simulator. Please ensure:\n"
                    + "   TPM simulator is running on localhost:2321\n" + "   Error: " + e.getMessage());
        }
    }

    /**
     * 確保已建立 TPM 連接。
     *
     * <p>
     * 若尚未連接或連接已斷開，此方法會自動嘗試重新連接。
     * 這是一個內部輔助方法，用於在執行 TPM 操作前確保連接狀態。
     * </p>
     *
     * @throws TPMConnectionException 當無法建立或重新建立 TPM 連接時拋出
     * @see #connect()
     */
    private void ensureConnected() throws TPMConnectionException {
        if (!connected || tpm == null) {
            throw new TPMConnectionException("Cannot connect to TPM or TPM simulator");
        }
    }

    /**
     * 檢查目前是否使用 TPM 模擬器。
     *
     * <p>
     * 當系統沒有實體 TPM 晶片時，服務會退而使用 TPM 模擬器。
     * 模擬器提供與實體 TPM 相同的 API，但安全性較低，
     * 因為金鑰實際上是儲存在軟體中而非硬體安全區域。
     * </p>
     *
     * <p>
     * <b>安全警告：</b>使用模擬器時，不應處理正式環境的敏感資料。
     * </p>
     *
     * @return {@code true} 如果目前連接到 TPM 模擬器；
     *         {@code false} 如果連接到實體 TPM 硬體
     * @see #connect()
     */
    public boolean isUsingSimulator() {
        return usingSimulator;
    }

    /**
     * 檢查 TPM 連接狀態。
     *
     * <p>
     * 此方法僅檢查邏輯連接狀態，不會實際測試 TPM 通訊。
     * 若需要確認 TPM 是否正常運作，建議執行一個簡單的 TPM 命令。
     * </p>
     *
     * @return {@code true} 如果已建立 TPM 連接；
     *         {@code false} 如果尚未連接或連接已關閉
     * @see #connect()
     * @see #close()
     */
    public boolean isConnected() {
        return connected;
    }

    // ==================== 簽章金鑰管理 ====================

    /**
     * 在 TPM 內建立簽署金鑰。
     *
     * <p>
     * 此方法會在 TPM 的 Owner Hierarchy 下建立兩個金鑰：
     * </p>
     * <ol>
     * <li><b>Primary Key</b>：作為父金鑰，使用 RSA 2048-bit，
     * 屬性包含 restricted、decrypt、fixedTPM、fixedParent</li>
     * <li><b>Signing Key</b>：作為子金鑰，專門用於數位簽章，
     * 使用 RSASSA-PKCS1-v1.5-SHA256 簽章方案</li>
     * </ol>
     *
     * <h3>金鑰屬性</h3>
     * <ul>
     * <li><b>fixedTPM</b>：金鑰繫結於此 TPM，無法匯出</li>
     * <li><b>fixedParent</b>：金鑰繫結於特定父金鑰</li>
     * <li><b>sensitiveDataOrigin</b>：私鑰由 TPM 產生，無法從外部匯入</li>
     * <li><b>userWithAuth</b>：使用 HMAC 授權即可使用此金鑰</li>
     * </ul>
     *
     * <h3>安全特性</h3>
     * <p>
     * 私鑰在 TPM 內部產生，<b>永遠不會離開 TPM 硬體</b>。
     * 即使攻擊者取得系統完整權限，也無法提取私鑰。
     * </p>
     *
     * <p>
     * <b>注意：</b>重複呼叫此方法會建立新的金鑰對，舊的金鑰不會自動清除。
     * </p>
     *
     * @throws TPMConnectionException 當 TPM 連接失敗或金鑰建立過程中發生錯誤時拋出
     * @see #sign(byte[])
     * @see #getSigningPublicKey()
     */
    public void createSigningKey() throws TPMConnectionException {
        ensureConnected();

        // Step 1: 建立 Primary Key
        TPMT_PUBLIC primaryTemplate = new TPMT_PUBLIC(
                TPM_ALG_ID.SHA256,
                new TPMA_OBJECT(
                        TPMA_OBJECT.restricted,
                        TPMA_OBJECT.decrypt,
                        TPMA_OBJECT.fixedTPM,
                        TPMA_OBJECT.fixedParent,
                        TPMA_OBJECT.sensitiveDataOrigin,
                        TPMA_OBJECT.userWithAuth),
                new byte[0],
                new TPMS_RSA_PARMS(
                        new TPMT_SYM_DEF_OBJECT(TPM_ALG_ID.AES, 128, TPM_ALG_ID.CFB),
                        new TPMS_NULL_ASYM_SCHEME(),
                        2048,
                        0),
                new TPM2B_PUBLIC_KEY_RSA());

        TPMS_SENSITIVE_CREATE inSensitive = new TPMS_SENSITIVE_CREATE(new byte[0], new byte[0]);

        CreatePrimaryResponse primaryResponse = tpm.CreatePrimary(
                TPM_HANDLE.from(TPM_RH.OWNER), inSensitive, primaryTemplate, new byte[0], new TPMS_PCR_SELECTION[0]);

        primaryHandle = primaryResponse.handle;

        // Step 2: 建立 Signing Key
        TPMT_PUBLIC signingTemplate = new TPMT_PUBLIC(
                TPM_ALG_ID.SHA256,
                new TPMA_OBJECT(
                        TPMA_OBJECT.sign,
                        TPMA_OBJECT.fixedTPM,
                        TPMA_OBJECT.fixedParent,
                        TPMA_OBJECT.sensitiveDataOrigin,
                        TPMA_OBJECT.userWithAuth),
                new byte[0],
                new TPMS_RSA_PARMS(
                        new TPMT_SYM_DEF_OBJECT(TPM_ALG_ID.NULL, 0, TPM_ALG_ID.NULL),
                        new TPMS_SCHEME_RSASSA(TPM_ALG_ID.SHA256),
                        2048,
                        0),
                new TPM2B_PUBLIC_KEY_RSA());

        CreateResponse createResponse =
                tpm.Create(primaryHandle, inSensitive, signingTemplate, new byte[0], new TPMS_PCR_SELECTION[0]);

        // Load the signing key
        signingKeyHandle = tpm.Load(primaryHandle, createResponse.outPrivate, createResponse.outPublic);

        signingKeyPublicArea = createResponse.outPublic;

        log.debug("Signing key created");
        log.trace("   Primary Handle: 0x{}", Integer.toHexString(primaryHandle.handle));
        log.trace("   Signing Key Handle: 0x{}", Integer.toHexString(signingKeyHandle.handle));
    }

    /**
     * 載入 TPM 持久化的簽章金鑰。
     *
     * <p>
     * 此方法載入透過 tpm2-tools 預先建立並持久化的簽章金鑰。
     * 持久化金鑰的優勢是每次重啟應用程式都使用相同的金鑰對，
     * 確保公鑰與區塊鏈上註冊的一致。
     * </p>
     *
     * <h3>建立持久化金鑰</h3>
     * <p>
     * 使用專案提供的腳本建立持久化金鑰：
     * </p>
     *
     * <pre>{@code
     * ./scripts/tpm_init_key.sh 0x81000001
     * }</pre>
     *
     * <h3>使用範例</h3>
     *
     * <pre>{@code
     * try (TPMService tpm = new TPMService()) {
     *     // 預設會自動載入 0x81000001 的持久化金鑰
     *     byte[] signature = tpm.sign(data);
     * }
     * }</pre>
     *
     * @param persistentHandle 持久化金鑰的 Handle（範圍：0x81000000 - 0x81FFFFFF）
     * @throws TPMConnectionException 當 TPM 連接失敗或金鑰不存在時拋出
     * @see #createSigningKey()
     * @see #DEFAULT_PERSISTENT_HANDLE
     */
    public void loadPersistentSigningKey(int persistentHandle) throws TPMConnectionException {
        ensureConnected();

        try {
            // 建立持久化 Handle - 使用 TPM_HANDLE.from() 直接轉換整數
            signingKeyHandle = TPM_HANDLE.from(persistentHandle);

            // 讀取公鑰資訊
            ReadPublicResponse response = tpm.ReadPublic(signingKeyHandle);
            signingKeyPublicArea = response.outPublic;

            usingPersistentKey = true;

            log.debug("Persistent signing key loaded");
            log.debug("   Handle: 0x{}", Integer.toHexString(persistentHandle));
            log.trace("   Key algorithm: {}", signingKeyPublicArea.nameAlg);

        } catch (Exception e) {
            throw new TPMConnectionException(
                    "Cannot load persistent key 0x" + Integer.toHexString(persistentHandle) + ".\n"
                            + "Please confirm that ./scripts/tpm_init_key.sh was used to create the key.\n"
                            + "Error: "
                            + e.getMessage());
        }
    }

    /**
     * 檢查目前是否使用持久化金鑰。
     *
     * @return {@code true} 如果使用持久化金鑰；{@code false} 如果使用動態建立的金鑰
     */
    public boolean isUsingPersistentKey() {
        return usingPersistentKey;
    }

    /**
     * 確保簽署金鑰已建立。
     *
     * <p>
     * 若簽署金鑰尚未建立，此方法會自動呼叫 {@link #createSigningKey()}。
     * 這是一個內部輔助方法，確保簽章操作前金鑰已就緒。
     * </p>
     *
     * @throws TPMConnectionException 當金鑰建立過程中發生連接或其他錯誤時拋出
     * @see #createSigningKey()
     */
    private void ensureSigningKeyCreated() throws TPMConnectionException {
        if (signingKeyHandle == null) {
            createSigningKey();
        }
    }

    // ==================== 簽章與驗證 ====================

    /**
     * 使用 TPM 簽署原始資料。
     *
     * <p>
     * 此方法會先對資料進行 SHA-256 雜湊，然後使用 TPM 內的簽署金鑰
     * 對 digest 進行 RSASSA-PKCS1-v1.5 簽章。
     * </p>
     *
     * <h3>簽章流程</h3>
     * <ol>
     * <li>計算輸入資料的 SHA-256 雜湊值（32 bytes）</li>
     * <li>將 digest 傳送到 TPM 硬體</li>
     * <li>TPM 使用內部的私鑰執行簽章操作</li>
     * <li>返回簽章結果（256 bytes 對於 RSA 2048-bit）</li>
     * </ol>
     *
     * <p>
     * <b>安全特性：</b>私鑰永遠不離開 TPM，簽章在硬體內完成。
     * </p>
     *
     * <p>
     * <b>執行緒安全：</b>此方法是同步的（synchronized），可安全地在多執行緒環境中使用。
     * 多個執行緒同時呼叫時，會依序執行簽章操作。
     * </p>
     *
     * <h3>使用範例</h3>
     *
     * <pre>{@code
     * byte[] data = "Hello TPM!".getBytes(StandardCharsets.UTF_8);
     * byte[] signature = tpmService.sign(data);
     * // signature.length == 256 (for RSA 2048-bit)
     * }</pre>
     *
     * @param data 要簽署的原始資料，不可為 {@code null}
     * @return 簽章結果，長度為 256 bytes（RSA 2048-bit）
     * @throws TPMConnectionException   當 TPM 連接失敗時拋出
     * @throws NoSuchAlgorithmException 當 SHA-256 雜湊演算法不存在時拋出（極罕見）
     * @see #signDigest(byte[])
     * @see #verify(byte[], byte[])
     */
    public synchronized byte[] sign(final byte[] data) throws TPMConnectionException, NoSuchAlgorithmException {
        byte[] digest = Util.calculateHash(data);
        return signDigest(digest);
    }

    /**
     * 使用 TPM 簽署已計算的 digest。
     *
     * <p>
     * 此方法直接對已經計算好的 SHA-256 digest 進行簽章，
     * 適用於已經在外部進行雜湊運算的場景。
     * </p>
     *
     * <p>
     * <b>重要：</b>digest 必須是 SHA-256 雜湊結果（正好 32 bytes），
     * 否則 TPM 可能會拒絕簽章或產生不相容的簽章。
     * </p>
     *
     * <p>
     * <b>執行緒安全：</b>此方法是同步的（synchronized），可安全地在多執行緒環境中使用。
     * </p>
     *
     * @param digest SHA-256 雜湊值，必須為 32 bytes
     * @return 簽章結果，長度為 256 bytes（RSA 2048-bit）
     * @throws TPMConnectionException   當 TPM 連接失敗時拋出
     * @throws IllegalArgumentException 當簽章類型不支援時拋出
     * @see #sign(byte[])
     */
    public synchronized byte[] signDigest(final byte[] digest) throws TPMConnectionException, IllegalArgumentException {
        ensureConnected();
        ensureSigningKeyCreated();

        TPMU_SIG_SCHEME scheme = new TPMS_NULL_SIG_SCHEME();
        TPMT_TK_HASHCHECK validation = new TPMT_TK_HASHCHECK(TPM_HANDLE.from(TPM_RH.NULL), new byte[0]);

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.debug("Attempting TPM signing (attempt {}/{})", attempt, maxRetries);
                TPMU_SIGNATURE signature = tpm.Sign(signingKeyHandle, digest, scheme, validation);
                if (attempt > 1) {
                    log.debug("TPM signing succeeded on attempt {}", attempt);
                }
                return extractSignatureBytes(signature);
            } catch (RuntimeException e) {
                lastException = e;
                log.warn("TPM signing attempt {}/{} failed: {}", attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        // 指數退避延遲讓 TPM 設備恢復 (100, 200, 400, 800ms)
                        long delay = baseDelayMs * (1L << (attempt - 1));
                        log.debug("Waiting {}ms before retry...", delay);

                        // TPM 重試邏輯需要持有鎖來防止其他執行緒同時存取 TPM 設備
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("TPM signing interrupted", ie);
                    }
                }
            }
        }

        throw new TPMConnectionException("TPM signing failed after " + maxRetries + " retries", lastException);
    }

    /**
     * 使用 TPM 硬體驗證簽章。
     *
     * <p>
     * 此方法使用 TPM 內的公鑰驗證簽章，驗證過程在 TPM 硬體內完成。
     * 此方法僅適用於本機驗證（需要 TPM 硬體）。
     * </p>
     *
     * <p>
     * 若需進行遠端驗證（不需 TPM），請使用
     * {@link #verifyWithJavaCrypto(PublicKey, byte[], byte[])}。
     * </p>
     *
     * @param data      原始資料（與簽章時使用的資料必須完全一致）
     * @param signature 要驗證的簽章
     * @return {@code true} 如果簽章有效；{@code false} 如果簽章無效或驗證失敗
     * @throws TPMConnectionException   當 TPM 連接失敗時拋出
     * @throws NoSuchAlgorithmException 當 SHA-256 雜湊演算法不存在時拋出
     * @see #verifyDigest(byte[], byte[])
     * @see #verifyWithJavaCrypto(PublicKey, byte[], byte[])
     */
    public boolean verify(final byte[] data, final byte[] signature)
            throws TPMConnectionException, NoSuchAlgorithmException {
        byte[] digest = Util.calculateHash(data);
        return verifyDigest(digest, signature);
    }

    /**
     * 使用 TPM 硬體驗證 digest 簽章。
     *
     * <p>
     * 此方法直接驗證已經計算好的 SHA-256 digest 的簽章，
     * 適用於已經在外部進行雜湊運算的場景。
     * </p>
     *
     * @param digest    SHA-256 雜湊值（32 bytes）
     * @param signature 要驗證的簽章
     * @return {@code true} 如果簽章有效；{@code false} 如果簽章無效或驗證失敗
     * @throws TPMConnectionException 當 TPM 連接失敗時拋出
     * @see #verify(byte[], byte[])
     */
    public boolean verifyDigest(final byte[] digest, final byte[] signature) throws TPMConnectionException {
        ensureConnected();
        ensureSigningKeyCreated();

        try {
            TPMS_SIGNATURE_RSASSA tpmSignature = new TPMS_SIGNATURE_RSASSA(TPM_ALG_ID.SHA256, signature);

            TPMT_TK_VERIFIED ticket = tpm.VerifySignature(signingKeyHandle, digest, tpmSignature);

            return ticket != null;
        } catch (Exception e) {
            log.debug("TPM verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 使用 Java Crypto API 驗證簽章（適用於遠端驗證）。
     *
     * <p>
     * 此靜態方法不需要 TPM 硬體，可在任何 Java 環境中驗證 TPM 產生的簽章。
     * 這讓遠端伺服器或其他系統可以驗證資料確實來自特定的 TPM 設備。
     * </p>
     *
     * <h3>遠端驗證場景</h3>
     * <ol>
     * <li>設備端使用 TPM 簽署資料，並將資料、簽章和公鑰傳送給伺服器</li>
     * <li>伺服器使用此方法驗證簽章，確認資料完整性和來源</li>
     * <li>若驗證成功，可信任資料確實來自擁有該 TPM 的設備</li>
     * </ol>
     *
     * <h3>使用範例</h3>
     *
     * <pre>{@code
     * // 接收端（不需要 TPM）
     * PublicKey publicKey = ... // 從傳輸資料中還原
     * byte[] data = ...         // 原始資料
     * byte[] signature = ...    // 簽章
     *
     * boolean isValid = TPMService.verifyWithJavaCrypto(publicKey, data, signature);
     * }</pre>
     *
     * @param publicKey 用於驗證的 RSA 公鑰（可從 {@link #getSigningPublicKey()} 取得）
     * @param data      原始資料（與簽章時使用的資料必須完全一致）
     * @param signature 要驗證的簽章
     * @return {@code true} 如果簽章有效；{@code false} 如果簽章無效或驗證過程發生錯誤
     * @see #verifyDigestWithJavaCrypto(PublicKey, byte[], byte[])
     * @see #getSigningPublicKey()
     */
    public static boolean verifyWithJavaCrypto(final PublicKey publicKey, final byte[] data, final byte[] signature) {
        try {
            byte[] digest = Util.calculateHash(data);
            return verifyDigestWithJavaCrypto(publicKey, digest, signature);
        } catch (Exception e) {
            log.error("Verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 使用 Java Crypto API 驗證 digest 簽章。
     *
     * <p>
     * 此方法使用 {@code NONEwithRSA} 演算法，因為 TPM 已經對資料進行過
     * SHA-256 雜湊。此方法會自動加入 PKCS#1 v1.5 DigestInfo 前綴，
     * 以符合 RSASSA-PKCS1-v1.5 簽章格式。
     * </p>
     *
     * <h3>DigestInfo 結構</h3>
     * <p>
     * PKCS#1 v1.5 要求在 digest 前加入 ASN.1 結構的 DigestInfo：
     * </p>
     *
     * <pre>
     * DigestInfo ::= SEQUENCE {
     *     digestAlgorithm AlgorithmIdentifier,
     *     digest OCTET STRING
     * }
     * </pre>
     * <p>
     * 對於 SHA-256，此前綴為 19 bytes 的固定值。
     * </p>
     *
     * @param publicKey 用於驗證的 RSA 公鑰
     * @param digest    SHA-256 雜湊值（32 bytes）
     * @param signature 要驗證的簽章
     * @return {@code true} 如果簽章有效；{@code false} 如果簽章無效或驗證過程發生錯誤
     * @see #verifyWithJavaCrypto(PublicKey, byte[], byte[])
     */
    public static boolean verifyDigestWithJavaCrypto(
            final PublicKey publicKey, final byte[] digest, final byte[] signature) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM_RAW);
            sig.initVerify(publicKey);

            // PKCS#1 v1.5 SHA-256 DigestInfo 前綴
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

            // Combine DigestInfo prefix with the digest
            byte[] digestInfo = new byte[sha256DigestInfo.length + digest.length];
            System.arraycopy(sha256DigestInfo, 0, digestInfo, 0, sha256DigestInfo.length);
            System.arraycopy(digest, 0, digestInfo, sha256DigestInfo.length, digest.length);

            sig.update(digestInfo);
            return sig.verify(signature);
        } catch (Exception e) {
            log.error("Java Crypto verification failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 公鑰轉換 ====================

    /**
     * 取得簽署金鑰的 Java {@link PublicKey} 物件。
     *
     * <p>
     * 此方法將 TPM 格式的公鑰轉換為標準的 Java {@link PublicKey}，
     * 可用於：
     * </p>
     * <ul>
     * <li>匯出給遠端系統進行簽章驗證</li>
     * <li>使用 Java Crypto API 進行本機驗證</li>
     * <li>儲存到資料庫或傳輸給其他系統</li>
     * </ul>
     *
     * <h3>公鑰匯出範例</h3>
     *
     * <pre>{@code
     * PublicKey publicKey = tpmService.getSigningPublicKey();
     *
     * // 轉換為 Base64 格式供傳輸
     * String base64Key = Base64.getEncoder().encodeToString(publicKey.getEncoded());
     *
     * // 在接收端還原
     * byte[] keyBytes = Base64.getDecoder().decode(base64Key);
     * X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
     * PublicKey restored = KeyFactory.getInstance("RSA").generatePublic(spec);
     * }</pre>
     *
     * @return 簽署金鑰的 Java {@link PublicKey}，格式為 X.509 編碼
     * @throws TPMConnectionException   當 TPM 連接失敗時拋出
     * @throws NoSuchAlgorithmException 當 RSA 演算法不存在時拋出（極罕見）
     * @throws InvalidKeySpecException  當金鑰規格無效時拋出
     * @see #convertTPMPublicKeyToJava(TPMT_PUBLIC)
     * @see #verifyWithJavaCrypto(PublicKey, byte[], byte[])
     */
    public PublicKey getSigningPublicKey()
            throws TPMConnectionException, NoSuchAlgorithmException, InvalidKeySpecException {
        ensureSigningKeyCreated();
        return convertTPMPublicKeyToJava(signingKeyPublicArea);
    }

    /**
     * 將 TPM RSA 公鑰轉換為 Java {@link PublicKey}。
     *
     * <p>
     * 此靜態方法將 TPM 特有的 {@link TPMT_PUBLIC} 格式轉換為
     * 標準的 Java {@link PublicKey}，便於與 Java Crypto API 配合使用。
     * </p>
     *
     * <h3>轉換過程</h3>
     * <ol>
     * <li>從 {@link TPMT_PUBLIC} 提取 RSA 參數（模數和指數）</li>
     * <li>使用 {@link RSAPublicKeySpec} 建立金鑰規格</li>
     * <li>透過 {@link KeyFactory} 產生標準 {@link PublicKey}</li>
     * </ol>
     *
     * <p>
     * <b>注意：</b>目前僅支援 RSA 金鑰。尚不支援 ECC 金鑰轉換。
     * </p>
     *
     * @param tpmPublic TPM 格式的 RSA 公鑰
     * @return 轉換後的 Java {@link PublicKey}
     * @throws IllegalArgumentException 當輸入不是 RSA 金鑰時拋出
     * @throws NoSuchAlgorithmException 如果 RSA 演算法不存在時拋出
     * @throws InvalidKeySpecException  當金鑰規格無效時拋出
     * @see #getSigningPublicKey()
     * @see #getRSAEndorsementPublicKey()
     */
    public static PublicKey convertTPMPublicKeyToJava(final TPMT_PUBLIC tpmPublic)
            throws IllegalArgumentException, NoSuchAlgorithmException, InvalidKeySpecException {
        if (!(tpmPublic.parameters instanceof TPMS_RSA_PARMS)) {
            throw new IllegalArgumentException("Not an RSA key");
        }

        TPMS_RSA_PARMS rsaParams = (TPMS_RSA_PARMS) tpmPublic.parameters;
        TPM2B_PUBLIC_KEY_RSA rsaUnique = (TPM2B_PUBLIC_KEY_RSA) tpmPublic.unique;

        BigInteger modulus = new BigInteger(1, rsaUnique.buffer);
        long exponentValue = rsaParams.exponent == 0 ? 65537 : rsaParams.exponent;
        BigInteger exponent = BigInteger.valueOf(exponentValue);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        KeyFactory kf = KeyFactory.getInstance(KEY_ALGORITHM);
        return kf.generatePublic(spec);
    }

    // ==================== Endorsement Key (EK) ====================

    /**
     * 讀取 RSA Endorsement Key (EK) 公鑰。
     *
     * <p>
     * Endorsement Key 是 TPM 出廠時由製造商建立的<b>唯一識別金鑰</b>，
     * 用於證明 TPM 的真實性和唯一性。EK 具有以下特性：
     * </p>
     *
     * <ul>
     * <li><b>唯一性</b>：每個 TPM 的 EK 都是獨一無二的</li>
     * <li><b>不可轉移</b>：EK 私鑰無法從 TPM 匯出</li>
     * <li><b>僅解密</b>：EK 設計為只能用於解密，不能用於簽章</li>
     * <li><b>應用場景</b>：主要用於 Remote Attestation 和設備證明</li>
     * </ul>
     *
     * <h3>EK 的用途</h3>
     * <ul>
     * <li>設備識別：透過 EK 指紋唯一識別設備</li>
     * <li>Remote Attestation：證明 TPM 的真實性</li>
     * <li>註冊過程：將設備與特定服務繫結</li>
     * </ul>
     *
     * <p>
     * 此方法使用 TCG EK Credential Profile 定義的標準範本建立 EK。
     * </p>
     *
     * @return TPM 格式的 RSA EK 公鑰（{@link TPMT_PUBLIC}）
     * @throws TPMConnectionException 當 TPM 連接失敗時拋出
     * @see #readECCEndorsementKey()
     * @see #getRSAEndorsementPublicKey()
     * @see #getEKFingerprint()
     * @see <a href=
     *      "https://trustedcomputinggroup.org/resource/tcg-ek-credential-profile/">TCG
     *      EK Credential Profile</a>
     */
    public TPMT_PUBLIC readRSAEndorsementKey() throws TPMConnectionException {
        ensureConnected();

        TPMT_PUBLIC ekTemplate = new TPMT_PUBLIC(
                TPM_ALG_ID.SHA256,
                new TPMA_OBJECT(
                        TPMA_OBJECT.restricted,
                        TPMA_OBJECT.decrypt,
                        TPMA_OBJECT.fixedTPM,
                        TPMA_OBJECT.fixedParent,
                        TPMA_OBJECT.adminWithPolicy,
                        TPMA_OBJECT.sensitiveDataOrigin),
                EK_POLICY_DIGEST,
                new TPMS_RSA_PARMS(
                        new TPMT_SYM_DEF_OBJECT(TPM_ALG_ID.AES, 128, TPM_ALG_ID.CFB),
                        new TPMS_NULL_ASYM_SCHEME(),
                        2048,
                        0),
                new TPM2B_PUBLIC_KEY_RSA());

        return createEndorsementKey(ekTemplate);
    }

    /**
     * 讀取 ECC Endorsement Key (EK) 公鑰。
     *
     * <p>
     * 此方法讀取 ECC P-256 曲線的 Endorsement Key。
     * ECC EK 提供與 RSA EK 相同的功能，但使用更短的金鑰長度，
     * 適合資源受限的環境。
     * </p>
     *
     * <p>
     * <b>注意：</b>並非所有 TPM 都支援 ECC。若 TPM 不支援 ECC，
     * 此方法可能會拋出異常。
     * </p>
     *
     * @return TPM 格式的 ECC EK 公鑰（{@link TPMT_PUBLIC}）
     * @throws TPMConnectionException 當 TPM 連接失敗或不支援 ECC 時拋出
     * @see #readRSAEndorsementKey()
     */
    public TPMT_PUBLIC readECCEndorsementKey() throws TPMConnectionException {
        ensureConnected();

        TPMT_PUBLIC ekTemplate = new TPMT_PUBLIC(
                TPM_ALG_ID.SHA256,
                new TPMA_OBJECT(
                        TPMA_OBJECT.restricted,
                        TPMA_OBJECT.decrypt,
                        TPMA_OBJECT.fixedTPM,
                        TPMA_OBJECT.fixedParent,
                        TPMA_OBJECT.adminWithPolicy,
                        TPMA_OBJECT.sensitiveDataOrigin),
                EK_POLICY_DIGEST,
                new TPMS_ECC_PARMS(
                        new TPMT_SYM_DEF_OBJECT(TPM_ALG_ID.AES, 128, TPM_ALG_ID.CFB),
                        new TPMS_NULL_ASYM_SCHEME(),
                        TPM_ECC_CURVE.NIST_P256,
                        new TPMS_NULL_KDF_SCHEME()),
                new TPMS_ECC_POINT());

        return createEndorsementKey(ekTemplate);
    }

    /**
     * 在 Endorsement Hierarchy 下建立 EK。
     *
     * <p>
     * 此內部方法使用提供的範本在 TPM 的 Endorsement Hierarchy 下
     * 建立 Primary Key（即 EK）。建立後會立即釋放 Handle 以節省 TPM 資源。
     * </p>
     *
     * <p>
     * 由於 EK 使用標準範本，每次呼叫此方法都會產生相同的公鑰，
     * 因此可以安全地釋放 Handle 而不影響指紋計算。
     * </p>
     *
     * @param template EK 公鑰範本（RSA 或 ECC）
     * @return 建立的 EK 公鑰（{@link TPMT_PUBLIC}）
     * @see #readRSAEndorsementKey()
     * @see #readECCEndorsementKey()
     */
    private TPMT_PUBLIC createEndorsementKey(final TPMT_PUBLIC template) {
        TPMS_SENSITIVE_CREATE inSensitive = new TPMS_SENSITIVE_CREATE(new byte[0], new byte[0]);

        CreatePrimaryResponse response = tpm.CreatePrimary(
                TPM_HANDLE.from(TPM_RH.ENDORSEMENT), inSensitive, template, new byte[0], new TPMS_PCR_SELECTION[0]);

        TPMT_PUBLIC publicKey = response.outPublic;
        tpm.FlushContext(response.handle);

        return publicKey;
    }

    /**
     * 取得 RSA EK 的 Java {@link PublicKey}。
     *
     * <p>
     * 此方法讀取 RSA EK 並將其轉換為標準的 Java {@link PublicKey}，
     * 便於與其他 Java 加密庫或服務配合使用。
     * </p>
     *
     * @return RSA EK 的 Java {@link PublicKey}
     * @throws TPMConnectionException   當 TPM 連接失敗時拋出
     * @throws NoSuchAlgorithmException 當 RSA 演算法不存在時拋出
     * @throws InvalidKeySpecException  當金鑰規格無效時拋出
     * @see #readRSAEndorsementKey()
     * @see #convertTPMPublicKeyToJava(TPMT_PUBLIC)
     */
    public PublicKey getRSAEndorsementPublicKey()
            throws TPMConnectionException, NoSuchAlgorithmException, InvalidKeySpecException {
        TPMT_PUBLIC ekPublic = readRSAEndorsementKey();
        return convertTPMPublicKeyToJava(ekPublic);
    }

    /**
     * 從 TPM NV 儲存區讀取 EK 憑證。
     *
     * <p>
     * EK 憑證是由 TPM 製造商簽發的 X.509 憑證，
     * 用於證明 EK 的真實性。此憑證儲存在 TPM 的 NV（Non-Volatile）儲存區。
     * </p>
     *
     * <p>
     * 標準 NV 索引：
     * </p>
     * <ul>
     * <li>{@link #NV_INDEX_RSA_EK_CERT} (0x01C00002) - RSA 2048 EK 憑證</li>
     * <li>{@link #NV_INDEX_ECC_EK_CERT} (0x01C0000A) - ECC P-256 EK 憑證</li>
     * </ul>
     *
     * <p>
     * <b>注意：</b>並非所有 TPM 都有預先寫入的 EK 憑證。
     * TPM 模擬器和部分實體 TPM 可能沒有 EK 憑證。
     * </p>
     *
     * @param nvIndex NV 儲存區索引，如 {@link #NV_INDEX_RSA_EK_CERT}
     * @return EK 憑證的 DER 編碼 byte array
     * @throws TPMConnectionException 當 TPM 連接失敗或指定的 NV 索引不存在時拋出
     * @see #NV_INDEX_RSA_EK_CERT
     * @see #NV_INDEX_ECC_EK_CERT
     */
    public byte[] readEKCertificate(final int nvIndex) throws TPMConnectionException {
        ensureConnected();

        TPM_HANDLE nvHandle = TPM_HANDLE.from(nvIndex);

        NV_ReadPublicResponse nvPublic = tpm.NV_ReadPublic(nvHandle);
        int size = nvPublic.nvPublic.dataSize;

        log.debug("NV area size: {} bytes", size);

        byte[] certificate = new byte[size];
        int offset = 0;
        int maxReadSize = 1024;

        while (offset < size) {
            int readSize = Math.min(maxReadSize, size - offset);
            byte[] chunk = tpm.NV_Read(TPM_HANDLE.from(TPM_RH.OWNER), nvHandle, (short) readSize, (short) offset);
            System.arraycopy(chunk, 0, certificate, offset, chunk.length);
            offset += chunk.length;
        }

        return certificate;
    }

    /**
     * 取得 RSA EK 的 SHA-256 指紋。
     *
     * <p>
     * EK 指紋是 EK 公鑰 DER 編碼的 SHA-256 雜湊值，可作為設備的唯一識別碼。
     * 由於每個 TPM 的 EK 都是唯一的，指紋也是唯一的。
     * </p>
     *
     * <p>
     * 此方法透過呼叫 tpm2-tools 來取得 EK 公鑰，確保與 Shell 腳本
     * （如 tpm_init_key.sh）產生的指紋完全一致。
     * </p>
     *
     * <h3>應用場景</h3>
     * <ul>
     * <li>設備註冊：將指紋與設備資訊繫結</li>
     * <li>設備驗證：確認設備身份</li>
     * <li>設備管理：追蹤和管理多個 TPM 設備</li>
     * </ul>
     *
     * <h3>指紋格式</h3>
     * <p>
     * 返回值為 64 字元的十六進位字串（256 bits / 32 bytes），
     * 例如：{@code "A1B2C3D4..."}
     * </p>
     *
     * @return EK 指紋（SHA-256 雜湊值的十六進位字串，64 字元）
     * @throws TPMConnectionException 當無法取得 EK 指紋時拋出
     */
    public synchronized String getEKFingerprint() throws TPMConnectionException {
        // 使用快取避免重複計算
        if (cachedEKFingerprint != null) {
            return cachedEKFingerprint;
        }

        try {
            cachedEKFingerprint = getEKFingerprintViaTpm2Tools();
            log.debug("EK Fingerprint 已快取 (via tpm2-tools): {}", cachedEKFingerprint);
            return cachedEKFingerprint;
        } catch (Exception e) {
            throw new TPMConnectionException("Cannot get EK Fingerprint (tpm2-tools)", e);
        }
    }

    /**
     * 透過 tpm2-tools 取得 EK Fingerprint。
     *
     * <p>
     * 此方法呼叫系統的 tpm2-tools 命令來建立和讀取 EK，
     * 確保與 Shell 腳本（如 tpm_init_key.sh）產生的指紋完全一致。
     * </p>
     *
     * @return EK 指紋（SHA-256 雜湊值的十六進位字串）
     * @throws TPMConnectionException 當命令執行失敗時拋出
     */
    private String getEKFingerprintViaTpm2Tools() throws TPMConnectionException {
        Path tempDir = null;
        try {
            // 建立暫存目錄
            tempDir = Files.createTempDirectory("tpm_ek_");
            Path ekCtx = tempDir.resolve("ek.ctx");
            Path ekPem = tempDir.resolve("ek.pem");

            // 建立 EK（使用 /dev/tpmrm0）
            ProcessBuilder pb1 = new ProcessBuilder("tpm2_createek", "-c", ekCtx.toString(), "-G", "rsa");
            pb1.environment().put("TPM2TOOLS_TCTI", "device:/dev/tpmrm0");
            pb1.redirectErrorStream(true);
            Process p1 = pb1.start();

            String output1;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p1.getInputStream()))) {
                output1 = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode1 = p1.waitFor();
            if (exitCode1 != 0) {
                throw new TPMConnectionException("tpm2_createek failed: " + output1);
            }

            // 讀取 EK 公鑰為 PEM 格式
            ProcessBuilder pb2 =
                    new ProcessBuilder("tpm2_readpublic", "-c", ekCtx.toString(), "-f", "pem", "-o", ekPem.toString());
            pb2.environment().put("TPM2TOOLS_TCTI", "device:/dev/tpmrm0");
            pb2.redirectErrorStream(true);
            Process p2 = pb2.start();
            int exitCode2 = p2.waitFor();
            if (exitCode2 != 0) {
                throw new TPMConnectionException("tpm2_readpublic failed, exit code: " + exitCode2);
            }

            // 使用 openssl 計算 DER 編碼的 SHA-256
            ProcessBuilder pb3 = new ProcessBuilder(
                    "bash",
                    "-c",
                    "openssl rsa -pubin -in " + ekPem
                            + " -outform DER 2>/dev/null | sha256sum | awk '{print toupper($1)}'");
            pb3.redirectErrorStream(true);
            Process p3 = pb3.start();

            String fingerprint;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p3.getInputStream()))) {
                fingerprint = reader.readLine();
            }

            int exitCode3 = p3.waitFor();
            if (exitCode3 != 0 || fingerprint == null || fingerprint.isEmpty()) {
                throw new TPMConnectionException("Failed to calculate EK fingerprint");
            }

            return fingerprint.trim();

        } catch (TPMConnectionException e) {
            throw e;
        } catch (Exception e) {
            throw new TPMConnectionException("Failed to get EK Fingerprint: " + e.getMessage(), e);
        } finally {
            // 清理暫存檔案
            if (tempDir != null) {
                try (Stream<Path> pathStream = Files.walk(tempDir)) {
                    pathStream.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // 忽略清理錯誤
                        }
                    });
                } catch (Exception ignored) {
                    // 忽略清理錯誤
                }
            }
        }
    }

    // ==================== 輔助方法 ====================

    /**
     * 從 TPM 簽章物件中提取原始簽章 bytes。
     *
     * <p>
     * 此方法處理不同類型的 TPM 簽章結構，提取出實際的簽章 byte array。
     * 目前支援的簽章類型：
     * </p>
     * <ul>
     * <li>{@code TPMS_SIGNATURE_RSASSA} - RSASSA-PKCS1-v1.5 簽章</li>
     * <li>{@code TPMS_SIGNATURE_RSAPSS} - RSASSA-PSS 簽章</li>
     * </ul>
     *
     * @param signature TPM 簽章物件（{@link TPMU_SIGNATURE}）
     * @return 簽章 byte array
     * @throws IllegalArgumentException 當簽章類型不在支援範圍內時拋出
     */
    private byte[] extractSignatureBytes(final TPMU_SIGNATURE signature) throws IllegalArgumentException {
        if (signature instanceof TPMS_SIGNATURE_RSASSA rsassaSignature) {
            return rsassaSignature.sig;
        } else if (signature instanceof TPMS_SIGNATURE_RSAPSS rsapssSignature) {
            return rsapssSignature.sig;
        }
        throw new IllegalArgumentException(
                "Unsupported signature type: " + signature.getClass().getName());
    }

    /**
     * 顯示 EK 公鑰的詳細資訊。
     *
     * <p>
     * 此方法使用日誌輸出 EK 公鑰的各項屬性，包括：
     * </p>
     * <ul>
     * <li>名稱雜湊演算法</li>
     * <li>物件屬性（TPMA_OBJECT）</li>
     * <li>金鑰類型（RSA 或 ECC）</li>
     * <li>金鑰長度或曲線資訊</li>
     * </ul>
     *
     * <p>
     * 在 DEBUG 級別會輸出更詳細的金鑰資料（如模數前 64 字元）。
     * </p>
     *
     * @param name     顯示用的名稱（如 "RSA EK" 或 "ECC EK"）
     * @param ekPublic 要顯示的 EK 公鑰
     * @see #readRSAEndorsementKey()
     * @see #readECCEndorsementKey()
     */
    public void displayEKInfo(final String name, final TPMT_PUBLIC ekPublic) {
        log.info("{} loaded successfully", name);
        log.info("   - Algorithm: {}", ekPublic.nameAlg);
        log.info("   - Attributes: {}", ekPublic.objectAttributes);

        if (ekPublic.parameters instanceof TPMS_RSA_PARMS rsaParams) {
            TPM2B_PUBLIC_KEY_RSA rsaKey = (TPM2B_PUBLIC_KEY_RSA) ekPublic.unique;

            log.info("   - Key type: RSA");
            log.info("   - Key length: {} bits", rsaParams.keyBits);
            log.info("   - Modulus length: {} bytes", rsaKey.buffer.length);
            log.debug(
                    "   - Modulus (Hex first 64 chars): {}...",
                    Util.byteArrayToHexString(rsaKey.buffer).substring(0, Math.min(64, rsaKey.buffer.length * 2)));

        } else if (ekPublic.parameters instanceof TPMS_ECC_PARMS eccParams) {
            TPMS_ECC_POINT eccPoint = (TPMS_ECC_POINT) ekPublic.unique;

            log.info("   - Key type: ECC");
            log.info("   - Curve: {}", eccParams.curveID);
            log.info("   - X coordinate length: {} bytes", eccPoint.x.length);
            log.info("   - Y coordinate length: {} bytes", eccPoint.y.length);
        }
    }

    // ==================== 資源清理 ====================

    /**
     * 清理並釋放所有 TPM 資源。
     *
     * <p>
     * 此方法會執行以下清理動作：
     * </p>
     * <ol>
     * <li>釋放 Signing Key 的 TPM Handle（若存在）</li>
     * <li>釋放 Primary Key 的 TPM Handle（若存在）</li>
     * <li>將連接狀態設為未連接</li>
     * </ol>
     *
     * <p>
     * <b>重要：</b>建議使用 try-with-resources 語法確保此方法被呼叫，
     * 避免 TPM Handle 洩漏。TPM 的 Handle 數量有限，若不清理可能導致後續操作失敗。
     *
     *
     *
     *
     * <h3>使用範例</h3>
     *
     * <pre>{@code
     * try (TPMService tpm = new TPMService()) {
     *     // 使用 TPM 服務
     *     tpm.createSigningKey();
     *     byte[] signature = tpm.sign(data);
     * } // close() 會自動被呼叫
     * }</pre>
     *
     * <p>
     * 清理過程中的任何錯誤會被記錄但不會拋出異常，
     * 確保清理流程盡可能完成。
     * </p>
     *
     * @see AutoCloseable#close()
     */
    @Override
    public void close() {
        try {
            // 持久化金鑰不需要 FlushContext，只有動態建立的金鑰需要
            if (!usingPersistentKey) {
                if (signingKeyHandle != null) {
                    tpm.FlushContext(signingKeyHandle);
                    signingKeyHandle = null;
                }
                if (primaryHandle != null) {
                    tpm.FlushContext(primaryHandle);
                    primaryHandle = null;
                }
            } else {
                // 持久化金鑰只需清除參考
                signingKeyHandle = null;
            }
            connected = false;
            log.debug("TPM resources cleaned up");
        } catch (Exception e) {
            log.error("Error during cleanup: {}", e.getMessage());
        }
    }

    // ==================== 示範程式 ====================

    /**
     * TPMService 的示範程式進入點。
     *
     * <p>
     * 此示範程式展示 TPMService 的主要功能：
     * </p>
     * <ol>
     * <li>連接到 TPM（實體或模擬器）</li>
     * <li>讀取 RSA Endorsement Key 並顯示資訊</li>
     * <li>取得 EK 指紋（設備識別碼）</li>
     * <li>建立簽署金鑰</li>
     * <li>對測試資料進行簽署</li>
     * <li>使用 TPM 硬體驗證簽章</li>
     * <li>使用 Java Crypto API 驗證簽章（模擬遠端驗證）</li>
     * </ol>
     *
     * <p>
     * <b>執行方式：</b>
     * </p>
     *
     * <pre>{@code
     * java - cp < classpath > org.gslab.tpm.TPMService
     * }</pre>
     *
     * <p>
     * <b>前置需求：</b>
     * </p>
     * <ul>
     * <li>系統有可用的 TPM 晶片，或</li>
     * <li>TPM 模擬器正在 localhost:2321 執行</li>
     * </ul>
     *
     * @param args 命令列參數（未使用）
     */
    public static void main(String[] args) {
        try (TPMService tpmService = new TPMService()) {
            log.info("=== TPMService 示範 ===");
            log.info("Using persistent key: {}", tpmService.isUsingPersistentKey() ? "Yes" : "No");

            // 讀取 EK
            log.info("\n--- 讀取 Endorsement Key ---");
            TPMT_PUBLIC rsaEK = tpmService.readRSAEndorsementKey();
            tpmService.displayEKInfo("RSA EK", rsaEK);

            String fingerprint = tpmService.getEKFingerprint();
            log.info("   EK 指紋: {}", fingerprint);

            // 使用持久化金鑰簽署（不需要呼叫 createSigningKey）
            log.info("\n--- 簽署測試（使用持久化金鑰）---");

            byte[] testData = "Hello TPM!".getBytes();
            byte[] signature = tpmService.sign(testData);
            log.info("Signing successful, signature length: {} bytes", signature.length);

            // 驗證
            boolean verified = tpmService.verify(testData, signature);
            log.info("TPM verification result: {}", verified ? "Success" : "Failed");

            // Java Crypto 驗證
            PublicKey publicKey = tpmService.getSigningPublicKey();
            boolean javaVerified = TPMService.verifyWithJavaCrypto(publicKey, testData, signature);
            log.info("Java Crypto verification result: {}", javaVerified ? "Success" : "Failed");

            // 顯示公鑰資訊（這個公鑰應該與區塊鏈註冊的一致）
            log.info("\n--- 公鑰資訊（應與區塊鏈註冊一致）---");
            String publicKeyBase64 = java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());
            log.info("公鑰 (Base64): {}...", publicKeyBase64.substring(0, Math.min(64, publicKeyBase64.length())));

            log.info("\n🎉 TPMService 示範完成！");

        } catch (Exception e) {
            log.error("❌ 錯誤: {}", e.getMessage(), e);
        }
    }
}
