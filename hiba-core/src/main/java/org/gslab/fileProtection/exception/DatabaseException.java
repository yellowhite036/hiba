package org.gslab.fileProtection.exception;

import java.sql.SQLException;

/**
 * Base exception for all database-related errors Serves as the parent class for more specific database exceptions
 */
public class DatabaseException extends RuntimeException {

    private final String operation;
    private final SQLException sqlException;

    /**
     * Constructor with message only
     *
     * @param message Error message
     */
    public DatabaseException(String message) {
        super(message);
        this.operation = null;
        this.sqlException = null;
    }

    /**
     * Constructor with message and cause
     *
     * @param message Error message
     * @param cause   Underlying cause
     */
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
        this.operation = null;
        this.sqlException = cause instanceof SQLException ? (SQLException) cause : null;
    }

    /**
     * Constructor with message, operation, and SQLException
     *
     * @param message   Error message
     * @param operation Database operation that failed
     * @param cause     Underlying SQLException
     */
    public DatabaseException(String message, String operation, SQLException cause) {
        super(message, cause);
        this.operation = operation;
        this.sqlException = cause;
    }

    /**
     * Get the database operation that failed
     *
     * @return Operation description, or null if not specified
     */
    public String getOperation() {
        return operation;
    }

    /**
     * Get the underlying SQLException
     *
     * @return SQLException that caused this exception, or null if not available
     */
    public SQLException getSqlException() {
        return sqlException;
    }

    /**
     * Get SQL error code if available
     *
     * @return SQL error code, or 0 if not available
     */
    public int getSqlErrorCode() {
        return sqlException != null ? sqlException.getErrorCode() : 0;
    }

    /**
     * Get SQL state if available
     *
     * @return SQL state, or null if not available
     */
    public String getSqlState() {
        return sqlException != null ? sqlException.getSQLState() : null;
    }
}
