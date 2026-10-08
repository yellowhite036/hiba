package org.gslab.fileProtection;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.gslab.configuration.TPMDeviceRegistryConfig;
import org.gslab.fileProtection.config.BlockchainServiceConfig;
import org.gslab.fileProtection.config.BlockchainServiceConfig.ServiceType;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.service.factory.BlockchainServiceFactory;
import org.gslab.fileProtection.service.impl.Web3jBlockchainServiceImpl;
import org.gslab.tpm.blockchain.Web3BlockchainService;

/**
 * FileMetadata 測試區塊鏈整合支援
 *
 * <p>
 * 提供 Mock 和真實區塊鏈兩種模式的檔案與設備註冊表實作。
 * 透過系統屬性 {@code tpm.test.use.blockchain} 切換模式。
 * </p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // Mock 模式（預設）
 * mvn test -Dtest=FileMetadataIntegrityTest
 *
 * // 真實區塊鏈模式
 * mvn test -Dtest=FileMetadataIntegrityTest -Dtpm.test.use.blockchain=true
 * }</pre>
 */
public class FileMetadataTestBlockchainSupport {

    /**
     * 系統屬性：是否使用真實區塊鏈（預設 false，使用 Mock）
     */
    public static final boolean USE_BLOCKCHAIN = Boolean.getBoolean("tpm.test.use.blockchain");

    /**
     * 檔案區塊鏈註冊表介面
     */
    public interface FileBlockchainRegistry extends AutoCloseable {
        void registerFile(FileMetadata metadata);

        Optional<FileMetadata> getFile(String fileHash);

        boolean exists(String fileHash);

        void clear();

        @Override
        default void close() {
            // 預設無操作
        }
    }

    /**
     * 設備註冊表介面
     */
    public interface DeviceRegistry extends AutoCloseable {
        void registerDevice(String deviceId);

        boolean isDeviceRegistered(String deviceId);

        void clear();

        @Override
        default void close() {
            // 預設無操作
        }
    }

    /**
     * Mock 檔案區塊鏈註冊表（預設）
     */
    public static class MockFileBlockchainRegistry implements FileBlockchainRegistry {
        private final Map<String, FileMetadata> registeredFiles = new HashMap<>();

        @Override
        public void registerFile(FileMetadata metadata) {
            registeredFiles.put(metadata.getFileHash(), metadata);
        }

        @Override
        public Optional<FileMetadata> getFile(String fileHash) {
            return Optional.ofNullable(registeredFiles.get(fileHash));
        }

        @Override
        public boolean exists(String fileHash) {
            return registeredFiles.containsKey(fileHash);
        }

        @Override
        public void clear() {
            registeredFiles.clear();
        }
    }

    /**
     * Mock 設備註冊表（預設）
     */
    public static class MockDeviceRegistry implements DeviceRegistry {
        private final Map<String, Boolean> registeredDevices = new HashMap<>();

        @Override
        public void registerDevice(String deviceId) {
            registeredDevices.put(deviceId, true);
        }

        @Override
        public boolean isDeviceRegistered(String deviceId) {
            return registeredDevices.getOrDefault(deviceId, false);
        }

        @Override
        public void clear() {
            registeredDevices.clear();
        }
    }

    /**
     * 真實檔案區塊鏈註冊表
     */
    public static class RealFileBlockchainRegistry implements FileBlockchainRegistry {
        private final Web3jBlockchainServiceImpl blockchainService;

        public RealFileBlockchainRegistry(Web3jBlockchainServiceImpl blockchainService) {
            this.blockchainService = blockchainService;
        }

        @Override
        public void registerFile(FileMetadata metadata) {
            try {
                blockchainService.uploadMetadata(metadata);
            } catch (Exception e) {
                throw new RuntimeException("Failed to upload metadata: " + e.getMessage(), e);
            }
        }

        @Override
        public Optional<FileMetadata> getFile(String fileHash) {
            try {
                return Optional.ofNullable(blockchainService.retrieveMetadataByFileHash(fileHash));
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean exists(String fileHash) {
            return blockchainService.exists(fileHash);
        }

        @Override
        public void clear() {
            // 區塊鏈資料不可清除
            System.out.println("[警告] 真實區塊鏈模式不支援 clear() 操作");
        }

        @Override
        public void close() {
            if (blockchainService != null) {
                blockchainService.close();
            }
        }
    }

    /**
     * 真實設備註冊表（透過 TPM 區塊鏈服務）
     */
    public static class RealDeviceRegistry implements DeviceRegistry {
        private final Web3BlockchainService tpmBlockchainService;

        public RealDeviceRegistry(Web3BlockchainService tpmBlockchainService) {
            this.tpmBlockchainService = tpmBlockchainService;
        }

        @Override
        public void registerDevice(String deviceId) {
            // 真實區塊鏈模式下，設備應已在鏈上註冊
            System.out.println("[資訊] 真實區塊鏈模式：設備 " + deviceId + " 應已在鏈上註冊");
        }

        @Override
        public boolean isDeviceRegistered(String deviceId) {
            return tpmBlockchainService.getPublicKey(deviceId).isPresent();
        }

        @Override
        public void clear() {
            // 區塊鏈資料不可清除
        }

        @Override
        public void close() {
            if (tpmBlockchainService != null) {
                tpmBlockchainService.close();
            }
        }
    }

    /**
     * 建立檔案區塊鏈註冊表
     */
    public static FileBlockchainRegistry createFileRegistry() {
        if (USE_BLOCKCHAIN) {
            return createRealFileRegistry();
        } else {
            System.out.println("[設定] 使用 Mock 檔案區塊鏈模式");
            return new MockFileBlockchainRegistry();
        }
    }

    /**
     * 建立設備註冊表
     */
    public static DeviceRegistry createDeviceRegistry() {
        if (USE_BLOCKCHAIN) {
            return createRealDeviceRegistry();
        } else {
            System.out.println("[設定] 使用 Mock 設備註冊表模式");
            return new MockDeviceRegistry();
        }
    }

    /**
     * 建立真實檔案區塊鏈註冊表
     */
    private static FileBlockchainRegistry createRealFileRegistry() {
        System.out.println("[設定] 使用真實檔案區塊鏈模式");
        try {
            // 重新載入配置確保最新
            org.gslab.fileProtection.util.ConfigUtil.reload();

            // 使用 ConfigUtil 從 application.properties 讀取配置
            // 包括 blockchain.manager.rpc.url, blockchain.manager.contract.address, blockchain.manager.private.key
            BlockchainServiceConfig config = org.gslab.fileProtection.util.ConfigUtil.createBlockchainServiceConfig();
            config.setType(ServiceType.WEB3J);

            System.out.println("   RPC URL: " + config.getRpcUrl());
            System.out.println("   合約地址: " + config.getContractAddress());
            System.out.println("   私鑰存在: "
                    + (config.getPrivateKey() != null && !config.getPrivateKey().isEmpty()));

            Web3jBlockchainServiceImpl service =
                    (Web3jBlockchainServiceImpl) BlockchainServiceFactory.createService(ServiceType.WEB3J, config);

            System.out.println("   區塊鏈連線成功");

            return new RealFileBlockchainRegistry(service);
        } catch (Exception e) {
            System.err.println("[警告] 檔案區塊鏈連線失敗，回退到 Mock 模式: " + e.getMessage());
            return new MockFileBlockchainRegistry();
        }
    }

    /**
     * 建立真實設備註冊表
     */
    private static DeviceRegistry createRealDeviceRegistry() {
        System.out.println("[設定] 使用真實設備區塊鏈模式");
        try {
            TPMDeviceRegistryConfig config = TPMDeviceRegistryConfig.load();
            Web3BlockchainService service = config.createService();

            System.out.println("   TPM 設備區塊鏈連線成功");

            return new RealDeviceRegistry(service);
        } catch (Exception e) {
            System.err.println("[警告] 設備區塊鏈連線失敗，回退到 Mock 模式: " + e.getMessage());
            return new MockDeviceRegistry();
        }
    }
}
