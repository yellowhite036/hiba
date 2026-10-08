package org.gslab.fileProtection.database;

import java.sql.SQLException;
import org.gslab.fileProtection.exception.DatabaseException;

@FunctionalInterface
public interface SQLExceptionTranslator {

    /**
     * Translates a specific SQLException into a generic DataAccessException.
     *
     * @param e The original SQLException from the JDBC driver.
     * @return A DatabaseException that wraps the original exception.
     */
    DatabaseException translate(SQLException e);
}
