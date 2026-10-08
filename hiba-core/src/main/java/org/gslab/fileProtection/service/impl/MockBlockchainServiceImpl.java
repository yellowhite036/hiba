package org.gslab.fileProtection.service.impl;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.exception.BlockchainNetworkException;
import org.gslab.fileProtection.exception.FileNotFoundException;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.model.TransactionResult;
import org.gslab.fileProtection.service.BlockchainService;
import org.gslab.fileProtection.util.HashUtil;

/**
 * Mock Blockchain Service Implementation - Used for development and testing phases to simulate real blockchain
 * operations
 */
@Slf4j
public class MockBlockchainServiceImpl implements BlockchainService {

    private boolean simulateFailures = false; // Default to not failing
    private final Set<String> uploadedFileHashes = new HashSet<>(); // Track uploaded files
    private final Map<String, FileMetadata> storedMetadata = new HashMap<>(); // Store actual metadata

    public MockBlockchainServiceImpl() {}

    public MockBlockchainServiceImpl(boolean simulateFailures) {
        this.simulateFailures = simulateFailures; // Allow failure simulation for testing
    }

    public static MockBlockchainServiceImpl createInstanceWithFailures() {
        return new MockBlockchainServiceImpl(true); // Create instance with failure simulation enabled
    }

    public static MockBlockchainServiceImpl createInstanceWithoutFailures() {
        return new MockBlockchainServiceImpl(false); // Create instance with failure simulation disabled
    }

    @Override
    public String uploadMetadata(FileMetadata metadata) throws Exception {
        // Simulate network delay (shortened delay for testing)
        Thread.sleep(ThreadLocalRandom.current().nextInt(10, 100));

        // Simulate upload failure scenario (reduced failure rate to 1% to ensure test stability)
        if (simulateFailures) {
            if (ThreadLocalRandom.current().nextDouble() < 0.01) {
                throw new Exception("Blockchain network error: Transaction failed");
            }
        }

        // Generate mock blockchain transaction hash
        String metadataString =
                metadata.getId() + metadata.getFileName() + metadata.getFileHash() + metadata.getUploadTime();
        String txHash = "0x" + HashUtil.calculateStringHash(metadataString);

        // Track this file hash as uploaded and store the metadata
        uploadedFileHashes.add(metadata.getFileHash());
        storedMetadata.put(metadata.getFileHash(), metadata);

        log.info("Mock blockchain upload successful: {}", txHash);
        return txHash;
    }

    @Override
    public String batchUploadMetadata(List<FileMetadata> metadataList) throws Exception {
        if (metadataList == null || metadataList.isEmpty()) {
            throw new IllegalArgumentException("Metadata list cannot be null or empty");
        }

        // Simulate network delay for batch upload (shortened delay for testing)
        Thread.sleep(ThreadLocalRandom.current().nextInt(50, 200));

        // Simulate upload failure scenario (reduced failure rate to 1% to ensure test stability)
        if (simulateFailures) {
            if (ThreadLocalRandom.current().nextDouble() < 0.01) {
                throw new Exception("Blockchain network error: Batch upload failed");
            }
        }

        // Generate a mock blockchain transaction hash for the batch
        StringBuilder combinedMetadata = new StringBuilder();
        for (FileMetadata metadata : metadataList) {
            combinedMetadata
                    .append(metadata.getId())
                    .append(metadata.getFileName())
                    .append(metadata.getFileHash())
                    .append(metadata.getUploadTime());
            // Track each file hash as uploaded
            uploadedFileHashes.add(metadata.getFileHash());
        }
        String txHash = "0x" + HashUtil.calculateStringHash(combinedMetadata.toString());

        log.info("Mock blockchain batch upload successful: {}", txHash);
        return txHash;
    }

    @Override
    public TransactionResult uploadMetadataWithReceipt(FileMetadata metadata) throws Exception {
        // Simulate network delay (shortened delay for testing)
        Thread.sleep(ThreadLocalRandom.current().nextInt(10, 100));

        // Simulate upload failure scenario (reduced failure rate to 1%)
        if (simulateFailures) {
            if (ThreadLocalRandom.current().nextDouble() < 0.01) {
                throw new Exception("Blockchain network error: Transaction failed");
            }
        }

        // Generate mock blockchain transaction hash
        String metadataString =
                metadata.getId() + metadata.getFileName() + metadata.getFileHash() + metadata.getUploadTime();
        String txHash = "0x" + HashUtil.calculateStringHash(metadataString);

        // Track this file hash as uploaded and store the metadata
        uploadedFileHashes.add(metadata.getFileHash());
        storedMetadata.put(metadata.getFileHash(), metadata);

        // Generate mock Gas data
        BigInteger gasUsed = BigInteger.valueOf(ThreadLocalRandom.current().nextLong(21000, 100000));
        BigInteger gasPrice = BigInteger.valueOf(
                ThreadLocalRandom.current().nextLong(20_000_000_000L, 50_000_000_000L)); // 20-50 Gwei
        BigInteger blockNumber = BigInteger.valueOf(ThreadLocalRandom.current().nextLong(10000000, 20000000));
        String status = "0x1"; // Success

        TransactionResult result = new TransactionResult(txHash, gasUsed, gasPrice, blockNumber, status);

        log.info(
                "Mock blockchain upload successful with receipt: {} (Gas: {} @ {} Gwei)",
                txHash,
                result.getGasUsedLong(),
                result.getGasPriceGwei());
        return result;
    }

    @Override
    public TransactionResult batchUploadMetadataWithReceipt(List<FileMetadata> metadataList) throws Exception {
        if (metadataList == null || metadataList.isEmpty()) {
            throw new IllegalArgumentException("Metadata list cannot be null or empty");
        }

        // Simulate network delay for batch upload (shortened delay for testing)
        Thread.sleep(ThreadLocalRandom.current().nextInt(50, 200));

        // Simulate upload failure scenario (reduced failure rate to 1%)
        if (simulateFailures) {
            if (ThreadLocalRandom.current().nextDouble() < 0.01) {
                throw new Exception("Blockchain network error: Batch upload failed");
            }
        }

        // Generate a mock blockchain transaction hash for the batch
        StringBuilder combinedMetadata = new StringBuilder();
        for (FileMetadata metadata : metadataList) {
            combinedMetadata
                    .append(metadata.getId())
                    .append(metadata.getFileName())
                    .append(metadata.getFileHash())
                    .append(metadata.getUploadTime());
            // Track each file hash as uploaded
            uploadedFileHashes.add(metadata.getFileHash());
        }
        String txHash = "0x" + HashUtil.calculateStringHash(combinedMetadata.toString());

        // Generate mock Gas data (batch operations typically use more gas)
        BigInteger gasUsed = BigInteger.valueOf(
                ThreadLocalRandom.current().nextLong(21000 * metadataList.size(), 100000 * metadataList.size()));
        BigInteger gasPrice = BigInteger.valueOf(
                ThreadLocalRandom.current().nextLong(20_000_000_000L, 50_000_000_000L)); // 20-50 Gwei
        BigInteger blockNumber = BigInteger.valueOf(ThreadLocalRandom.current().nextLong(10000000, 20000000));
        String status = "0x1"; // Success

        TransactionResult result = new TransactionResult(txHash, gasUsed, gasPrice, blockNumber, status);

        log.info(
                "Mock blockchain batch upload successful with receipt: {} (Gas: {} @ {} Gwei, {} files)",
                txHash,
                result.getGasUsedLong(),
                result.getGasPriceGwei(),
                metadataList.size());
        return result;
    }

    /**
     * Asynchronous metadata upload
     *
     * @param metadata File metadata
     * @return CompletableFuture wrapped transaction hash
     * @throws CompletionException When the async operation fails (wraps other exceptions)
     */
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
     * Asynchronous batch metadata upload
     *
     * @param metadataList List of file metadata
     * @return CompletableFuture wrapped transaction hash
     * @throws CompletionException When the async operation fails (wraps other exceptions)
     */
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
    public FileMetadata retrieveMetadataByFileHash(String fileHash)
            throws FileNotFoundException, BlockchainNetworkException {
        try {
            // Simulate network delay (shortened delay for testing)
            Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));

            // Simulate retrieval failure scenario (reduced failure rate)
            if (simulateFailures) {
                double random = ThreadLocalRandom.current().nextDouble();
                if (random < 0.001) {
                    throw new BlockchainNetworkException(
                            "retrieveMetadataByFileHash", "Blockchain network error: Unable to retrieve data");
                } else if (random < 0.01) {
                    // Simulate file not found scenario
                    throw new FileNotFoundException(fileHash);
                }
            }

            // Return the actual stored metadata if it exists
            FileMetadata metadata = storedMetadata.get(fileHash);
            if (metadata == null) {
                throw new FileNotFoundException(fileHash);
            }

            log.info("Mock blockchain retrieve successful for file hash: {}", fileHash);
            return metadata;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BlockchainNetworkException(
                    "retrieveMetadataByFileHash", "Thread interrupted during network simulation", e);
        }
    }

    @Override
    public FileMetadata retrieveMetadata(String txHash)
            throws Exception { // Simulate network delay (shortened delay for testing)
        Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));

        // Simulate retrieval failure scenario (reduced failure rate)
        if (simulateFailures) {
            if (ThreadLocalRandom.current().nextDouble() < 0.001) {
                throw new Exception("Blockchain network error: Unable to retrieve data");
            }
        }

        // Simulate returning file metadata (in actual scenario would parse from blockchain)
        FileMetadata metadata = new FileMetadata();
        metadata.setId("mock-file-id");
        metadata.setFileName("mock-file.txt");
        metadata.setFileHash("mock-hash-" + txHash.substring(2, 10));
        metadata.setUploadTime(LocalDateTime.now().minusHours(1));
        metadata.setUploader("mock-user");
        metadata.setFileSize(1024);
        metadata.setFilePath("/mock/path/mock-file.txt");

        log.info("Mock blockchain retrieve successful: {}", txHash);
        return metadata;
    }

    @Override
    public boolean verifyIntegrity(String txHash, String expectedHash) {
        try {
            // Simulate network delay
            Thread.sleep(ThreadLocalRandom.current().nextInt(100, 500));

            boolean isValid = true;

            // Simulate verification logic (90% success rate)
            if (simulateFailures) {
                // Simulate a failure 10% of the time
                isValid = ThreadLocalRandom.current().nextDouble() >= 0.1;
            }

            log.info("Mock integrity verification for {}: {}", txHash, isValid);
            return isValid;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public String getTransactionStatus(String txHash) {
        try {
            // Simulate network delay
            Thread.sleep(ThreadLocalRandom.current().nextInt(100, 300));

            String status;
            // Simulate different transaction statuses
            if (simulateFailures) {
                double random = ThreadLocalRandom.current().nextDouble();
                if (random < 0.8) {
                    status = "CONFIRMED";
                } else if (random < 0.95) {
                    status = "PENDING";
                } else {
                    status = "FAILED";
                }
            } else {
                status = "CONFIRMED"; // Always confirmed in non-failure mode
            }

            log.info("Mock transaction status for {}: {}", txHash, status);
            return status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR";
        }
    }

    @Override
    public boolean exists(String fileHash) {
        log.debug("Checking if file exists on blockchain: {}", fileHash);

        boolean exists;

        if (simulateFailures) {
            // In failure simulation mode, occasionally return random results for testing edge cases
            double random = ThreadLocalRandom.current().nextDouble();
            if (random < 0.1) { // 10% chance of random behavior
                exists = ThreadLocalRandom.current().nextBoolean();
                log.debug("Simulated random existence check for file: {}, result: {}", fileHash, exists);
            } else {
                // Normal behavior: check if we actually uploaded this file
                exists = uploadedFileHashes.contains(fileHash);
            }
        } else {
            // Normal mode: accurately track uploaded files
            exists = uploadedFileHashes.contains(fileHash);
        }

        if (exists) {
            log.info("File already exists on blockchain: {}", fileHash);
        } else {
            log.debug("File does not exist on blockchain: {}", fileHash);
        }

        return exists;
    }

    @Override
    public boolean isConnected() {
        // Mock implementation is always "connected"
        return true;
    }

    @Override
    public void close() {
        // No resources to close in mock implementation
        log.info("MockBlockchainServiceImpl closed");
    }
}
