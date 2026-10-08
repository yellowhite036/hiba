package org.gslab.tpm.blockchain;

import io.reactivex.disposables.Disposable;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.gslab.tpm.PublicKeyCacheService;
import org.gslab.tpm.blockchain.contract.TPMDeviceRegistry;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.StaticGasProvider;

/**
 * 區塊鏈服務 - 與 TPMDeviceRegistry 智慧合約互動
 * <p>
 * 提供與以太坊區塊鏈上的 TPMDeviceRegistry 智慧合約互動的功能，
 * 包括設備註冊、查詢、撤銷以及事件監聽等。
 * </p>
 *
 * <h2>使用範例</h2>
 *
 * <pre>{@code
 * // 建立服務實例
 * Web3BlockchainService service = new Web3BlockchainService.Builder()
 *         .rpcUrl("http://localhost:8545")
 *         .contractAddress("0x...")
 *         .privateKey("0x...")
 *         .build();
 *
 * // 註冊設備
 * service.registerDevice(ekFingerprint, publicKeyBase64);
 *
 * // 查詢公鑰
 * Optional<PublicKey> publicKey = service.getPublicKey(ekFingerprint);
 *
 * // 啟動事件監聽
 * service.startEventListeners();
 *
 * // 關閉服務
 * service.close();
 * }</pre>
 *
 * @author TPM Test Project
 */
@Slf4j
public class Web3BlockchainService implements AutoCloseable {

    private static final String KEY_ALGORITHM = "RSA";

    // 預設 Gas 設定
    private static final BigInteger DEFAULT_GAS_PRICE = BigInteger.valueOf(20_000_000_000L); // 20 Gwei
    private static final BigInteger DEFAULT_GAS_LIMIT = BigInteger.valueOf(500_000L);

    private final Web3j web3j;
    private final TPMDeviceRegistry contract;
    private final PublicKeyCacheService cacheService;

    // 事件訂閱
    private Disposable deviceRegisteredSubscription;
    private Disposable deviceRevokedSubscription;
    private Disposable deviceReactivatedSubscription;

    private volatile boolean isListening = false;

    // ============ 建構函數 ============

    private Web3BlockchainService(Web3j web3j, TPMDeviceRegistry contract, PublicKeyCacheService cacheService) {
        this.web3j = web3j;
        this.contract = contract;
        this.cacheService = cacheService;
        log.debug("Web3 blockchain service initialized");
        log.debug("   Contract address: {}", contract.getContractAddress());

        // TODO 加入區塊鏈智慧合約事件監聽機制
        // startEventListeners();
    }

    // ============ Builder 模式 ============

    /**
     * Builder 用於建立 Web3BlockchainService 實例
     */
    public static class Builder {
        private String rpcUrl = "http://localhost:8545";
        private String networkId = "1";
        private String contractAddress;
        private String privateKey;
        private BigInteger gasPrice = DEFAULT_GAS_PRICE;
        private BigInteger gasLimit = DEFAULT_GAS_LIMIT;
        private int pollingInterval = 1500;
        private int pollingAttempts = 40;
        private PublicKeyCacheService cacheService;

        public Builder rpcUrl(String rpcUrl) {
            this.rpcUrl = rpcUrl;
            return this;
        }

        public Builder networkId(String networkId) {
            this.networkId = networkId;
            return this;
        }

        public Builder contractAddress(String contractAddress) {
            this.contractAddress = contractAddress;
            return this;
        }

        public Builder privateKey(String privateKey) {
            this.privateKey = privateKey;
            return this;
        }

        public Builder gasPrice(BigInteger gasPrice) {
            this.gasPrice = gasPrice;
            return this;
        }

        public Builder gasLimit(BigInteger gasLimit) {
            this.gasLimit = gasLimit;
            return this;
        }

        public Builder pollingInterval(int pollingInterval) {
            this.pollingInterval = pollingInterval;
            return this;
        }

        public Builder pollingAttempts(int pollingAttempts) {
            this.pollingAttempts = pollingAttempts;
            return this;
        }

        public Builder cacheService(PublicKeyCacheService cacheService) {
            // 可選：設定自訂的快取服務
            this.cacheService = cacheService;
            return this;
        }

        public Web3BlockchainService build() {
            if (contractAddress == null || contractAddress.isEmpty()) {
                throw new IllegalArgumentException("Contract address is required");
            }
            if (privateKey == null || privateKey.isEmpty()) {
                throw new IllegalArgumentException("Private key is required");
            }

            Web3j web3j = Web3j.build(new HttpService(rpcUrl));
            Credentials credentials = Credentials.create(privateKey);
            ContractGasProvider gasProvider = new StaticGasProvider(gasPrice, gasLimit);
            TransactionManager txManager = new RawTransactionManager(
                    web3j, credentials, Long.parseLong(networkId), pollingAttempts, pollingInterval);

            TPMDeviceRegistry contract = TPMDeviceRegistry.load(contractAddress, web3j, txManager, gasProvider);

            return new Web3BlockchainService(web3j, contract, cacheService);
        }
    }

    // ============ 設備註冊功能 ============

    /**
     * 註冊設備到區塊鏈
     *
     * @param ekFingerprint TPM EK 指紋
     * @param publicKey     設備公鑰
     * @return 交易回執
     */
    public TransactionReceipt registerDevice(String ekFingerprint, PublicKey publicKey) throws Exception {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return registerDevice(ekFingerprint, publicKeyBase64);
    }

    /**
     * 註冊設備到區塊鏈
     *
     * @param ekFingerprint   TPM EK 指紋
     * @param publicKeyBase64 設備公鑰（Base64 編碼）
     * @return 交易回執
     */
    public TransactionReceipt registerDevice(String ekFingerprint, String publicKeyBase64) throws Exception {
        log.debug("[Blockchain] Registering device...");
        log.debug("   EK Fingerprint: {}...", truncate(ekFingerprint, 16));

        TransactionReceipt receipt =
                contract.registerDevice(ekFingerprint, publicKeyBase64).send();

        if (receipt.isStatusOK()) {
            log.debug("[Blockchain] Device registered successfully");
            log.debug("   Transaction hash: {}", receipt.getTransactionHash());
            log.debug("   Block number: {}", receipt.getBlockNumber());
            log.debug("   Gas used: {}", receipt.getGasUsed());

            // 更新快取
            if (cacheService != null) {
                PublicKey publicKey = decodePublicKey(publicKeyBase64);
                cacheService.put(ekFingerprint, publicKey);
            }
        } else {
            log.error("[Blockchain] Device registration failed");
            log.error("   Transaction hash: {}", receipt.getTransactionHash());
        }

        return receipt;
    }

    /**
     * 註冊設備到區塊鏈（如果尚未註冊）
     * <p>
     * 此方法會先檢查設備是否已註冊，避免重複註冊導致的交易失敗。
     * 適合用於伺服器啟動時的設備註冊。
     * </p>
     *
     * @param ekFingerprint   TPM EK 指紋
     * @param publicKeyBase64 設備公鑰（Base64 編碼）
     * @return 交易回執（若設備已存在則返回 null）
     */
    public TransactionReceipt registerDeviceIfNotExists(String ekFingerprint, String publicKeyBase64) throws Exception {
        log.debug("[Blockchain] Checking and registering device...");
        log.debug("   EK Fingerprint: {}...", truncate(ekFingerprint, 16));

        // 先檢查設備是否已註冊
        if (isDeviceRegistered(ekFingerprint)) {
            log.debug("[Blockchain] Device already registered, skipping registration");

            // 檢查設備是否為啟用狀態
            if (!isDeviceActive(ekFingerprint)) {
                log.debug("[Blockchain] Device was revoked, reactivating...");
                return reactivateDevice(ekFingerprint);
            }

            // 更新快取（確保快取是最新的）
            if (cacheService != null) {
                try {
                    PublicKey publicKey = decodePublicKey(publicKeyBase64);
                    cacheService.put(ekFingerprint, publicKey);
                } catch (Exception e) {
                    log.warn("Failed to update cache: {}", e.getMessage());
                }
            }

            return null; // 設備已存在，無需註冊
        }

        // 設備未註冊，執行註冊
        return registerDevice(ekFingerprint, publicKeyBase64);
    }

    /**
     * 註冊設備到區塊鏈（如果尚未註冊）
     *
     * @param ekFingerprint TPM EK 指紋
     * @param publicKey     設備公鑰
     * @return 交易回執（若設備已存在則返回 null）
     */
    public TransactionReceipt registerDeviceIfNotExists(String ekFingerprint, PublicKey publicKey) throws Exception {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return registerDeviceIfNotExists(ekFingerprint, publicKeyBase64);
    }

    /**
     * 非同步註冊設備
     */
    public CompletableFuture<TransactionReceipt> registerDeviceAsync(String ekFingerprint, String publicKeyBase64) {
        return contract.registerDevice(ekFingerprint, publicKeyBase64)
                .sendAsync()
                .whenComplete((receipt, error) -> {
                    if (error != null) {
                        log.error("[Blockchain] Async registration failed: {}", error.getMessage());
                    } else if (receipt.isStatusOK()) {
                        log.debug("[Blockchain] Async registration successful: {}", receipt.getTransactionHash());
                    }
                });
    }

    /**
     * 註冊設備（含元資料）
     */
    public TransactionReceipt registerDeviceWithMetadata(String ekFingerprint, String publicKeyBase64, String metadata)
            throws Exception {
        log.debug("[Blockchain] Registering device (with metadata)...");

        TransactionReceipt receipt = contract.registerDeviceWithMetadata(ekFingerprint, publicKeyBase64, metadata)
                .send();

        if (receipt.isStatusOK()) {
            log.debug("[Blockchain] Device registered successfully (with metadata)");
            log.debug("   Transaction hash: {}", receipt.getTransactionHash());
        }

        return receipt;
    }

    // ============ 設備查詢功能 ============

    /**
     * 從快取或區塊鏈查詢設備公鑰（優先使用快取）
     * <p>
     * 這是推薦的查詢方法，會先從快取查詢，快取未命中時才查詢區塊鏈。
     * </p>
     *
     * @param ekFingerprint TPM EK 指紋
     * @return 設備公鑰
     */
    public Optional<PublicKey> getPublicKeyWithCache(String ekFingerprint) {
        // 若快取服務可用，先嘗試從快取取得
        if (cacheService != null) {
            Optional<PublicKey> cachedKey = cacheService.get(ekFingerprint);
            if (cachedKey.isPresent()) {
                return cachedKey;
            }
        }

        // 快取未命中，從區塊鏈查詢並更新快取
        Optional<PublicKey> publicKey = getPublicKey(ekFingerprint);
        publicKey.ifPresent(key -> cacheService.put(ekFingerprint, key));
        return publicKey;
    }

    /**
     * 從區塊鏈查詢設備公鑰（不使用快取）
     *
     * @param ekFingerprint TPM EK 指紋
     * @return 設備公鑰
     */
    public Optional<PublicKey> getPublicKey(String ekFingerprint) {
        log.debug("[Blockchain] Querying device public key...");
        log.debug("   EK Fingerprint: {}...", truncate(ekFingerprint, 16));

        try {
            String publicKeyBase64 = contract.getPublicKey(ekFingerprint).send();

            if (publicKeyBase64 == null || publicKeyBase64.isEmpty()) {
                log.warn("[Blockchain] Device not registered or revoked: {}...", truncate(ekFingerprint, 16));
                return Optional.empty();
            }

            PublicKey publicKey = decodePublicKey(publicKeyBase64);
            log.debug("[Blockchain] Query successful");

            return Optional.of(publicKey);

        } catch (Exception e) {
            log.error("[Blockchain] Query failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 檢查設備是否已註冊
     */
    public boolean isDeviceRegistered(String ekFingerprint) {
        try {
            return contract.isDeviceRegistered(ekFingerprint).send();
        } catch (Exception e) {
            log.error("[Blockchain] Failed to check device registration status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 檢查設備是否啟用中
     */
    public boolean isDeviceActive(String ekFingerprint) {
        try {
            return contract.isDeviceActive(ekFingerprint).send();
        } catch (Exception e) {
            log.error("[Blockchain] Failed to check device active status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 取得已註冊設備總數
     */
    public long getDeviceCount() {
        try {
            return contract.getDeviceCount().send().longValue();
        } catch (Exception e) {
            log.error("[Blockchain] Failed to get total device count: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 取得啟用中設備數量
     */
    public long getActiveDeviceCount() {
        try {
            return contract.getActiveDeviceCount().send().longValue();
        } catch (Exception e) {
            log.error("[Blockchain] Failed to get active device count: {}", e.getMessage());
            return 0;
        }
    }

    // ============ 設備管理功能 ============

    /**
     * 撤銷設備
     */
    public TransactionReceipt revokeDevice(String ekFingerprint) throws Exception {
        log.debug("[Blockchain] Revoking device...");
        log.debug("   EK Fingerprint: {}...", truncate(ekFingerprint, 16));

        TransactionReceipt receipt = contract.revokeDevice(ekFingerprint).send();

        if (receipt.isStatusOK()) {
            log.debug("[Blockchain] Device revoked successfully");
            log.debug("   Transaction hash: {}", receipt.getTransactionHash());

            // 從快取中移除
            if (cacheService != null) {
                cacheService.invalidate(ekFingerprint);
            }
        } else {
            log.error("[Blockchain] Revocation failed");
        }

        return receipt;
    }

    /**
     * 重新啟用設備
     */
    public TransactionReceipt reactivateDevice(String ekFingerprint) throws Exception {
        log.debug("[Blockchain] Reactivating device...");
        log.debug("   EK Fingerprint: {}...", truncate(ekFingerprint, 16));

        TransactionReceipt receipt = contract.reactivateDevice(ekFingerprint).send();

        if (receipt.isStatusOK()) {
            log.debug("[Blockchain] Device reactivated successfully");
            log.debug("   Transaction hash: {}", receipt.getTransactionHash());
        }

        return receipt;
    }

    /**
     * 更新設備公鑰
     */
    public TransactionReceipt updatePublicKey(String ekFingerprint, String newPublicKeyBase64) throws Exception {
        log.debug("[Blockchain] Updating device public key...");

        TransactionReceipt receipt =
                contract.updatePublicKey(ekFingerprint, newPublicKeyBase64).send();

        if (receipt.isStatusOK()) {
            log.debug("[Blockchain] Public key updated successfully");

            // 更新快取
            if (cacheService != null) {
                PublicKey publicKey = decodePublicKey(newPublicKeyBase64);
                cacheService.put(ekFingerprint, publicKey);
            }
        }

        return receipt;
    }

    // ============ 批次操作 ============

    /**
     * 批次註冊設備
     */
    public TransactionReceipt batchRegisterDevices(List<String> ekFingerprints, List<String> publicKeys)
            throws Exception {
        log.debug("[Blockchain] Batch registering devices: {} devices", ekFingerprints.size());

        TransactionReceipt receipt =
                contract.batchRegisterDevices(ekFingerprints, publicKeys).send();

        if (receipt.isStatusOK()) {
            log.debug("[Blockchain] Batch registration completed");
            log.debug("   Transaction hash: {}", receipt.getTransactionHash());
            log.debug("   Gas used: {}", receipt.getGasUsed());
        }

        return receipt;
    }

    /**
     * 批次撤銷設備
     */
    public TransactionReceipt batchRevokeDevices(List<String> ekFingerprints) throws Exception {
        log.debug("[Blockchain] Batch revoking devices: {} devices", ekFingerprints.size());

        TransactionReceipt receipt = contract.batchRevokeDevices(ekFingerprints).send();

        if (receipt.isStatusOK()) {
            log.debug("[Blockchain] Batch revocation completed");

            // 從快取中移除
            if (cacheService != null) {
                ekFingerprints.forEach(cacheService::invalidate);
            }
        }

        return receipt;
    }

    // ============ 事件監聽 ============

    /**
     * 啟動事件監聽器
     */
    public void startEventListeners() {
        if (isListening) {
            log.warn("Event listeners are already running");
            return;
        }

        log.debug("Starting blockchain event listeners...");
        isListening = true;

        // 監聽設備註冊事件
        deviceRegisteredSubscription = contract.deviceRegisteredEventFlowable(
                        DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST)
                .subscribe(
                        event -> {
                            log.debug("[Event] New device registered");
                            log.debug("   EK Fingerprint: {}...", truncate(event.ekFingerprint, 16));
                            log.debug("   Timestamp: {}", event.timestamp);
                            onDeviceRegistered(event.ekFingerprint);
                        },
                        error -> log.error("Failed to listen for device registration events: {}", error.getMessage()));

        // 監聽設備撤銷事件
        deviceRevokedSubscription = contract.deviceRevokedEventFlowable(
                        DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST)
                .subscribe(
                        event -> {
                            log.debug("[Event] Device revoked");
                            log.debug("   EK Fingerprint: {}...", truncate(event.ekFingerprint, 16));
                            log.debug("   Timestamp: {}", event.timestamp);
                            onDeviceRevoked(event.ekFingerprint);
                        },
                        error -> log.error("Failed to listen for device revocation events: {}", error.getMessage()));

        // 監聽設備重新啟用事件
        deviceReactivatedSubscription = contract.deviceReactivatedEventFlowable(
                        DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST)
                .subscribe(
                        event -> {
                            log.debug("[Event] Device reactivated");
                            log.debug("   EK Fingerprint: {}...", truncate(event.ekFingerprint, 16));
                            log.debug("   Timestamp: {}", event.timestamp);
                            onDeviceReactivated(event.ekFingerprint);
                        },
                        error -> log.error("Failed to listen for device reactivation events: {}", error.getMessage()));

        log.debug("Event listeners started");
    }

    /**
     * 停止事件監聽器
     */
    public void stopEventListeners() {
        log.debug("Stopping blockchain event listeners...");
        isListening = false;

        if (deviceRegisteredSubscription != null && !deviceRegisteredSubscription.isDisposed()) {
            deviceRegisteredSubscription.dispose();
        }
        if (deviceRevokedSubscription != null && !deviceRevokedSubscription.isDisposed()) {
            deviceRevokedSubscription.dispose();
        }
        if (deviceReactivatedSubscription != null && !deviceReactivatedSubscription.isDisposed()) {
            deviceReactivatedSubscription.dispose();
        }

        log.debug("Event listeners stopped");
    }

    // ============ 事件處理回調 ============

    /**
     * 設備註冊事件處理
     */
    protected void onDeviceRegistered(String ekFingerprint) {
        // 可以在這裡預載入公鑰到快取
        if (cacheService != null) {
            getPublicKey(ekFingerprint).ifPresent(publicKey -> cacheService.put(ekFingerprint, publicKey));
        }
    }

    /**
     * 設備撤銷事件處理
     */
    protected void onDeviceRevoked(String ekFingerprint) {
        // 從快取中移除已撤銷的設備
        if (cacheService != null) {
            cacheService.invalidate(ekFingerprint);
        }
    }

    /**
     * 設備重新啟用事件處理
     */
    protected void onDeviceReactivated(String ekFingerprint) {
        // 重新載入公鑰到快取
        if (cacheService != null) {
            getPublicKey(ekFingerprint).ifPresent(publicKey -> cacheService.put(ekFingerprint, publicKey));
        }
    }

    // ============ 工具方法 ============

    /**
     * 解碼 Base64 公鑰
     * <p>
     * 使用 MIME decoder 來處理可能包含換行符的 Base64 字串
     * （例如從區塊鏈讀取的 PEM 格式公鑰）
     * </p>
     */
    private PublicKey decodePublicKey(String publicKeyBase64) throws Exception {
        // 使用 MIME decoder，可以處理包含換行符和空白的 Base64
        byte[] publicKeyBytes = Base64.getMimeDecoder().decode(publicKeyBase64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return keyFactory.generatePublic(keySpec);
    }

    /**
     * 截斷字串
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "null";
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
    }

    /**
     * 取得合約地址
     */
    public String getContractAddress() {
        return contract.getContractAddress();
    }

    /**
     * 檢查是否正在監聽事件
     */
    public boolean isListening() {
        return isListening;
    }

    // ============ Closeable 實作 ============

    @Override
    public void close() {
        log.debug("Closing blockchain service...");
        stopEventListeners();
        web3j.shutdown();
        log.debug("Blockchain service closed");
    }
}
