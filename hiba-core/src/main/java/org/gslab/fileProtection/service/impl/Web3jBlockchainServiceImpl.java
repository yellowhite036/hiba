package org.gslab.fileProtection.service.impl;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.adapter.FileMetadataAdapter;
import org.gslab.fileProtection.config.BlockchainServiceConfig;
import org.gslab.fileProtection.contract.FileProtectionContract;
import org.gslab.fileProtection.exception.BlockchainNetworkException;
import org.gslab.fileProtection.exception.FileNotFoundException;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.model.TransactionResult;
import org.gslab.fileProtection.service.BlockchainService;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.websocket.WebSocketService;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.exceptions.ContractCallException;
import org.web3j.tx.gas.DefaultGasProvider;

/**
 * Web3j implementation of BlockchainService for real Ethereum blockchain interaction
 */
@Slf4j
public class Web3jBlockchainServiceImpl implements BlockchainService {

    private final Web3j web3j;
    private final WebSocketService webSocketService; // Store WebSocket service for proper cleanup
    private final Credentials credentials;
    private final BlockchainServiceConfig config;
    private final FileProtectionContract contract;

    /**
     * Constructor with blockchain service configuration
     */
    public Web3jBlockchainServiceImpl(BlockchainServiceConfig config) throws Exception {
        this.config = config;
        final String privateKey = config.getPrivateKey();
        final String contractAddress = config.getContractAddress();
        final String rpcUrl = config.getRpcUrl();

        // Validate required parameters first
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalArgumentException("Private key is required for blockchain operations");
        }

        if (contractAddress == null || contractAddress.isEmpty()) {
            throw new IllegalArgumentException("Contract address is required");
        }

        if (rpcUrl == null || rpcUrl.isEmpty()) {
            throw new IllegalArgumentException("RPC URL is required to connect to blockchain");
        }

        // Initialize Web3j connection
        if (rpcUrl.startsWith("ws")) {
            log.info("Using WebSocket RPC URL for blockchain connection");
            try {
                WebSocketService wsService = new WebSocketService(rpcUrl, true);
                wsService.connect(); // Establish WebSocket connection explicitly
                this.webSocketService = wsService;
                this.web3j = Web3j.build(wsService);
                log.info("WebSocket connection established successfully");
            } catch (Exception e) {
                throw new Exception("Failed to establish WebSocket connection: " + e.getMessage(), e);
            }
        } else if (rpcUrl.startsWith("http")) {
            log.info("Using HTTP RPC URL for blockchain connection");
            this.webSocketService = null; // No WebSocket service for HTTP
            this.web3j = Web3j.build(new HttpService(rpcUrl));
        } else {
            throw new IllegalArgumentException("Unsupported RPC URL scheme. Use http(s):// or ws(s)://");
        }

        // Verify connection
        verifyConnection();

        // Initialize credentials
        this.credentials = Credentials.create(privateKey);

        // Initialize contract with faster polling for local networks
        DefaultGasProvider gasProvider = new DefaultGasProvider();

        // Use polling configuration from BlockchainConfig
        // Use FastRawTransactionManager for better nonce management (prevents
        // "replacement transaction underpriced")
        TransactionManager txManager = new org.web3j.tx.FastRawTransactionManager(
                web3j,
                credentials,
                Long.parseLong(config.getNetworkId()),
                new org.web3j.tx.response.PollingTransactionReceiptProcessor(
                        web3j, config.getPollingInterval(), config.getPollingAttempts()));

        this.contract = FileProtectionContract.load(contractAddress, web3j, txManager, gasProvider);

        log.info("Web3j blockchain service initialized successfully");
        log.info("Connected to network: {}", config.getNetworkId());
        log.info("Contract address: {}", contractAddress);
        log.info("Account address: {}", credentials.getAddress());
    }

    /**
     * Verify blockchain connection
     */
    private void verifyConnection() throws Exception {
        try {
            String clientVersion = web3j.web3ClientVersion().send().getWeb3ClientVersion();
            log.info("Connected to Ethereum client: {}", clientVersion);

            BigInteger blockNumber = web3j.ethBlockNumber().send().getBlockNumber();
            log.info("Latest block number: {}", blockNumber);

        } catch (Exception e) {
            log.error("Failed to connect to blockchain: {}", e.getMessage());
            throw new Exception("Blockchain connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadMetadata(FileMetadata metadata) throws Exception {
        if (metadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }

        try {
            log.info("Uploading file metadata to blockchain: {}", metadata.getFileName());

            TransactionReceipt receipt = contract.storeFileWithStruct(
                            FileMetadataAdapter.toContractFileMetadata(metadata))
                    .send();

            String txHash = receipt.getTransactionHash();

            // Verify transaction success
            if (!receipt.isStatusOK()) {
                throw new Exception("Transaction failed. Hash: " + txHash);
            }

            log.info("File metadata uploaded successfully. TX Hash: {}", txHash);
            log.info("Gas used: {}", receipt.getGasUsed());

            return txHash;

        } catch (Exception e) {
            log.error("Failed to upload metadata for file: {}", metadata.getFileName(), e);
            throw new Exception("Blockchain upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String batchUploadMetadata(List<FileMetadata> metadataList) throws Exception {
        if (metadataList == null || metadataList.isEmpty()) {
            throw new IllegalArgumentException("No metadata provided for upload");
        }

        try {
            // trans all the FileMetadata in the list to FileProtectionContract.FileMetadata and call
            // storeFilesWithStruct
            TransactionReceipt receipt = this.contract
                    .batchStoreFileWithStruct(metadataList.stream()
                            .map(FileMetadataAdapter::toContractFileMetadata)
                            .toList())
                    .send();

            if (!receipt.isStatusOK()) {
                throw new Exception("Blockchain batch upload failed. Hash: " + receipt.getTransactionHash());
            }

            log.info("Batch file metadata uploaded successfully. TX Hash: {}", receipt.getTransactionHash());
            log.info("Gas used: {}", receipt.getGasUsed());

            return receipt.getTransactionHash();
        } catch (Exception e) {
            log.error("Failed to upload batch metadata", e);
            throw new Exception("Blockchain batch upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public TransactionResult uploadMetadataWithReceipt(FileMetadata metadata) throws Exception {
        if (metadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }

        try {
            log.info("Uploading file metadata to blockchain (with receipt): {}", metadata.getFileName());

            TransactionReceipt receipt = contract.storeFileWithStruct(
                            FileMetadataAdapter.toContractFileMetadata(metadata))
                    .send();

            String txHash = receipt.getTransactionHash();

            // Verify transaction success
            if (!receipt.isStatusOK()) {
                throw new Exception("Transaction failed. Hash: " + txHash);
            }

            log.info("File metadata uploaded successfully. TX Hash: {}", txHash);

            // Build TransactionResult with full gas information
            TransactionResult result = buildTransactionResult(receipt);

            log.info(
                    "Gas details - Used: {}, Price: {} Gwei, Total Cost: {} ETH",
                    result.getGasUsedLong(),
                    result.getGasPriceGwei(),
                    result.getTotalCostEth());

            return result;

        } catch (Exception e) {
            log.error("Failed to upload metadata for file: {}", metadata.getFileName(), e);
            throw new Exception("Blockchain upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public TransactionResult batchUploadMetadataWithReceipt(List<FileMetadata> metadataList) throws Exception {
        if (metadataList == null || metadataList.isEmpty()) {
            throw new IllegalArgumentException("No metadata provided for upload");
        }

        try {
            log.info("Batch uploading {} file metadata to blockchain (with receipt)", metadataList.size());

            TransactionReceipt receipt = this.contract
                    .batchStoreFileWithStruct(metadataList.stream()
                            .map(FileMetadataAdapter::toContractFileMetadata)
                            .toList())
                    .send();

            if (!receipt.isStatusOK()) {
                throw new Exception("Blockchain batch upload failed. Hash: " + receipt.getTransactionHash());
            }

            log.info("Batch file metadata uploaded successfully. TX Hash: {}", receipt.getTransactionHash());

            // Build TransactionResult with full gas information
            TransactionResult result = buildTransactionResult(receipt);

            log.info(
                    "Batch gas details - Used: {}, Price: {} Gwei, Total Cost: {} ETH",
                    result.getGasUsedLong(),
                    result.getGasPriceGwei(),
                    result.getTotalCostEth());

            return result;

        } catch (Exception e) {
            log.error("Failed to upload batch metadata", e);
            throw new Exception("Blockchain batch upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build TransactionResult from TransactionReceipt
     * Extracts gas information by querying the transaction details
     *
     * @param receipt Transaction receipt
     * @return TransactionResult with complete gas information
     */
    private TransactionResult buildTransactionResult(TransactionReceipt receipt) {
        String txHash = receipt.getTransactionHash();
        BigInteger gasUsed = receipt.getGasUsed();
        BigInteger blockNumber = receipt.getBlockNumber();
        String status = receipt.getStatus();

        // Get gas price from transaction
        BigInteger gasPrice = BigInteger.ZERO;
        try {
            Optional<Transaction> txOpt =
                    web3j.ethGetTransactionByHash(txHash).send().getTransaction();

            if (txOpt.isPresent()) {
                gasPrice = txOpt.get().getGasPrice();
            } else {
                log.warn("Could not retrieve transaction details for gas price. Hash: {}", txHash);
            }
        } catch (IOException e) {
            log.warn("Failed to get gas price from transaction: {}", e.getMessage());
            // Continue with gasPrice = 0
        }

        return new TransactionResult(txHash, gasUsed, gasPrice, blockNumber, status);
    }

    /**
     * Upload metadata asynchronously
     *
     * @param metadata FileMetadata to upload
     * @return CompletableFuture with transaction hash
     * @throws CompletionException When the async operation fails (wraps other exceptions)
     */
    @Override
    public CompletableFuture<String> uploadMetadataAsync(FileMetadata metadata) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return uploadMetadata(metadata);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Upload list of metadata asynchronously
     *
     * @param metadataList List of FileMetadata to upload
     * @return CompletableFuture with transaction hash
     * @throws CompletionException When the async operation fails (wraps other exceptions)
     */
    @Override
    public CompletableFuture<String> batchUploadMetadataAsync(List<FileMetadata> metadataList) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return batchUploadMetadata(metadataList);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public FileMetadata retrieveMetadata(String txHash) throws Exception {
        try {
            log.info("Retrieving metadata from blockchain for TX: {}", txHash);

            // Get transaction receipt to find the file hash from events
            Optional<TransactionReceipt> receiptOpt =
                    web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();

            if (receiptOpt.isEmpty()) {
                throw new Exception("Transaction not found: " + txHash);
            }
            TransactionReceipt receipt = receiptOpt.get();

            // Verify the transaction is from the expected contract
            String expectedContract = config.getContractAddress().toLowerCase();
            boolean hasMatchingContract = receipt.getLogs().stream()
                    .anyMatch(log -> expectedContract.equals(log.getAddress().toLowerCase()));

            if (!hasMatchingContract) {
                throw new Exception("Transaction " + txHash + " is not from the expected contract address: "
                        + expectedContract + ". This might be from an old contract version.");
            }

            // Extract file stored events
            var events = FileProtectionContract.getFileStoredEvents(receipt);
            if (events.isEmpty()) {
                throw new Exception("No FileStored events found in transaction: " + txHash);
            }

            String fileHash = events.getFirst().fileHash;

            // 直接用 fileHash 查詢（重用上面的方法）
            return retrieveMetadataByFileHash(fileHash);

        } catch (Exception e) {
            log.error("Failed to retrieve metadata for TX: {}", txHash, e);
            throw new Exception("Blockchain retrieval failed: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieve file metadata by file hash directly
     */
    public FileMetadata retrieveMetadataByFileHash(String fileHash)
            throws FileNotFoundException, BlockchainNetworkException {
        try {
            log.info("Retrieving metadata from blockchain for file hash: {}", fileHash);

            // 直接用 fileHash 查詢，不需要解析交易事件
            var result = contract.getFileAsStruct(fileHash).send();

            if (result == null || !result.exists) {
                throw new FileNotFoundException(fileHash);
            }

            FileMetadata metadata = FileMetadataAdapter.fromContractStoredFileMetadata(result);

            log.info("Metadata retrieved successfully for file: {}", metadata.getFileName());
            return metadata;
        } catch (ContractCallException e) {
            // Check if this is a "File not found" error from the smart contract
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("File not found")) {
                log.debug("File not found on blockchain for hash: {}", fileHash);
                throw new FileNotFoundException(fileHash, "File not found on blockchain", e);
            } else {
                // This is a network or other contract error
                log.error("Contract call failed for file hash: {}", fileHash, e);
                throw new BlockchainNetworkException(
                        "retrieveMetadataByFileHash", "Contract call failed: " + errorMessage, e);
            }
        } catch (Exception e) {
            // Handle any other unexpected exceptions
            log.error("Unexpected error retrieving metadata for file hash: {}", fileHash, e);
            throw new BlockchainNetworkException(
                    "retrieveMetadataByFileHash", "Unexpected error: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verifyIntegrity(String txHash, String expectedHash) {
        try {
            log.info("Verifying integrity for TX: {} with expected hash: {}", txHash, expectedHash);

            // Retrieve metadata from blockchain
            FileMetadata metadata = retrieveMetadata(txHash);

            // Compare hashes
            boolean isValid = expectedHash.equals(metadata.getFileHash());

            log.info("Integrity verification result for {}: {}", txHash, isValid);
            return isValid;

        } catch (Exception e) {
            log.error("Failed to verify integrity for TX: {}", txHash, e);
            return false;
        }
    }

    @Override
    public String getTransactionStatus(String txHash) {
        try {
            log.debug("Checking transaction status for: {}", txHash);

            Optional<TransactionReceipt> receiptOpt =
                    web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();

            if (receiptOpt.isEmpty()) {
                // Transaction not yet mined
                return "PENDING";
            }

            TransactionReceipt receipt = receiptOpt.get();

            if (receipt.isStatusOK()) {
                return "CONFIRMED";
            } else {
                return "FAILED";
            }

        } catch (Exception e) {
            log.error("Failed to get transaction status for: {}", txHash, e);
            return "ERROR";
        }
    }

    /**
     * Wait for transaction confirmation
     */
    public boolean waitForConfirmation(String txHash, long timeoutSeconds) {
        try {
            long startTime = System.currentTimeMillis();
            long timeoutMillis = timeoutSeconds * 1000;

            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                String status = getTransactionStatus(txHash);

                if ("CONFIRMED".equals(status)) {
                    return true;
                } else if ("FAILED".equals(status) || "ERROR".equals(status)) {
                    return false;
                }

                // Wait before next check
                //noinspection BusyWait
                Thread.sleep(2000);
            }

            log.warn("Transaction confirmation timeout for: {}", txHash);
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Get account balance in Wei
     */
    public BigInteger getAccountBalance() {
        try {
            return web3j.ethGetBalance(
                            credentials.getAddress(), org.web3j.protocol.core.DefaultBlockParameterName.LATEST)
                    .send()
                    .getBalance();
        } catch (IOException e) {
            log.error("Failed to get account balance", e);
            return BigInteger.ZERO;
        }
    }

    /**
     * Get account balance in Ether
     */
    public double getAccountBalanceInEther() {
        BigInteger balanceWei = getAccountBalance();
        return balanceWei.doubleValue() / Math.pow(10, 18);
    }

    @Override
    public boolean exists(String fileHash) {
        try {
            log.debug("Checking if file exists on blockchain: {}", fileHash);

            // Direct O(1) lookup using fileHash as key
            boolean exists = contract.fileExists(fileHash).send();

            if (exists) {
                log.info("File already exists on blockchain: {}", fileHash);
            } else {
                log.debug("File does not exist on blockchain: {}", fileHash);
            }

            return exists;

        } catch (Exception e) {
            log.error("Error checking file existence on blockchain: {}", e.getMessage());
            // In case of error, assume file doesn't exist to allow upload attempt
            return false;
        }
    }

    /**
     * Check blockchain connection status
     */
    @Override
    public boolean isConnected() {
        if (web3j == null) {
            return false;
        }

        try {
            // Lightweight RPC call to check connection
            web3j.web3ClientVersion().send();
            return true;
        } catch (Exception e) {
            log.debug("Blockchain connection check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Close Web3j connection
     */
    @Override
    public void close() {
        // Close WebSocket service first if it exists
        if (webSocketService != null) {
            try {
                webSocketService.close();
                log.info("WebSocket connection closed");
            } catch (Exception e) {
                log.warn("Error closing WebSocket connection: {}", e.getMessage());
            }
        }

        // Then shutdown Web3j
        if (web3j != null) {
            web3j.shutdown();
            log.info("Web3j connection closed");
        }
    }

    // Getters for testing and monitoring
    public Web3j getWeb3j() {
        return web3j;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public BlockchainServiceConfig getConfig() {
        return config;
    }
}
