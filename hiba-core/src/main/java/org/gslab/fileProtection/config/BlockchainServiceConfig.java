package org.gslab.fileProtection.config;

/**
 * Unified blockchain service configuration settings
 * Following Linus principle: One configuration for one service - no artificial separation
 */
public class BlockchainServiceConfig {

    public enum ServiceType {
        MOCK("mock"),
        WEB3J("web3j");

        private final String value;

        ServiceType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static ServiceType fromString(String value) {
            for (ServiceType type : ServiceType.values()) {
                if (type.value.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            return MOCK; // Default fallback
        }
    }

    // Default configuration for Ethereum testnet (Sepolia)
    public static final String DEFAULT_RPC_URL = "https://sepolia.infura.io/v3/YOUR_PROJECT_ID";
    public static final String DEFAULT_NETWORK_ID = "11155111"; // Sepolia testnet

    // Local development network (Ganache)
    public static final String LOCAL_RPC_URL = "http://127.0.0.1:8545";
    public static final String LOCAL_NETWORK_ID = "31337";

    // Gas settings
    public static final long DEFAULT_GAS_PRICE = 20_000_000_000L; // 20 Gwei
    public static final long DEFAULT_GAS_LIMIT = 6_721_975L;

    // Web3j transaction polling settings
    public static final long DEFAULT_POLLING_INTERVAL = 1000L; // 1 second for mainnet/testnet
    public static final int DEFAULT_POLLING_ATTEMPTS = 40; // 40 attempts = 40s max wait
    public static final long LOCAL_POLLING_INTERVAL = 100L; // 100ms for local networks
    public static final int LOCAL_POLLING_ATTEMPTS = 50; // 50 attempts = 5s max wait

    // Contract deployment settings
    public static final long CONTRACT_GAS_LIMIT = 3_000_000L;

    // Default private key for development (DO NOT use in production)
    public static final String DEFAULT_PRIVATE_KEY =
            "0x0000000000000000000000000000000000000000000000000000000000000000";

    // Service configuration
    private ServiceType type;
    private boolean mockSimulateFailures;

    // Blockchain connection settings (for Web3j service)
    private String rpcUrl;
    private String networkId;
    private String privateKey;
    private String contractAddress;
    private long gasPrice;
    private long gasLimit;
    private long pollingInterval;
    private int pollingAttempts;

    public BlockchainServiceConfig() {
        this.type = ServiceType.MOCK;
        this.mockSimulateFailures = false;
        // Default to local development settings for blockchain
        this.rpcUrl = LOCAL_RPC_URL;
        this.networkId = LOCAL_NETWORK_ID;
        this.gasPrice = DEFAULT_GAS_PRICE;
        this.gasLimit = DEFAULT_GAS_LIMIT;
        this.pollingInterval = LOCAL_POLLING_INTERVAL;
        this.pollingAttempts = LOCAL_POLLING_ATTEMPTS;
    }

    public BlockchainServiceConfig(ServiceType type, boolean mockSimulateFailures) {
        this();
        this.type = type;
        this.mockSimulateFailures = mockSimulateFailures;
    }

    public BlockchainServiceConfig(
            ServiceType type,
            boolean mockSimulateFailures,
            String rpcUrl,
            String networkId,
            String privateKey,
            String contractAddress) {
        this.type = type;
        this.mockSimulateFailures = mockSimulateFailures;
        this.rpcUrl = rpcUrl;
        this.networkId = networkId;
        this.privateKey = privateKey;
        this.contractAddress = contractAddress;
        this.gasPrice = DEFAULT_GAS_PRICE;
        this.gasLimit = DEFAULT_GAS_LIMIT;
        // Use appropriate polling settings based on network type
        if (LOCAL_NETWORK_ID.equals(networkId)) {
            this.pollingInterval = LOCAL_POLLING_INTERVAL;
            this.pollingAttempts = LOCAL_POLLING_ATTEMPTS;
        } else {
            this.pollingInterval = DEFAULT_POLLING_INTERVAL;
            this.pollingAttempts = DEFAULT_POLLING_ATTEMPTS;
        }
    }

    public ServiceType getType() {
        return type;
    }

    public void setType(ServiceType type) {
        this.type = type;
    }

    public boolean isMockSimulateFailures() {
        return mockSimulateFailures;
    }

    public void setMockSimulateFailures(boolean mockSimulateFailures) {
        this.mockSimulateFailures = mockSimulateFailures;
    }

    public boolean isWeb3jEnabled() {
        return type == ServiceType.WEB3J;
    }

    // Blockchain connection getters and setters
    public String getRpcUrl() {
        return rpcUrl;
    }

    public void setRpcUrl(String rpcUrl) {
        this.rpcUrl = rpcUrl;
    }

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getContractAddress() {
        return contractAddress;
    }

    public void setContractAddress(String contractAddress) {
        this.contractAddress = contractAddress;
    }

    public long getGasPrice() {
        return gasPrice;
    }

    public void setGasPrice(long gasPrice) {
        this.gasPrice = gasPrice;
    }

    public long getGasLimit() {
        return gasLimit;
    }

    public void setGasLimit(long gasLimit) {
        this.gasLimit = gasLimit;
    }

    public long getPollingInterval() {
        return pollingInterval;
    }

    public void setPollingInterval(long pollingInterval) {
        this.pollingInterval = pollingInterval;
    }

    public int getPollingAttempts() {
        return pollingAttempts;
    }

    public void setPollingAttempts(int pollingAttempts) {
        this.pollingAttempts = pollingAttempts;
    }

    /**
     * Create configuration for local development
     */
    public static BlockchainServiceConfig createLocalConfig(String privateKey, String contractAddress) {
        BlockchainServiceConfig config = new BlockchainServiceConfig();
        config.setType(ServiceType.WEB3J);
        config.setRpcUrl(LOCAL_RPC_URL);
        config.setNetworkId(LOCAL_NETWORK_ID);
        config.setPrivateKey(privateKey);
        config.setContractAddress(contractAddress);
        return config;
    }

    /**
     * Create configuration for Sepolia testnet
     */
    public static BlockchainServiceConfig createSepoliaConfig(
            String projectId, String privateKey, String contractAddress) {
        String rpcUrl = DEFAULT_RPC_URL.replace("YOUR_PROJECT_ID", projectId);
        return new BlockchainServiceConfig(
                ServiceType.WEB3J, false, rpcUrl, DEFAULT_NETWORK_ID, privateKey, contractAddress);
    }

    @Override
    public String toString() {
        return "BlockchainServiceConfig{" + "type="
                + type + ", mockSimulateFailures="
                + mockSimulateFailures + ", rpcUrl='"
                + rpcUrl + '\'' + ", networkId='"
                + networkId + '\'' + ", contractAddress='"
                + contractAddress + '\'' + ", gasPrice="
                + gasPrice + ", gasLimit="
                + gasLimit + '}';
    }
}
