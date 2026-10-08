package org.gslab.fileProtection.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.config.BlockchainFileProtectManagerConfig;
import org.gslab.fileProtection.config.BlockchainServiceConfig;
import org.gslab.fileProtection.config.DatabaseConfig;
import org.gslab.fileProtection.config.FileProtectionConfig;
import org.gslab.fileProtection.config.RetryConfig;

/**
 * Simplified configuration utility - System properties override application.properties
 * Following Linus principle: Simple is better than complex
 */
@Slf4j
public class ConfigUtil {

    private static final String CONFIG_FILE = "application.properties";
    private static final String SYSTEM_ENV_KEY = "BLOCKCHAIN_MANAGER_ENV";
    private static final String SYSTEM_ENV_PROPERTY = "blockchain.manager.env";
    private static final String DEFAULT_ENV = "dev";
    private static Properties properties = null;
    private static String currentEnvironment = null;

    static {
        loadProperties();
    }

    private ConfigUtil() {}

    /**
     * Get the current environment from system properties or environment variables
     * Priority: 1. System property (-Dblockchain.manager.env=prod)
     * 2. Environment variable (BLOCKCHAIN_MANAGER_ENV=prod)
     * 3. Default environment (dev)
     */
    private static String getCurrentEnvironment() {
        if (currentEnvironment != null) {
            return currentEnvironment;
        }

        // Check system property first (command line -Dapp.env=prod)
        currentEnvironment = System.getProperty(SYSTEM_ENV_PROPERTY);
        if (currentEnvironment != null) {
            log.info("Environment set via system property: {}", currentEnvironment);
            return currentEnvironment;
        }

        // Check environment variable
        currentEnvironment = System.getenv(SYSTEM_ENV_KEY);
        if (currentEnvironment != null) {
            log.info("Environment set via environment variable: {}", currentEnvironment);
            return currentEnvironment;
        }

        // Use default
        currentEnvironment = DEFAULT_ENV;
        log.info("Using default environment: {}", currentEnvironment);
        return currentEnvironment;
    }

    /**
     * Load properties from application.properties file
     * Simplified: Only load default properties, environment handled via system properties
     */
    private static void loadProperties() {
        properties = new Properties();

        // Load default properties file
        try (InputStream inputStream = ConfigUtil.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (inputStream != null) {
                properties.load(inputStream);
                log.info("Loaded configuration from {}", CONFIG_FILE);
            } else {
                log.warn("Configuration file {} not found", CONFIG_FILE);
            }
        } catch (IOException e) {
            log.error("Failed to load configuration file: {}", CONFIG_FILE, e);
        }
    }

    /**
     * Get the current active environment
     */
    public static String getEnvironment() {
        return getCurrentEnvironment();
    }

    /**
     * Set the environment (useful for testing)
     */
    public static void setEnvironment(String environment) {
        if (!environment.equals(currentEnvironment)) {
            currentEnvironment = environment;
            // Reload configuration for new environment
            reload();
        }
    }

    /**
     * Get property value with default fallback
     * Priority: 1. System properties (-D flags)
     * 2. Application properties
     */
    public static String getProperty(String key, String defaultValue) {
        // First check system properties (command line -D flags)
        String systemValue = System.getProperty(key);
        if (systemValue != null) {
            return systemValue;
        }

        // Then check application properties
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Get property value without default
     */
    public static String getProperty(String key) {
        return getProperty(key, null);
    }

    /**
     * Get boolean property value
     */
    public static boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Get integer property value
     */
    public static int getIntProperty(String key, int defaultValue) {
        String value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Get long property value
     */
    public static long getLongProperty(String key, long defaultValue) {
        String value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid long value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Create blockchain service configuration from properties
     */
    public static BlockchainServiceConfig createBlockchainServiceConfig() {
        // Service type and settings
        String serviceTypeStr = getProperty("blockchain.manager.service.type", "mock");
        BlockchainServiceConfig.ServiceType serviceType =
                BlockchainServiceConfig.ServiceType.fromString(serviceTypeStr);
        boolean mockSimulateFailures = getBooleanProperty("blockchain.manager.service.mock.simulate.failures", false);

        // Blockchain connection settings
        String rpcUrl = getProperty("blockchain.manager.rpc.url", BlockchainServiceConfig.LOCAL_RPC_URL);
        String networkId = getProperty("blockchain.manager.network.id", BlockchainServiceConfig.LOCAL_NETWORK_ID);
        String privateKey = getProperty("blockchain.manager.private.key");
        String contractAddress = getProperty("blockchain.manager.contract.address");

        // Use environment variables for sensitive data if not in properties
        if (privateKey == null || privateKey.trim().isEmpty()) {
            privateKey = System.getenv("BLOCKCHAIN_PRIVATE_KEY");
        }

        if (contractAddress == null || contractAddress.trim().isEmpty()) {
            contractAddress = System.getenv("BLOCKCHAIN_CONTRACT_ADDRESS");
        }

        BlockchainServiceConfig config = new BlockchainServiceConfig(
                serviceType, mockSimulateFailures, rpcUrl, networkId, privateKey, contractAddress);

        // Set gas configuration
        long gasPrice = getLongProperty("blockchain.manager.gas.price", BlockchainServiceConfig.DEFAULT_GAS_PRICE);
        long gasLimit = getLongProperty("blockchain.manager.gas.limit", BlockchainServiceConfig.DEFAULT_GAS_LIMIT);

        config.setGasPrice(gasPrice);
        config.setGasLimit(gasLimit);

        // Set Web3j polling configuration
        long pollingInterval = getLongProperty(
                "blockchain.manager.polling.interval.ms", BlockchainServiceConfig.DEFAULT_POLLING_INTERVAL);
        int pollingAttempts =
                getIntProperty("blockchain.manager.polling.attempts", BlockchainServiceConfig.DEFAULT_POLLING_ATTEMPTS);

        config.setPollingInterval(pollingInterval);
        config.setPollingAttempts(pollingAttempts);

        return config;
    }

    /**
     * Get blockchain service type from configuration
     */
    public static String getBlockchainServiceType() {
        return getProperty("blockchain.manager.service.type", "mock");
    }

    public static boolean isMockSimulateFailures() {
        return getBooleanProperty("blockchain.manager.service.mock.simulate.failures", false);
    }

    /**
     * Check if Web3j service should be used
     */
    public static boolean isWeb3jEnabled() {
        return "web3j".equalsIgnoreCase(getBlockchainServiceType());
    }

    /**
     * Get database URL
     */
    public static String getDatabaseUrl() {
        return getProperty("blockchain.manager.database.url", "jdbc:sqlite:blockchain_manager.db");
    }

    /**
     * Get database username and password
     */
    public static String getDatabaseUsername() {
        return getProperty("blockchain.manager.database.username", "user");
    }

    /**
     * Get database password
     */
    public static String getDatabasePassword() {
        return getProperty("blockchain.manager.database.password", "password");
    }

    /**
     * Reload properties from file
     */
    public static void reload() {
        // Clear current environment to force re-evaluation
        currentEnvironment = null;
        loadProperties();
        log.info("Configuration reloaded");
    }

    /**
     * Create unified BlockchainManagerConfig from properties This is the main factory method for creating the unified
     * configuration
     */
    public static BlockchainFileProtectManagerConfig createBlockchainManagerConfig() {
        return BlockchainFileProtectManagerConfig.builder()
                .withBlockchainServiceConfig(createBlockchainServiceConfig())
                .withDatabaseConfig(createDatabaseConfig())
                .withRetryConfig(createRetryConfig())
                .withFileProtectionConfig(createFileProtectionConfig())
                .build();
    }

    /**
     * Create database configuration from properties
     */
    public static DatabaseConfig createDatabaseConfig() {
        String url = getProperty("blockchain.manager.database.url", "jdbc:sqlite:blockchain_manager.db");
        String username = getProperty("blockchain.manager.database.username", "user");
        String password = getProperty("blockchain.manager.database.password", "password");

        return new DatabaseConfig(url, username, password);
    }

    /**
     * Create retry configuration from properties
     */
    public static RetryConfig createRetryConfig() {
        int maxAttempts = getIntProperty("blockchain.manager.retry.max.attempts", 3);
        int baseDelayMs = getIntProperty("blockchain.manager.retry.base.delay.ms", 500);
        int asyncMaxAttempts = getIntProperty("blockchain.manager.retry.async.max.attempts", 10);
        int asyncBaseDelayMs = getIntProperty("blockchain.manager.retry.async.base.delay.ms", 1000);

        return new RetryConfig(maxAttempts, baseDelayMs, asyncMaxAttempts, asyncBaseDelayMs);
    }

    /**
     * Create file protection configuration from properties
     */
    public static FileProtectionConfig createFileProtectionConfig() {
        boolean verifyOnUpload = getBooleanProperty("blockchain.manager.file.protection.verify.on.upload", true);
        boolean backupEnabled = getBooleanProperty("blockchain.manager.file.protection.backup.enabled", false);
        String backupInterval = getProperty("blockchain.manager.file.protection.backup.interval", "24h");
        boolean enableDatabaseRetry =
                getBooleanProperty("blockchain.manager.file.protection.enable.database.retry", false);
        boolean enableBlockchainRetry =
                getBooleanProperty("blockchain.manager.file.protection.enable.blockchain.retry", false);

        return new FileProtectionConfig(
                verifyOnUpload, backupEnabled, backupInterval, enableDatabaseRetry, enableBlockchainRetry);
    }

    /**
     * Get all properties for debugging
     */
    public static Properties getAllProperties() {
        return new Properties(properties);
    }
}
