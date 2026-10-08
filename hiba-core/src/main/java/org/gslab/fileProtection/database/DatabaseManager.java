package org.gslab.fileProtection.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.gslab.fileProtection.exception.DatabaseException;
import org.gslab.fileProtection.model.FileMetadata;

/**
 * Interface for database operations related to blockchain file metadata and transactions
 */
public interface DatabaseManager extends AutoCloseable {

    /**
     * Initialize the database connection and structure
     */
    void initializeDatabase() throws SQLException;

    /**
     * Close database connection Overrides AutoCloseable.close() to throw SQLException instead of Exception
     */
    @Override
    void close() throws SQLException;

    /**
     * Get database connection for advanced operations
     */
    Connection getConnection() throws SQLException;

    /**
     * Create necessary database tables and indices
     */
    void createTables() throws SQLException;

    /**
     * Save file metadata to database
     *
     * @param metadata the file metadata to save
     * @throws DatabaseException for any database constraint violations or operation failures
     */
    void saveFileMetadata(FileMetadata metadata);

    /**
     * Find file metadata by ID
     *
     * @param fileId the unique identifier of the file
     * @return the file metadata, or null if not found
     * @throws DatabaseException for database access errors
     */
    FileMetadata findFileMetadataById(String fileId);

    /**
     * Find file metadata by file name
     *
     * @param fileName the name of the file
     * @return the file metadata, or null if not found
     * @throws DatabaseException for database access errors
     */
    FileMetadata findFileMetadataByName(String fileName);

    /**
     * Find file metadata by file hash
     *
     * @param fileHash the hash of the file
     * @return the file metadata, or null if not found
     * @throws DatabaseException for database access errors
     */
    FileMetadata findFileMetadataByHash(String fileHash);

    /**
     * Get all file metadata
     *
     * @return list of all file metadata, ordered by upload time (newest first), empty list if none found
     * @throws DatabaseException for database access errors
     */
    List<FileMetadata> getAllFileMetadata();

    /**
     * Find file metadata by uploader
     *
     * @param uploader the name of the uploader
     * @return list of file metadata uploaded by the specified user, empty list if none found
     * @throws DatabaseException for database access errors
     */
    List<FileMetadata> findFileMetadataByUploader(String uploader);

    /**
     * Check if file exists using FileMetadata (secure approach) Implementation extracts fileName and fileHash from
     * metadata
     *
     * @param metadata the file metadata to check
     * @return true if a file with the same name or hash exists
     * @throws DatabaseException for database access errors
     */
    boolean isFileExists(FileMetadata metadata);

    /**
     * Remove file record
     *
     * @param fileId the unique identifier of the file to remove
     * @return true if the file was successfully removed, false if not found
     * @throws DatabaseException for database access errors
     */
    boolean removeFile(String fileId);

    /**
     * Remove file record using FileMetadata
     *
     * @param metadata the file metadata of the file to remove
     * @return true if the file was successfully removed, false if not found
     * @throws DatabaseException for database access errors
     */
    boolean removeFile(FileMetadata metadata);

    /**
     * Clear all data from the database (for testing)
     *
     * @throws DatabaseException for database access errors
     */
    void clearAllData();

    /**
     * Get database statistics
     *
     * @return map containing database statistics (file count, etc.)
     * @throws DatabaseException for database access errors
     */
    Map<String, Object> getStatistics();

    /**
     * Check database connection status
     *
     * @return true if database connection is active and valid
     */
    boolean isConnected();
}
