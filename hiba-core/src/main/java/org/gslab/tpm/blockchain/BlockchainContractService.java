package org.gslab.tpm.blockchain;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

/**
 * 區塊鏈智慧合約模擬服務
 * <p>
 * 模擬區塊鏈上的智慧合約，維護 EK Fingerprint 與 PublicKey 的對應關係。
 * 在實際應用中，這會是與區塊鏈互動的服務。
 * </p>
 *
 * <h2>功能說明</h2>
 * <ul>
 * <li>註冊設備：將 EK Fingerprint 與 PublicKey 的對應關係寫入區塊鏈</li>
 * <li>查詢公鑰：根據 EK Fingerprint 從區塊鏈取得已註冊的 PublicKey</li>
 * <li>驗證設備：確認設備是否已在區塊鏈上註冊</li>
 * </ul>
 *
 * <h2>注意事項</h2>
 * <p>
 * 這是一個模擬實作，實際應用時應替換為真正的區塊鏈互動邏輯，例如：
 * </p>
 * <ul>
 * <li>使用 Web3j 與以太坊智慧合約互動</li>
 * <li>使用 Hyperledger Fabric SDK</li>
 * <li>使用其他區塊鏈 SDK</li>
 * </ul>
 *
 * @author TPM Test Project
 */
@Slf4j
public class BlockchainContractService {

    private static final String KEY_ALGORITHM = "RSA";

    /**
     * 模擬區塊鏈上的 Map<EKFingerprint, PublicKey>
     * 在實際應用中，這會是智慧合約的狀態
     */
    private final ConcurrentMap<String, String> deviceRegistry = new ConcurrentHashMap<>();

    /**
     * 單例實例
     */
    private static volatile BlockchainContractService instance;

    private BlockchainContractService() {
        log.info("Initializing blockchain smart contract service (simulation)");
    }

    /**
     * 取得單例實例
     */
    public static BlockchainContractService getInstance() {
        if (instance == null) {
            synchronized (BlockchainContractService.class) {
                if (instance == null) {
                    instance = new BlockchainContractService();
                }
            }
        }
        return instance;
    }

    /**
     * 註冊設備到區塊鏈
     * <p>
     * 將設備的 EK Fingerprint 與 PublicKey 的對應關係寫入智慧合約。
     * 這個操作在實際應用中會產生一筆區塊鏈交易。
     * </p>
     *
     * @param ekFingerprint TPM EK 指紋（設備唯一識別碼）
     * @param publicKey     設備的公鑰
     * @return 是否註冊成功
     */
    public boolean registerDevice(String ekFingerprint, PublicKey publicKey) {
        log.info("[Blockchain] Registering device to smart contract...");
        log.info("   EK Fingerprint: {}...", truncate(ekFingerprint, 16));

        try {
            // 模擬區塊鏈交易延遲
            simulateBlockchainLatency();

            // 將公鑰編碼為 Base64 字串存儲
            String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            deviceRegistry.put(ekFingerprint, publicKeyBase64);

            log.info("[Blockchain] Device registered successfully");
            log.info("   Transaction confirmed (simulation)");
            log.info("   Currently registered devices: {}", deviceRegistry.size());

            return true;

        } catch (Exception e) {
            log.error("[Blockchain] Device registration failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 從區塊鏈查詢設備公鑰
     * <p>
     * 根據 EK Fingerprint 從智慧合約查詢對應的 PublicKey。
     * 這個操作在實際應用中會呼叫智慧合約的 view 函數。
     * </p>
     *
     * @param ekFingerprint TPM EK 指紋
     * @return 設備的公鑰，如果設備未註冊則返回 Optional.empty()
     */
    public Optional<PublicKey> getPublicKeyByEKFingerprint(String ekFingerprint) {
        log.debug("[Blockchain] Querying device public key...");
        log.debug("   EK Fingerprint: {}...", truncate(ekFingerprint, 16));

        try {
            // 模擬區塊鏈查詢延遲
            simulateBlockchainLatency();

            String publicKeyBase64 = deviceRegistry.get(ekFingerprint);

            if (publicKeyBase64 == null) {
                log.warn("[Blockchain] Device not registered: {}...", truncate(ekFingerprint, 16));
                return Optional.empty();
            }

            // 還原公鑰
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            log.debug("[Blockchain] Query successful, found registered public key");

            return Optional.of(publicKey);

        } catch (Exception e) {
            log.error("[Blockchain] Query failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 檢查設備是否已註冊
     *
     * @param ekFingerprint TPM EK 指紋
     * @return 是否已註冊
     */
    public boolean isDeviceRegistered(String ekFingerprint) {
        return deviceRegistry.containsKey(ekFingerprint);
    }

    /**
     * 取得已註冊設備數量
     */
    public int getRegisteredDeviceCount() {
        return deviceRegistry.size();
    }

    /**
     * 移除設備註冊（用於測試或設備撤銷）
     *
     * @param ekFingerprint TPM EK 指紋
     * @return 是否移除成功
     */
    public boolean revokeDevice(String ekFingerprint) {
        log.info("[Blockchain] Revoking device registration...");
        log.info("   EK Fingerprint: {}...", truncate(ekFingerprint, 16));

        try {
            simulateBlockchainLatency();

            String removed = deviceRegistry.remove(ekFingerprint);
            if (removed != null) {
                log.info("[Blockchain] Device revoked successfully");
                return true;
            } else {
                log.warn("[Blockchain] Device does not exist");
                return false;
            }

        } catch (Exception e) {
            log.error("[Blockchain] Revocation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 模擬區塊鏈操作延遲
     */
    private void simulateBlockchainLatency() {
        try {
            // 模擬網路延遲 50-150ms
            Thread.sleep(50 + ThreadLocalRandom.current().nextInt(100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 截斷字串
     */
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "null";
        }
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
    }

    /**
     * 清空所有註冊（僅用於測試）
     */
    public void clearAllRegistrations() {
        log.warn("[Blockchain] Clearing all device registrations (for testing only)");
        deviceRegistry.clear();
    }
}
