package org.gslab.fileProtection.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileWriter;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.gslab.fileProtection.config.BlockchainServiceConfig;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.service.BlockchainService;
import org.gslab.fileProtection.service.factory.BlockchainServiceFactory;
import org.gslab.fileProtection.util.ConfigUtil;
import org.gslab.fileProtection.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for Web3j blockchain service implementation These tests require a running Ethereum node or testnet connection
 */
class Web3jBlockchainServiceImplTest {

    @TempDir
    Path tempDir;

    private BlockchainServiceConfig testConfig;
    private Web3jBlockchainServiceImpl blockchainService;

    @BeforeEach
    void setUp() {}

    @Test
    @DisplayName("Test blockchain service creation with invalid config")
    void testInvalidConfiguration() {
        // Test missing private key
        BlockchainServiceConfig invalidConfig = new BlockchainServiceConfig();
        invalidConfig.setRpcUrl("http://127.0.0.1:8545");
        invalidConfig.setContractAddress("0x1234567890123456789012345678901234567890");

        assertThatThrownBy(() -> new Web3jBlockchainServiceImpl(invalidConfig))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Private key is required");

        // Test missing contract address
        BlockchainServiceConfig invalidConfig2 = new BlockchainServiceConfig();
        invalidConfig2.setRpcUrl("http://127.0.0.1:8545");
        invalidConfig2.setPrivateKey("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d");

        assertThatThrownBy(() -> new Web3jBlockchainServiceImpl(invalidConfig2))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Contract address is required");
    }

    @Test
    @DisplayName("Test factory service creation")
    void testFactoryServiceCreation() {
        // Test Mock service creation
        BlockchainService mockService =
                BlockchainServiceFactory.createService(BlockchainServiceConfig.ServiceType.MOCK);
        assertThat(mockService).isInstanceOf(MockBlockchainServiceImpl.class);

        // Test Web3j service creation with config
        if (isBlockchainAvailable()) {
            try {
                BlockchainService web3jService =
                        BlockchainServiceFactory.createService(BlockchainServiceConfig.ServiceType.WEB3J, testConfig);
                assertThat(web3jService).isInstanceOf(Web3jBlockchainServiceImpl.class);

                if (web3jService instanceof Web3jBlockchainServiceImpl) {
                    ((Web3jBlockchainServiceImpl) web3jService).close();
                }
            } catch (Exception e) {
                System.out.println("Blockchain not available for testing: " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("Test factory environment creation")
    void testFactoryEnvironmentCreation() {
        // Test default (should create Mock)
        // BlockchainService defaultService = BlockchainServiceFactory.createFromEnvironment();
        // assertThat(defaultService).isInstanceOf(MockBlockchainServiceImpl.class);

        // Test with system property
        System.setProperty("blockchain.manager.service.type", "mock");
        BlockchainService mockService = BlockchainServiceFactory.createFromEnvironment();
        assertThat(mockService).isInstanceOf(MockBlockchainServiceImpl.class);

        System.clearProperty("blockchain.manager.service.type");
    }

    @Test
    @EnabledIf("isBlockchainAvailable")
    @DisplayName("Test Web3j service connection")
    void testWeb3jConnection() throws Exception {
        blockchainService = new Web3jBlockchainServiceImpl(testConfig);

        // Test basic connection
        assertThat(blockchainService.getWeb3j()).isNotNull();
        assertThat(blockchainService.getCredentials()).isNotNull();
        assertThat(blockchainService.getConfig()).isEqualTo(testConfig);

        // Test account balance retrieval
        BigInteger balance = blockchainService.getAccountBalance();
        assertThat(balance).isNotNull();

        double balanceEth = blockchainService.getAccountBalanceInEther();
        assertThat(balanceEth).isGreaterThanOrEqualTo(0);

        System.out.printf("Account balance: %s Wei (%.6f ETH)%n", balance, balanceEth);

        blockchainService.close();
    }

    @Test
    @EnabledIf("isBlockchainAvailable")
    @DisplayName("Test file metadata upload and retrieval")
    void testMetadataUploadAndRetrieval() throws Exception {
        blockchainService = new Web3jBlockchainServiceImpl(testConfig);

        try {
            // Create test file metadata
            FileMetadata metadata = createTestMetadata();

            // Upload to blockchain
            String txHash = blockchainService.uploadMetadata(metadata);
            assertThat(txHash).isNotNull();
            assertThat(txHash).startsWith("0x");
            assertThat(txHash).hasSize(66); // 0x + 64 hex characters

            System.out.printf("Upload transaction hash: %s%n", txHash);

            // Wait for confirmation
            boolean confirmed = blockchainService.waitForConfirmation(txHash, 30);
            assertThat(confirmed).isTrue();

            // Check transaction status
            String status = blockchainService.getTransactionStatus(txHash);
            assertThat(status).isEqualTo("CONFIRMED");

            // Retrieve metadata
            FileMetadata retrieved = blockchainService.retrieveMetadata(txHash);
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.getFileName()).isEqualTo(metadata.getFileName());
            assertThat(retrieved.getFileHash()).isEqualTo(metadata.getFileHash());
            assertThat(retrieved.getUploader()).isEqualTo(metadata.getUploader());
            assertThat(retrieved.getFileSize()).isEqualTo(metadata.getFileSize());

            System.out.printf("Retrieved metadata: %s%n", retrieved.getFileName());

        } finally {
            blockchainService.close();
        }
    }

    @Test
    @EnabledIf("isBlockchainAvailable")
    @DisplayName("Test integrity verification")
    void testIntegrityVerification() throws Exception {
        blockchainService = new Web3jBlockchainServiceImpl(testConfig);

        try {
            // Create and upload test metadata
            FileMetadata metadata = createTestMetadata();
            String txHash = blockchainService.uploadMetadata(metadata);

            // Wait for confirmation
            blockchainService.waitForConfirmation(txHash, 30);

            // Test successful verification
            boolean isValid = blockchainService.verifyIntegrity(txHash, metadata.getFileHash());
            assertThat(isValid).isTrue();

            // Test failed verification with wrong hash
            boolean isInvalid = blockchainService.verifyIntegrity(txHash, "wrong-hash");
            assertThat(isInvalid).isFalse();

            System.out.printf("Integrity verification passed for TX: %s%n", txHash);

        } finally {
            blockchainService.close();
        }
    }

    @Test
    @DisplayName("Test transaction status for non-existent transaction")
    void testNonExistentTransaction() throws Exception {
        if (!isBlockchainAvailable()) {
            return;
        }

        blockchainService = new Web3jBlockchainServiceImpl(testConfig);

        try {
            String fakeHash = "0x1234567890123456789012345678901234567890123456789012345678901234";
            String status = blockchainService.getTransactionStatus(fakeHash);
            assertThat(status).isEqualTo("PENDING");

        } finally {
            blockchainService.close();
        }
    }

    /**
     * Helper method to create test file metadata
     */
    private FileMetadata createTestMetadata() throws Exception {
        File testFile = new File(tempDir.toFile(), "web3j-test.txt");
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Web3j blockchain test file content\n");
            writer.write("Timestamp: " + LocalDateTime.now() + "\n");
        }

        FileMetadata metadata = new FileMetadata();
        metadata.setId("web3j-test-" + System.currentTimeMillis());
        metadata.setFileName(testFile.getName());
        metadata.setFileHash(HashUtil.calculateFileHash(testFile));
        metadata.setUploadTime(LocalDateTime.now());
        metadata.setUploader("Web3jTestUser");
        metadata.setFileSize(testFile.length());
        metadata.setFilePath(testFile.getAbsolutePath());
        metadata.setDescription("Test file for Web3j blockchain integration");

        return metadata;
    }

    /**
     * Check if blockchain is available for testing
     */
    boolean isBlockchainAvailable() {
        setUp();

        // Initialize testConfig using ConfigUtil settings
        if (testConfig == null) {
            testConfig = ConfigUtil.createBlockchainServiceConfig();
        }

        // Check if we have required environment variables or system properties
        String rpcUrl = System.getProperty("blockchain.manager.rpc.url", System.getenv("BLOCKCHAIN_RPC_URL"));
        String privateKey =
                System.getProperty("blockchain.manager.private.key", System.getenv("BLOCKCHAIN_PRIVATE_KEY"));
        String contractAddress =
                System.getProperty("blockchain.manager.contract.address", System.getenv("BLOCKCHAIN_CONTRACT_ADDRESS"));

        // If environment variables are set, use them
        if (rpcUrl != null && privateKey != null && contractAddress != null) {
            testConfig.setRpcUrl(rpcUrl);
            testConfig.setPrivateKey(privateKey);
            testConfig.setContractAddress(contractAddress);
            return true;
        }

        // Try to connect to default local Ganache
        try {
            Web3jBlockchainServiceImpl testService = new Web3jBlockchainServiceImpl(testConfig);
            testService.close();
            return true;
        } catch (Exception e) {
            System.out.printf("Blockchain not available: %s%n", e.getMessage());
            return false;
        }
    }
}
