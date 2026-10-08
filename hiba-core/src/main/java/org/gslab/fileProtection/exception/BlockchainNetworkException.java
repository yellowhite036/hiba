package org.gslab.fileProtection.exception;

/**
 * Exception thrown when blockchain network or communication errors occur
 */
public class BlockchainNetworkException extends Exception {

    private final String operation;

    public BlockchainNetworkException(String message) {
        super(message);
        this.operation = null;
    }

    public BlockchainNetworkException(String operation, String message) {
        super(message);
        this.operation = operation;
    }

    public BlockchainNetworkException(String operation, String message, Throwable cause) {
        super(message, cause);
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }
}
