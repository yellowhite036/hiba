package org.gslab.fileProtection.database.impl;

import java.sql.SQLException;
import org.gslab.fileProtection.database.SQLExceptionTranslator;
import org.gslab.fileProtection.exception.ConnectionException;
import org.gslab.fileProtection.exception.DatabaseException;

public class PostgresExceptionTranslator implements SQLExceptionTranslator {

    @Override
    public DatabaseException translate(SQLException e) {
        String sqlState = e.getSQLState();

        if (sqlState == null) {
            return new DatabaseException("An unexpected database error occurred.", e);
        }

        // PostgreSQL SQLSTATE 參考: https://www.postgresql.org/docs/current/errcodes-appendix.html
        switch (sqlState) {
                // Integrity Constraint Violations (Class 23)
            case "23505": // unique_violation
                return new DatabaseException("A unique constraint was violated: " + e.getMessage(), e);
            case "23503": // foreign_key_violation
                return new DatabaseException("A foreign key constraint was violated: " + e.getMessage(), e);
            case "23502": // not_null_violation
                return new DatabaseException("A NOT NULL constraint was violated: " + e.getMessage(), e);
            case "23514": // check_violation
                return new DatabaseException("A CHECK constraint was violated: " + e.getMessage(), e);
            case "23001": // restrict_violation
            case "23000": // integrity_constraint_violation (generic)
                return new DatabaseException("An integrity constraint was violated.", e);

                // Data Exception (Class 22)
            case "22001": // string_data_right_truncation
            case "22003": // numeric_value_out_of_range
            case "22008": // datetime_field_overflow
            case "22012": // division_by_zero
                return new DatabaseException("Data truncation or overflow occurred: " + e.getMessage(), e);

                // Connection Exceptions (Class 08)
            case "08000": // connection_exception
            case "08003": // connection_does_not_exist
            case "08006": // connection_failure
            case "08001": // sqlclient_unable_to_establish_sqlconnection
            case "08004": // sqlserver_rejected_establishment_of_sqlconnection
                return new ConnectionException("Database connection error occurred.", e);

                // System Error (Class XX)
            case "XX000": // internal_error
            case "XX001": // data_corrupted
            case "XX002": // index_corrupted
                return new ConnectionException("Database system error occurred.", e);

            default:
                return new DatabaseException("An unexpected database error occurred.", e);
        }
    }
}
