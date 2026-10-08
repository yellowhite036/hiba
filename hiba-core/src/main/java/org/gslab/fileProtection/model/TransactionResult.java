package org.gslab.fileProtection.model;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Transaction Result - Immutable value object for blockchain transaction details
 *
 * <p>Follows Clean Code principles:
 * <ul>
 *   <li>Value Object Pattern: Immutable and self-contained</li>
 *   <li>Single Responsibility: Only represents transaction result data</li>
 *   <li>No business logic: Pure data transfer object</li>
 * </ul>
 *
 * <p>Contains all relevant information from a blockchain transaction:
 * <ul>
 *   <li>Transaction hash for identification</li>
 *   <li>Gas usage and pricing for cost analysis</li>
 *   <li>Block information for confirmation tracking</li>
 *   <li>Status for success verification</li>
 * </ul>
 *
 * @param transactionHash  Unique transaction identifier (hex string)
 * @param gasUsed          Actual gas consumed by the transaction (in Wei)
 * @param gasPrice         Gas price paid (in Wei per gas unit)
 * @param blockNumber      Block number where transaction was mined
 * @param status           Transaction status (0x1 = success, 0x0 = failure)
 */
public record TransactionResult(
        String transactionHash, BigInteger gasUsed, BigInteger gasPrice, BigInteger blockNumber, String status) {

    /**
     * Constructor with validation
     */
    public TransactionResult {
        Objects.requireNonNull(transactionHash, "Transaction hash cannot be null");
        Objects.requireNonNull(gasUsed, "Gas used cannot be null");
        // gasPrice can be null for some transaction types
        // blockNumber can be null for pending transactions
        // status can be null for pending transactions
    }

    /**
     * Check if transaction was successful
     *
     * @return true if transaction succeeded, false otherwise
     */
    public boolean isSuccess() {
        return "0x1".equals(status);
    }

    /**
     * Get gas used as long value
     *
     * @return Gas used in Wei as long
     */
    public long getGasUsedLong() {
        return gasUsed != null ? gasUsed.longValue() : 0L;
    }

    /**
     * Get gas price in Gwei
     *
     * @return Gas price in Gwei (1 Gwei = 10^9 Wei)
     */
    public double getGasPriceGwei() {
        if (gasPrice == null) {
            return 0.0;
        }
        return gasPrice.doubleValue() / 1_000_000_000.0;
    }

    /**
     * Calculate total gas cost in ETH
     *
     * @return Total cost in ETH (gasUsed * gasPrice / 10^18)
     */
    public double getTotalCostEth() {
        if (gasUsed == null || gasPrice == null) {
            return 0.0;
        }

        // Calculate: gasUsed * gasPrice (in Wei)
        BigInteger totalWei = gasUsed.multiply(gasPrice);

        // Convert to ETH (1 ETH = 10^18 Wei)
        return totalWei.doubleValue() / 1_000_000_000_000_000_000.0;
    }

    /**
     * Get block number as long value
     *
     * @return Block number or 0 if not yet mined
     */
    public long getBlockNumberLong() {
        return blockNumber != null ? blockNumber.longValue() : 0L;
    }

    @Override
    public String toString() {
        return String.format(
                "TransactionResult{hash='%s', gasUsed=%s, gasPrice=%s Gwei, block=%s, status=%s}",
                transactionHash, gasUsed, getGasPriceGwei(), blockNumber, isSuccess() ? "SUCCESS" : "FAILED");
    }

    /**
     * Create a transaction result for a failed transaction (no gas info)
     *
     * @param transactionHash Transaction hash
     * @return TransactionResult with minimal information
     */
    public static TransactionResult failed(String transactionHash) {
        return new TransactionResult(transactionHash, BigInteger.ZERO, BigInteger.ZERO, null, "0x0");
    }

    /**
     * Create a transaction result for a successful transaction
     *
     * @param transactionHash Transaction hash
     * @param gasUsed         Gas used
     * @param gasPrice        Gas price
     * @param blockNumber     Block number
     * @return TransactionResult with full information
     */
    public static TransactionResult success(
            String transactionHash, BigInteger gasUsed, BigInteger gasPrice, BigInteger blockNumber) {
        return new TransactionResult(transactionHash, gasUsed, gasPrice, blockNumber, "0x1");
    }
}
