package org.gslab.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.gslab.tpm.blockchain.Web3BlockchainService;

/**
 * 區塊鏈TPM裝置註冊配置類別
 * <p>
 * 提供區塊鏈連線所需的配置參數，支援從以下來源載入（優先順序由高到低）：
 * <ul>
 * <li>1. 環境變數（最高優先，推薦用於生產環境）</li>
 * <li>2. 外部 properties 檔案（專案目錄外）</li>
 * <li>3. classpath 內的 properties 檔案（僅用於開發）</li>
 * <li>4. 程式碼直接設定</li>
 * </ul>
 * </p>
 *
 * <h2>Security Best Practices</h2>
 * <ul>
 * <li>私鑰等機敏資料應使用環境變數傳入</li>
 * <li>不要將 blockchain.properties 提交到版本控制</li>
 * <li>生產環境應使用 Vault、AWS Secrets Manager 等密鑰管理服務</li>
 * </ul>
 *
 * <h2>環境變數</h2>
 *
 * <pre>
 * BLOCKCHAIN_RPC_URL=http://localhost:8545
 * TPM_DEVICE_REGISTRY_CONTRACT_ADDRESS=0x...
 * TPM_DEVICE_REGISTRY_PRIVATE_KEY=0x...
 * BLOCKCHAIN_CHAIN_ID=1337
 * BLOCKCHAIN_GAS_PRICE=20000000000
 * BLOCKCHAIN_GAS_LIMIT=500000
 * </pre>
 *
 * <h2>Properties 檔案 (blockchain.properties)</h2>
 *
 * <pre>
 * blockchain.rpc.url=http://localhost:8545
 * tpm.device.registry.contract.address=0x...
 * tpm.device.registry.private.key=0x...
 * blockchain.chain.id=1337
 * blockchain.gas.price=20000000000
 * blockchain.gas.limit=500000
 * </pre>
 */
@Slf4j
@Getter
@Builder
public class TPMDeviceRegistryConfig {

    private static final String PROPERTIES_FILE = "blockchain.properties";

    // 預設值
    private static final String DEFAULT_RPC_URL = "http://localhost:8545";
    private static final long DEFAULT_CHAIN_ID = 1337L; // Hardhat/Ganache 本地鏈
    private static final BigInteger DEFAULT_GAS_PRICE = BigInteger.valueOf(20_000_000_000L); // 20 Gwei
    private static final BigInteger DEFAULT_GAS_LIMIT = BigInteger.valueOf(500_000L);

    /**
     * 以太坊 RPC URL
     */
    @Builder.Default
    private String rpcUrl = DEFAULT_RPC_URL;

    /**
     * 智慧合約地址
     */
    private String contractAddress;

    /**
     * 帳戶私鑰（用於簽署交易）
     */
    private String privateKey;

    /**
     * 鏈 ID
     */
    @Builder.Default
    private long chainId = DEFAULT_CHAIN_ID;

    /**
     * Gas 價格（Wei）
     */
    @Builder.Default
    private BigInteger gasPrice = DEFAULT_GAS_PRICE;

    /**
     * Gas 限制
     */
    @Builder.Default
    private BigInteger gasLimit = DEFAULT_GAS_LIMIT;

    /**
     * 是否啟用事件監聽
     */
    @Builder.Default
    private boolean enableEventListeners = true;

    /**
     * 網路名稱（用於日誌）
     */
    @Builder.Default
    private String networkName = "localhost";

    /**
     * Polling 間隔（毫秒）
     */
    @Builder.Default
    private int pollingIntervalMs = 1500;

    /**
     * Polling 次數
     */
    @Builder.Default
    private int pollingAttempts = 40;

    /**
     * 從環境變數載入配置（推薦用於生產環境）
     * <p>
     * 環境變數是最安全的配置方式，因為：
     * <ul>
     * <li>不會被提交到版本控制</li>
     * <li>可由容器編排工具（K8s）或 CI/CD 管理</li>
     * <li>支援密鑰管理服務整合</li>
     * </ul>
     */
    public static TPMDeviceRegistryConfig fromEnvironment() {
        log.info("Loading blockchain configuration from environment variables...");

        TPMDeviceRegistryConfigBuilder builder = TPMDeviceRegistryConfig.builder();

        // RPC URL
        String rpcUrl = getEnv("BLOCKCHAIN_RPC_URL", null);
        if (rpcUrl != null) {
            builder.rpcUrl(rpcUrl);
        }

        // 合約地址
        String contractAddress = getEnv("TPM_DEVICE_REGISTRY_CONTRACT_ADDRESS", null);
        if (contractAddress != null) {
            builder.contractAddress(contractAddress);
        }

        // 私鑰
        String privateKey = getEnv("TPM_DEVICE_REGISTRY_PRIVATE_KEY", null);
        if (privateKey != null) {
            builder.privateKey(privateKey);
        }

        // 鏈 ID
        String chainId = getEnv("BLOCKCHAIN_CHAIN_ID", null);
        if (chainId != null) {
            builder.chainId(Long.parseLong(chainId));
        }

        // Gas 價格
        String gasPrice = getEnv("BLOCKCHAIN_GAS_PRICE", null);
        if (gasPrice != null) {
            builder.gasPrice(new BigInteger(gasPrice));
        }

        // Gas 限制
        String gasLimit = getEnv("BLOCKCHAIN_GAS_LIMIT", null);
        if (gasLimit != null) {
            builder.gasLimit(new BigInteger(gasLimit));
        }

        // 網路名稱
        String networkName = getEnv("BLOCKCHAIN_NETWORK_NAME", null);
        if (networkName != null) {
            builder.networkName(networkName);
        }

        TPMDeviceRegistryConfig config = builder.build();
        config.logConfig();
        return config;
    }

    /**
     * 使用分層配置載入（推薦）
     * <p>
     * 優先順序：環境變數 > 外部檔案 > classpath 檔案 > 預設值
     * <p>
     * 這種方式確保：
     * <ul>
     * <li>開發環境可使用 properties 檔案方便測試</li>
     * <li>生產環境透過環境變數覆蓋機敏設定</li>
     * </ul>
     */
    public static TPMDeviceRegistryConfig load() {
        log.info("Loading blockchain configuration using layered approach...");

        // 先從 properties 檔案載入基礎配置
        TPMDeviceRegistryConfig baseConfig = fromPropertiesQuietly();

        // 用環境變數覆蓋機敏設定
        TPMDeviceRegistryConfigBuilder builder = TPMDeviceRegistryConfig.builder()
                .rpcUrl(baseConfig.getRpcUrl())
                .contractAddress(baseConfig.getContractAddress())
                .privateKey(baseConfig.getPrivateKey())
                .chainId(baseConfig.getChainId())
                .gasPrice(baseConfig.getGasPrice())
                .gasLimit(baseConfig.getGasLimit())
                .networkName(baseConfig.getNetworkName())
                .enableEventListeners(baseConfig.isEnableEventListeners());

        // 環境變數覆蓋（機敏資料優先從環境變數讀取）
        String envPrivateKey = getEnv("TPM_DEVICE_REGISTRY_PRIVATE_KEY", null);
        if (envPrivateKey != null) {
            log.info("Using private key from environment variable (secure)");
            builder.privateKey(envPrivateKey);
        }

        String envContractAddress = getEnv("TPM_DEVICE_REGISTRY_CONTRACT_ADDRESS", null);
        if (envContractAddress != null) {
            builder.contractAddress(envContractAddress);
        }

        String envRpcUrl = getEnv("BLOCKCHAIN_RPC_URL", null);
        if (envRpcUrl != null) {
            builder.rpcUrl(envRpcUrl);
        }

        TPMDeviceRegistryConfig config = builder.build();
        config.logConfig();
        config.warnIfInsecure();
        return config;
    }

    /**
     * 靜默載入 properties（不輸出警告）
     */
    private static TPMDeviceRegistryConfig fromPropertiesQuietly() {
        return fromPropertiesQuietly(PROPERTIES_FILE);
    }

    /**
     * 靜默載入 properties（不輸出警告）
     */
    private static TPMDeviceRegistryConfig fromPropertiesQuietly(String propertiesFile) {
        Properties props = new Properties();

        // 嘗試從外部檔案載入（專案根目錄）
        Path externalPath = Paths.get(propertiesFile);
        if (Files.exists(externalPath)) {
            try (InputStream is = Files.newInputStream(externalPath)) {
                props.load(is);
                log.debug("Loaded configuration from external file: {}", externalPath.toAbsolutePath());
            } catch (IOException e) {
                log.debug("Cannot load external configuration file: {}", e.getMessage());
            }
        }

        // 嘗試從 classpath 載入
        if (props.isEmpty()) {
            try (InputStream is = TPMDeviceRegistryConfig.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
                if (is != null) {
                    props.load(is);
                }
            } catch (IOException e) {
                log.debug("Cannot load configuration from classpath: {}", e.getMessage());
            }
        }

        return buildFromProperties(props);
    }

    /**
     * 從 Properties 物件建立配置
     */
    private static TPMDeviceRegistryConfig buildFromProperties(Properties props) {
        TPMDeviceRegistryConfigBuilder builder = TPMDeviceRegistryConfig.builder();

        setIfPresent(props, "blockchain.rpc.url", builder::rpcUrl);
        setIfPresent(props, "tpm.device.registry.contract.address", builder::contractAddress);
        setIfPresent(props, "tpm.device.registry.private.key", builder::privateKey);
        setIfPresent(props, "blockchain.network.name", builder::networkName);

        String chainId = props.getProperty("blockchain.chain.id");
        if (chainId != null && !chainId.isEmpty()) {
            builder.chainId(Long.parseLong(chainId));
        }

        String gasPrice = props.getProperty("blockchain.gas.price");
        if (gasPrice != null && !gasPrice.isEmpty()) {
            builder.gasPrice(new BigInteger(gasPrice));
        }

        String gasLimit = props.getProperty("blockchain.gas.limit");
        if (gasLimit != null && !gasLimit.isEmpty()) {
            builder.gasLimit(new BigInteger(gasLimit));
        }

        String enableListeners = props.getProperty("tpm.device.registry.enable.event.listeners");
        if (enableListeners != null && !enableListeners.isEmpty()) {
            builder.enableEventListeners(Boolean.parseBoolean(enableListeners));
        }

        // Polling 設定
        String pollingInterval = props.getProperty("blockchain.polling.interval.ms");
        if (pollingInterval != null && !pollingInterval.isEmpty()) {
            builder.pollingIntervalMs(Integer.parseInt(pollingInterval));
        }

        String pollingAttempts = props.getProperty("blockchain.polling.attempts");
        if (pollingAttempts != null && !pollingAttempts.isEmpty()) {
            builder.pollingAttempts(Integer.parseInt(pollingAttempts));
        }

        return builder.build();
    }

    /**
     * 輔助方法：如果屬性存在則設定
     */
    private static void setIfPresent(Properties props, String key, Consumer<String> setter) {
        String value = props.getProperty(key);
        if (value != null && !value.isEmpty()) {
            setter.accept(value);
        }
    }

    /**
     * 檢查並警告不安全的配置
     */
    private void warnIfInsecure() {
        if (privateKey != null && !privateKey.isEmpty()) {
            // 檢查私鑰是否來自環境變數
            String envKey = getEnv("TPM_DEVICE_REGISTRY_PRIVATE_KEY", null);
            if (envKey == null || !envKey.equals(privateKey)) {
                log.warn("Security warning: Private key not loaded from environment variable!");
                log.warn("For production, please use TPM_DEVICE_REGISTRY_PRIVATE_KEY environment variable");
            }
        }
    }

    /**
     * 從 properties 檔案載入配置
     */
    public static TPMDeviceRegistryConfig fromProperties() {
        return fromProperties(PROPERTIES_FILE);
    }

    /**
     * 從指定的 properties 檔案載入配置
     */
    public static TPMDeviceRegistryConfig fromProperties(String propertiesFile) {
        log.info("Loading blockchain configuration from properties file: {}", propertiesFile);

        Properties props = new Properties();
        try (InputStream is = TPMDeviceRegistryConfig.class.getClassLoader().getResourceAsStream(propertiesFile)) {
            if (is != null) {
                props.load(is);
            } else {
                log.warn("Properties file not found: {}", propertiesFile);
                return fromEnvironment();
            }
        } catch (IOException e) {
            log.error("Failed to load properties file: {}", e.getMessage());
            return fromEnvironment();
        }

        TPMDeviceRegistryConfigBuilder builder = TPMDeviceRegistryConfig.builder();

        // RPC URL
        String rpcUrl = props.getProperty("blockchain.rpc.url");
        if (rpcUrl != null && !rpcUrl.isEmpty()) {
            builder.rpcUrl(rpcUrl);
        }

        // 合約地址
        String contractAddress = props.getProperty("blockchain.contract.address");
        if (contractAddress != null && !contractAddress.isEmpty()) {
            builder.contractAddress(contractAddress);
        }

        // 私鑰
        String privateKey = props.getProperty("blockchain.private.key");
        if (privateKey != null && !privateKey.isEmpty()) {
            builder.privateKey(privateKey);
        }

        // 鏈 ID
        String chainId = props.getProperty("blockchain.chain.id");
        if (chainId != null && !chainId.isEmpty()) {
            builder.chainId(Long.parseLong(chainId));
        }

        // Gas 價格
        String gasPrice = props.getProperty("blockchain.gas.price");
        if (gasPrice != null && !gasPrice.isEmpty()) {
            builder.gasPrice(new BigInteger(gasPrice));
        }

        // Gas 限制
        String gasLimit = props.getProperty("blockchain.gas.limit");
        if (gasLimit != null && !gasLimit.isEmpty()) {
            builder.gasLimit(new BigInteger(gasLimit));
        }

        // 網路名稱
        String networkName = props.getProperty("blockchain.network.name");
        if (networkName != null && !networkName.isEmpty()) {
            builder.networkName(networkName);
        }

        // 事件監聽
        String enableListeners = props.getProperty("blockchain.enable.event.listeners");
        if (enableListeners != null && !enableListeners.isEmpty()) {
            builder.enableEventListeners(Boolean.parseBoolean(enableListeners));
        }

        TPMDeviceRegistryConfig config = builder.build();
        config.logConfig();
        return config;
    }

    /**
     * 驗證配置是否完整
     */
    public void validate() {
        if (contractAddress == null || contractAddress.isEmpty()) {
            throw new IllegalStateException("Contract address is required");
        }
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalStateException("Private key is required");
        }
        if (!contractAddress.startsWith("0x") || contractAddress.length() != 42) {
            throw new IllegalStateException("Invalid contract address format");
        }
    }

    /**
     * 是否為有效配置
     */
    public boolean isValid() {
        try {
            validate();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * 取得環境變數
     */
    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    /**
     * 記錄配置資訊
     */
    public void logConfig() {
        log.info("=====================================================================");
        log.info("                       Blockchain Configuration                     ");
        log.info("=====================================================================");
        log.info("  Network: {}                                                       ", networkName);
        log.info("  RPC URL: {}                                                       ", rpcUrl);
        log.info("  Contract address: {}                                              ", maskAddress(contractAddress));
        log.info("  Private key: {}                                                   ", maskPrivateKey(privateKey));
        log.info("  Chain ID: {}                                                      ", chainId);
        log.info(
                "  Gas price: {} Gwei                                                ",
                gasPrice.divide(BigInteger.valueOf(1_000_000_000)));
        log.info("  Gas limit: {}                                                     ", gasLimit);
        log.info(
                "  Event listeners: {}                                               ",
                enableEventListeners ? "enabled" : "disabled");
        log.info("=====================================================================");
    }

    /**
     * 遮蔽地址（顯示前後各 6 個字元）
     */
    private String maskAddress(String address) {
        if (address == null || address.length() < 12) {
            return "Not set";
        }
        return address.substring(0, 8) + "..." + address.substring(address.length() - 6);
    }

    /**
     * 遮蔽私鑰（只顯示前 6 個字元）
     */
    private String maskPrivateKey(String privateKey) {
        if (privateKey == null || privateKey.isEmpty()) {
            return "Not set";
        }
        return privateKey.substring(0, Math.min(6, privateKey.length())) + "***";
    }

    /**
     * 建立 Web3BlockchainService
     */
    public Web3BlockchainService createService() {
        validate();
        return new Web3BlockchainService.Builder()
                .rpcUrl(rpcUrl)
                .contractAddress(contractAddress)
                .privateKey(privateKey)
                .networkId(String.valueOf(chainId))
                .gasPrice(gasPrice)
                .gasLimit(gasLimit)
                .pollingInterval(pollingIntervalMs)
                .pollingAttempts(pollingAttempts)
                .build();
    }
}
