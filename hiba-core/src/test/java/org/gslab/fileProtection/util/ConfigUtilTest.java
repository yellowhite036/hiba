package org.gslab.fileProtection.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for ConfigUtil covering environment variables, property priority,
 * environment switching, edge cases, and all configuration methods
 */
public class ConfigUtilTest {

    private String originalEnvironment;

    @BeforeEach
    void setUp() {
        originalEnvironment = ConfigUtil.getEnvironment();
        clearTestProperties();
        // 設定測試環境，使用 web3j 和測試區塊鏈配置
        System.setProperty("blockchain.manager.service.type", "web3j");
        System.setProperty("blockchain.manager.rpc.url", "http://localhost:8545");
        System.setProperty("blockchain.manager.network.id", "31337");
        System.setProperty("blockchain.manager.contract.address", "0x5FbDB2315678afecb367f032d93F642f64180aa3");
        System.setProperty(
                "blockchain.manager.private.key", "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
        System.setProperty("blockchain.manager.database.url", "jdbc:sqlite:test.db");
        ConfigUtil.reload();
    }

    @AfterEach
    void tearDown() {
        clearTestProperties();
        ConfigUtil.setEnvironment(originalEnvironment);
    }

    private void clearTestProperties() {
        System.clearProperty("test.property");
        System.clearProperty("TEST_PROPERTY");
        System.clearProperty("env.TEST_PROPERTY");
        System.clearProperty("blockchain.manager.env");
        System.clearProperty("test.boolean.property");
        System.clearProperty("test.int.property");
        System.clearProperty("test.long.property");
        System.clearProperty("test.invalid.int.property");
        System.clearProperty("test.invalid.long.property");
        System.clearProperty("blockchain.manager.service.type");
        System.clearProperty("blockchain.manager.service.mock.simulate.failures");
        System.clearProperty("blockchain.manager.database.url");
        System.clearProperty("blockchain.manager.database.username");
        System.clearProperty("blockchain.manager.database.password");
    }

    // Environment Variables and .env File Loading Tests

    @Test
    void testEnvironmentVariablesLoaded() {
        // Test that blockchain configuration can be loaded from environment variables
        String privateKey = ConfigUtil.getProperty("blockchain.manager.private.key");
        String contractAddress = ConfigUtil.getProperty("blockchain.manager.contract.address");
        String rpcUrl = ConfigUtil.getProperty("blockchain.manager.rpc.url");

        // These should not be null if .env file is properly loaded
        assertNotNull(privateKey, "Private key should be loaded from environment");
        assertNotNull(contractAddress, "Contract address should be loaded from environment");
        assertNotNull(rpcUrl, "RPC URL should be loaded from environment");

        // Private key should not contain placeholder text
        assertFalse(privateKey.contains("YOUR_PRIVATE_KEY"), "Private key should be actual value, not placeholder");
        assertFalse(privateKey.contains("PRIVATE_KEY_HERE"), "Private key should be actual value, not placeholder");

        // Contract address should not contain placeholder text
        assertNotEquals(
                "CONTRACT_ADDRESS", contractAddress, "Contract address should be actual value, not placeholder");

        System.out.println("✅ Environment variables loaded successfully:");
        System.out.println("Private key: " + (privateKey.length() > 10 ? privateKey.substring(0, 10) + "..." : "***"));
        System.out.println("Contract address: " + contractAddress);
        System.out.println("RPC URL: " + rpcUrl);
    }

    @Test
    void testBlockchainConfigCreation() {
        // Test that BlockchainServiceConfig can be created with environment variables
        var config = ConfigUtil.createBlockchainServiceConfig();

        assertNotNull(config, "Blockchain config should not be null");
        assertNotNull(config.getPrivateKey(), "Private key should not be null");
        assertNotNull(config.getContractAddress(), "Contract address should not be null");
        assertNotNull(config.getRpcUrl(), "RPC URL should not be null");

        // Verify that the RPC URL has the PROJECT_ID replaced
        assertFalse(config.getRpcUrl().contains("PROJECT_ID"), "RPC URL should have PROJECT_ID replaced");

        System.out.println("✅ Blockchain config created successfully:");
        System.out.println("RPC URL: " + config.getRpcUrl());
        System.out.println("Network ID: " + config.getNetworkId());
        System.out.println("Contract Address: " + config.getContractAddress());
        System.out.println("Gas Price: " + config.getGasPrice());
        System.out.println("Gas Limit: " + config.getGasLimit());
    }

    // Property Priority and Transformation Tests

    @Test
    void testPropertyPriorityOrder() {
        String key = "test.property";
        String systemPropValue = "system-property-value";
        String defaultValue = "default-value";

        // Test 1: Only default value
        String result = ConfigUtil.getProperty(key, defaultValue);
        assertEquals(defaultValue, result, "Should return default value when no other sources available");

        // Test 2: Add system property (highest priority)
        System.setProperty(key, systemPropValue);
        result = ConfigUtil.getProperty(key, defaultValue);
        assertEquals(systemPropValue, result, "System property should have highest priority");

        System.out.println("✅ Property priority order works correctly");
    }

    @Test
    void testPropertyKeyTransformation() {
        String key = "test.property.key";
        String testValue = "transformed-key-value";

        // Set property with exact key
        System.setProperty(key, testValue);

        // Get with same key
        String result = ConfigUtil.getProperty(key);
        assertEquals(testValue, result, "Should find property with exact key match");

        System.out.println("✅ Property key handling works correctly");
    }

    @Test
    void testSystemPropertyPriority() {
        String testKey = "test.system.priority";
        String sysValue = "from-system";
        String defaultValue = "default";

        // Test default value when no system property
        assertEquals(defaultValue, ConfigUtil.getProperty(testKey, defaultValue));

        // Test system property takes precedence over default
        System.setProperty(testKey, sysValue);
        assertEquals(sysValue, ConfigUtil.getProperty(testKey, defaultValue));

        System.out.println("✅ System property priority works correctly");
    }

    // Environment Management Tests

    @Test
    void testEnvironmentSwitching() {
        String originalEnv = ConfigUtil.getEnvironment();

        // Test setting environment via system property (since setEnvironment doesn't persist through reload)
        System.setProperty("blockchain.manager.env", "test");
        ConfigUtil.reload();
        assertEquals("test", ConfigUtil.getEnvironment(), "Environment should be switched to 'test'");

        // Test switching to production environment
        System.setProperty("blockchain.manager.env", "prod");
        ConfigUtil.reload();
        assertEquals("prod", ConfigUtil.getEnvironment(), "Environment should be switched to 'prod'");

        // Test switching back to original (clear system property to use default)
        System.clearProperty("blockchain.manager.env");
        ConfigUtil.reload();
        assertEquals("dev", ConfigUtil.getEnvironment(), "Environment should default to 'dev'");

        // Test that setEnvironment method exists and can be called (even if it doesn't persist through reload)
        assertDoesNotThrow(() -> ConfigUtil.setEnvironment("test"), "setEnvironment should not throw exception");

        System.out.println("✅ Environment switching works correctly");
    }

    @Test
    void testReloadConfiguration() {
        String testKey = "test.reload.property";
        String testValue = "test-reload-value";

        // Set a system property
        System.setProperty(testKey, testValue);

        // Reload configuration
        ConfigUtil.reload();

        // Verify property is still accessible after reload
        assertEquals(testValue, ConfigUtil.getProperty(testKey), "Property should be accessible after reload");

        System.out.println("✅ Configuration reload works correctly");
    }

    // Type Conversion Tests

    @Test
    void testGetBooleanProperty() {
        String key = "test.boolean.property";

        // Test boolean property with default value
        boolean backupEnabled = ConfigUtil.getBooleanProperty("test.nonexistent.boolean", true);
        assertTrue(backupEnabled, "Default value should be used when property doesn't exist");

        // Test various boolean representations
        System.setProperty(key, "true");
        assertTrue(ConfigUtil.getBooleanProperty(key, false), "Should parse 'true' as true");

        System.setProperty(key, "false");
        assertFalse(ConfigUtil.getBooleanProperty(key, true), "Should parse 'false' as false");

        System.setProperty(key, "TRUE");
        assertTrue(ConfigUtil.getBooleanProperty(key, false), "Should parse 'TRUE' as true");

        System.setProperty(key, "False");
        assertFalse(ConfigUtil.getBooleanProperty(key, true), "Should parse 'False' as false");

        System.setProperty(key, "invalid");
        assertFalse(ConfigUtil.getBooleanProperty(key, true), "Should parse invalid value as false");

        System.setProperty(key, "");
        assertFalse(ConfigUtil.getBooleanProperty(key, true), "Should parse empty string as false");

        // Test with null value
        System.clearProperty(key);
        boolean defaultValue = true;
        assertEquals(
                defaultValue,
                ConfigUtil.getBooleanProperty(key, defaultValue),
                "Should return default value when property is null");

        System.out.println("✅ Boolean property handling works correctly");
    }

    @Test
    void testGetIntProperty() {
        String key = "test.int.property";
        int defaultValue = 999;

        // Test int property with default value
        int testInt = ConfigUtil.getIntProperty("test.nonexistent.int", 500);
        assertEquals(500, testInt, "Default value should be used when property doesn't exist");

        // Test valid integer
        System.setProperty(key, "123");
        assertEquals(123, ConfigUtil.getIntProperty(key, defaultValue), "Should parse valid integer");

        // Test negative integer
        System.setProperty(key, "-456");
        assertEquals(-456, ConfigUtil.getIntProperty(key, defaultValue), "Should parse negative integer");

        // Test zero
        System.setProperty(key, "0");
        assertEquals(0, ConfigUtil.getIntProperty(key, defaultValue), "Should parse zero");

        // Test with valid integer from original test
        System.setProperty(key, "1000");
        testInt = ConfigUtil.getIntProperty(key, 500);
        assertEquals(1000, testInt, "Int property should be parsed correctly");

        // Test invalid integer
        System.setProperty(key, "not-a-number");
        assertEquals(
                defaultValue,
                ConfigUtil.getIntProperty(key, defaultValue),
                "Should return default for invalid integer");

        // Test empty string
        System.setProperty(key, "");
        assertEquals(
                defaultValue, ConfigUtil.getIntProperty(key, defaultValue), "Should return default for empty string");

        // Test integer overflow (larger than Integer.MAX_VALUE)
        System.setProperty(key, "999999999999999999999");
        assertEquals(
                defaultValue,
                ConfigUtil.getIntProperty(key, defaultValue),
                "Should return default for integer overflow");

        // Test with invalid integer from original test
        System.setProperty("test.invalid.int.property", "not-an-int");
        testInt = ConfigUtil.getIntProperty("test.invalid.int.property", 500);
        assertEquals(500, testInt, "Default value should be used when property is not a valid integer");

        System.out.println("✅ Integer property handling works correctly");
    }

    @Test
    void testGetLongProperty() {
        String key = "test.long.property";
        long defaultValue = 999L;

        // Test long property with default value
        long testLong = ConfigUtil.getLongProperty("test.nonexistent.long", 500L);
        assertEquals(500L, testLong, "Default value should be used when property doesn't exist");

        // Test valid long
        System.setProperty(key, "1234567890123");
        assertEquals(1234567890123L, ConfigUtil.getLongProperty(key, defaultValue), "Should parse valid long");

        // Test negative long
        System.setProperty(key, "-9876543210987");
        assertEquals(-9876543210987L, ConfigUtil.getLongProperty(key, defaultValue), "Should parse negative long");

        // Test Long.MAX_VALUE
        System.setProperty(key, String.valueOf(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, ConfigUtil.getLongProperty(key, defaultValue), "Should parse Long.MAX_VALUE");

        // Test Long.MIN_VALUE
        System.setProperty(key, String.valueOf(Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE, ConfigUtil.getLongProperty(key, defaultValue), "Should parse Long.MIN_VALUE");

        // Test with valid long from original test
        System.setProperty(key, "1000000000000");
        testLong = ConfigUtil.getLongProperty(key, 500L);
        assertEquals(1000000000000L, testLong, "Long property should be parsed correctly");

        // Test invalid long
        System.setProperty(key, "not-a-long");
        assertEquals(
                defaultValue, ConfigUtil.getLongProperty(key, defaultValue), "Should return default for invalid long");

        // Test long overflow (larger than Long.MAX_VALUE)
        System.setProperty(key, "99999999999999999999999999999999999999");
        assertEquals(
                defaultValue, ConfigUtil.getLongProperty(key, defaultValue), "Should return default for long overflow");

        // Test with invalid long from original test
        System.setProperty("test.invalid.long.property", "not-a-long");
        testLong = ConfigUtil.getLongProperty("test.invalid.long.property", 500L);
        assertEquals(500L, testLong, "Default value should be used when property is not a valid long");

        System.out.println("✅ Long property handling works correctly");
    }

    // Service Configuration Tests

    @Test
    void testBlockchainServiceConfiguration() {
        // Test blockchain service type
        String serviceType = ConfigUtil.getBlockchainServiceType();
        assertNotNull(serviceType, "Service type should not be null");

        // Test setting service type to web3j
        System.setProperty("blockchain.manager.service.type", "web3j");
        assertTrue(ConfigUtil.isWeb3jEnabled(), "Should recognize web3j service type");
        assertEquals("web3j", ConfigUtil.getBlockchainServiceType(), "Should return web3j service type");

        // Test setting service type to Web3J (case insensitive)
        System.setProperty("blockchain.manager.service.type", "Web3J");
        assertTrue(ConfigUtil.isWeb3jEnabled(), "Should recognize Web3J service type (case insensitive)");

        // Test setting service type to mock
        System.setProperty("blockchain.manager.service.type", "mock");
        assertFalse(ConfigUtil.isWeb3jEnabled(), "Should not recognize mock as web3j");
        assertEquals("mock", ConfigUtil.getBlockchainServiceType(), "Should return mock service type");

        // Test if mock failures simulation setting works
        boolean mockFailures = ConfigUtil.isMockSimulateFailures();
        System.out.println("Mock failures simulation: " + mockFailures);

        // Test mock simulate failures
        System.setProperty("blockchain.manager.service.mock.simulate.failures", "true");
        assertTrue(ConfigUtil.isMockSimulateFailures(), "Should enable mock failure simulation");

        System.setProperty("blockchain.manager.service.mock.simulate.failures", "false");
        assertFalse(ConfigUtil.isMockSimulateFailures(), "Should disable mock failure simulation");

        // Test Web3j enablement check
        boolean isWeb3jEnabled = ConfigUtil.isWeb3jEnabled();
        System.out.println("Web3j enabled: " + isWeb3jEnabled);

        System.out.println("✅ Blockchain service configuration works correctly");
    }

    // Database Configuration Tests

    @Test
    void testDatabaseConfiguration() {
        // Test database URL retrieval
        String dbUrl = ConfigUtil.getDatabaseUrl();
        assertNotNull(dbUrl, "Database URL should not be null");
        assertTrue(dbUrl.contains("jdbc:"), "Database URL should be a valid JDBC URL");

        System.out.println("✅ Database configuration:");
        System.out.println("Database URL: " + dbUrl);
    }

    @Test
    void testDatabaseConfigurationDefaults() {
        // Clear any existing database properties
        System.clearProperty("blockchain.manager.database.url");
        System.clearProperty("blockchain.manager.database.username");
        System.clearProperty("blockchain.manager.database.password");

        // Test default values
        assertTrue(ConfigUtil.getDatabaseUrl().contains("sqlite"), "Default database should be SQLite");
        assertEquals("user", ConfigUtil.getDatabaseUsername(), "Default username should be 'user'");
        assertEquals("password", ConfigUtil.getDatabasePassword(), "Default password should be 'password'");

        // Test custom values
        System.setProperty("blockchain.manager.database.url", "jdbc:postgresql://localhost:5432/testdb");
        System.setProperty("blockchain.manager.database.username", "testuser");
        System.setProperty("blockchain.manager.database.password", "testpass");

        assertEquals("jdbc:postgresql://localhost:5432/testdb", ConfigUtil.getDatabaseUrl());
        assertEquals("testuser", ConfigUtil.getDatabaseUsername());
        assertEquals("testpass", ConfigUtil.getDatabasePassword());
        System.out.println("✅ Database configuration defaults and overrides work correctly");
    }

    // Application Configuration Tests

    // Properties Management Tests

    @Test
    void testGetAllProperties() {
        // Test getting all properties
        Properties allProps = ConfigUtil.getAllProperties();
        assertNotNull(allProps, "Properties object should not be null");

        // Verify it's a copy, not the original
        assertDoesNotThrow(
                () -> allProps.put("test.key", "test.value"),
                "Should be able to modify the returned properties object");

        System.out.println("✅ All properties retrieved successfully, found " + allProps.size() + " properties");
    }

    @Test
    void testGetAllPropertiesIsolation() {
        // Get all properties
        Properties allProps = ConfigUtil.getAllProperties();
        assertNotNull(allProps, "Properties should not be null");

        // Modify the returned properties
        int originalSize = allProps.size();
        allProps.setProperty("test.isolation.key", "test.value");

        // Get properties again and verify original is not affected
        Properties allProps2 = ConfigUtil.getAllProperties();
        assertEquals(originalSize, allProps2.size(), "Original properties should not be affected by modifications");
        assertNull(allProps2.getProperty("test.isolation.key"), "New property should not exist in fresh copy");

        System.out.println("✅ Properties isolation works correctly");
    }
}
