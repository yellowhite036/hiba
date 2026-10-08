package org.gslab.tpm;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.gslab.configuration.TPMDeviceRegistryConfig;
import org.gslab.tpm.blockchain.Web3BlockchainService;

/**
 * TPM 測試區塊鏈整合支援
 *
 * <p>
 * 提供 Mock 和真實區塊鏈兩種模式的公鑰註冊表實作。
 * 透過系統屬性 {@code tpm.test.use.blockchain} 切換模式。
 * </p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // Mock 模式（預設）
 * mvn test -Dtest=TPMMultiStageVerificationTest
 *
 * // 真實區塊鏈模式
 * mvn test -Dtest=TPMMultiStageVerificationTest -Dtpm.test.use.blockchain=true
 *
 * // 真實區塊鏈模式 + 啟用快取
 * mvn test -Dtest=TPMMultiStageVerificationTest -Dtpm.test.use.blockchain=true -Dtpm.test.use.cache=true
 * }</pre>
 */
public class TPMTestBlockchainSupport {

    /**
     * 系統屬性：是否使用真實區塊鏈（預設 false，使用 Mock）
     */
    public static final boolean USE_BLOCKCHAIN = Boolean.getBoolean("tpm.test.use.blockchain");

    /**
     * 系統屬性：是否使用公鑰快取（預設 false，每次查詢區塊鏈）
     */
    public static final boolean USE_CACHE = Boolean.getBoolean("tpm.test.use.cache");

    /**
     * 公鑰註冊表介面
     */
    public interface PublicKeyRegistry extends AutoCloseable {
        Optional<String> getPublicKey(String deviceId);

        @Override
        default void close() {
            // 預設無操作
        }
    }

    /**
     * Mock 區塊鏈註冊表（預設）
     */
    public static class MockBlockchainRegistry implements PublicKeyRegistry {
        private final Map<String, String> registeredDevices = new HashMap<>();

        public void registerDevice(String deviceId, String publicKey) {
            registeredDevices.put(deviceId, publicKey);
        }

        @Override
        public Optional<String> getPublicKey(String deviceId) {
            return Optional.ofNullable(registeredDevices.get(deviceId));
        }

        public void revokeDevice(String deviceId) {
            registeredDevices.remove(deviceId);
        }
    }

    /**
     * 真實區塊鏈註冊表
     */
    public static class RealBlockchainRegistry implements PublicKeyRegistry {
        private final Web3BlockchainService blockchainService;

        public RealBlockchainRegistry(Web3BlockchainService blockchainService) {
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

        @Override
        public void close() {
            if (blockchainService != null) {
                blockchainService.close();
            }
        }
    }

    /**
     * 建立公鑰註冊表
     *
     * @param deviceId 預設註冊的裝置 ID（僅 Mock 模式使用）
     * @param publicKey 預設註冊的公鑰（僅 Mock 模式使用）
     * @return 公鑰註冊表實例
     */
    public static PublicKeyRegistry createRegistry(String deviceId, String publicKey) {
        if (USE_BLOCKCHAIN) {
            return createRealBlockchainRegistry();
        } else {
            return createMockRegistry(deviceId, publicKey);
        }
    }

    /**
     * 建立 Mock 註冊表
     */
    public static MockBlockchainRegistry createMockRegistry(String deviceId, String publicKey) {
        System.out.println("[設定] 使用 Mock 區塊鏈模式");
        MockBlockchainRegistry registry = new MockBlockchainRegistry();
        registry.registerDevice(deviceId, publicKey);
        return registry;
    }

    /**
     * 建立真實區塊鏈註冊表
     */
    public static PublicKeyRegistry createRealBlockchainRegistry() {
        System.out.println("[設定] 使用真實區塊鏈模式");
        System.out.println("   快取模式: " + (USE_CACHE ? "啟用" : "停用"));
        try {
            TPMDeviceRegistryConfig config = TPMDeviceRegistryConfig.load();

            // 根據 USE_CACHE 決定是否注入快取服務
            Web3BlockchainService service;
            if (USE_CACHE) {
                service = new Web3BlockchainService.Builder()
                        .rpcUrl(config.getRpcUrl())
                        .contractAddress(config.getContractAddress())
                        .privateKey(config.getPrivateKey())
                        .networkId(String.valueOf(config.getChainId()))
                        .gasPrice(config.getGasPrice())
                        .gasLimit(config.getGasLimit())
                        .pollingInterval(config.getPollingIntervalMs())
                        .pollingAttempts(config.getPollingAttempts())
                        .cacheService(new PublicKeyCacheService())
                        .build();
            } else {
                service = config.createService();
            }

            System.out.println("   RPC URL: " + config.getRpcUrl());
            System.out.println("   合約地址: " + config.getContractAddress());
            System.out.println("   區塊鏈連線成功");

            return new RealBlockchainRegistry(service);
        } catch (Exception e) {
            System.err.println("[警告] 區塊鏈連線失敗，回退到 Mock 模式: " + e.getMessage());
            return new MockBlockchainRegistry();
        }
    }
}
