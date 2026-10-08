package org.gslab.fileProtection.exception;

/**
 * Unified exception for file protection operations
 * Replaces multiple specific exceptions with a single, clear exception type
 * Following Linus's principle: solve problems with data structures, not exception types
 */
public class FileProtectionException extends Exception {

    private final String operation;
    private final String context;

    public FileProtectionException(String message) {
        super(message);
        this.operation = "unknown";
        this.context = null;
    }

    public FileProtectionException(String operation, String message) {
        super(message);
        this.operation = operation;
        this.context = null;
    }

    public FileProtectionException(String operation, String message, Throwable cause) {
        super(message, cause);
        this.operation = operation;
        this.context = null;
    }

    public FileProtectionException(String operation, String context, String message) {
        super(message);
        this.operation = operation;
        this.context = context;
    }

    public FileProtectionException(String operation, String context, String message, Throwable cause) {
        super(message, cause);
        this.operation = operation;
        this.context = context;
    }

    public String getOperation() {
        return operation;
    }

    public String getContext() {
        return context;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FileProtectionException");
        if (operation != null && !operation.equals("unknown")) {
            sb.append(" [").append(operation).append("]");
        }
        if (context != null) {
            sb.append(" (").append(context).append(")");
        }
        sb.append(": ").append(getMessage());
        return sb.toString();
    }
}
