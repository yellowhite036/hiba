package org.gslab.fileProtection.exception;

/**
 * Exception thrown when blockchain upload operations fail
 */
public class BlockchainUploadException extends Exception {

    private final String fileId;
    private final String operation;

    public BlockchainUploadException(String message) {
        super(message);
        this.fileId = null;
        this.operation = null;
    }

    public BlockchainUploadException(String fileId, String operation) {
        super("Blockchain upload failed for file ID: " + fileId + ", operation: " + operation);
        this.fileId = fileId;
        this.operation = operation;
    }

    public BlockchainUploadException(String fileId, String operation, String message) {
        super(message);
        this.fileId = fileId;
        this.operation = operation;
    }

    public BlockchainUploadException(String fileId, String operation, String message, Throwable cause) {
        super(message, cause);
        this.fileId = fileId;
        this.operation = operation;
    }

    public String getFileId() {
        return fileId;
    }

    public String getOperation() {
        return operation;
    }
}
