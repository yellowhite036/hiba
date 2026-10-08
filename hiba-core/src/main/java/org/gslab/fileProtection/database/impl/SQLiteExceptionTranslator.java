package org.gslab.fileProtection.database.impl;

import java.sql.SQLException;
import org.gslab.fileProtection.database.SQLExceptionTranslator;
import org.gslab.fileProtection.exception.ConnectionException;
import org.gslab.fileProtection.exception.DatabaseException;

public class SQLiteExceptionTranslator implements SQLExceptionTranslator {

    @Override
    public DatabaseException translate(SQLException e) {
        int errorCode = e.getErrorCode();
        String message = e.getMessage();

        // SQLite 錯誤碼參考: https://www.sqlite.org/rescode.html
        switch (errorCode) {
            case 19: // SQLITE_CONSTRAINT
                return translateConstraintViolation(e, message);
            case 14: // SQLITE_CANTOPEN
            case 11: // SQLITE_CORRUPT
            case 10: // SQLITE_IOERR
                return new ConnectionException("Database connection or I/O error occurred.", e);
            case 20: // SQLITE_MISMATCH
                return new DatabaseException("Data type mismatch or truncation occurred.", e);
            default:
                return new DatabaseException("An unexpected database error occurred.", e);
        }
    }

    private DatabaseException translateConstraintViolation(SQLException e, String message) {
        if (message == null) {
            return new DatabaseException("A database constraint was violated.", e);
        }

        String lowerMessage = message.toLowerCase();

        if (lowerMessage.contains("unique") || lowerMessage.contains("primary key")) {
            return new DatabaseException("A unique constraint was violated: " + message, e);
        } else if (lowerMessage.contains("foreign key")) {
            return new DatabaseException("A foreign key constraint was violated: " + message, e);
        } else if (lowerMessage.contains("not null")) {
            return new DatabaseException("A NOT NULL constraint was violated: " + message, e);
        } else if (lowerMessage.contains("check")) {
            return new DatabaseException("A CHECK constraint was violated: " + message, e);
        } else {
            return new DatabaseException("A database constraint was violated: " + message, e);
        }
    }
}
