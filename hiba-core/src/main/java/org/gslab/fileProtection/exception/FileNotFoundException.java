package org.gslab.fileProtection.exception;

/**
 * Exception thrown when a file is not found on the blockchain
 */
public class FileNotFoundException extends Exception {

    private final String fileHash;

    public FileNotFoundException(String fileHash) {
        super("File not found on blockchain for hash: " + fileHash);
        this.fileHash = fileHash;
    }

    public FileNotFoundException(String fileHash, String message) {
        super(message);
        this.fileHash = fileHash;
    }

    public FileNotFoundException(String fileHash, String message, Throwable cause) {
        super(message, cause);
        this.fileHash = fileHash;
    }

    public String getFileHash() {
        return fileHash;
    }
}
