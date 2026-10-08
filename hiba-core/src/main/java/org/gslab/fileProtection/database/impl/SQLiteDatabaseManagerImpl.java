package org.gslab.fileProtection.database.impl;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.config.DatabaseConfig;
import org.gslab.fileProtection.database.DatabaseManager;
import org.gslab.fileProtection.exception.DatabaseException;
import org.gslab.fileProtection.model.FileMetadata;

/**
 * SQLite Database Manager for persistent storage of file metadata
 */
@Slf4j
public class SQLiteDatabaseManagerImpl implements DatabaseManager {

    private static final String DEFAULT_DATABASE_URL = "jdbc:sqlite:blockchain_manager.db";

    private final String databaseUrl;
    private Connection connection;
    private final SQLiteExceptionTranslator exceptionTranslator = new SQLiteExceptionTranslator();

    public SQLiteDatabaseManagerImpl() throws SQLException {
        this(DEFAULT_DATABASE_URL);
    }

    /**
     * Constructor with custom database URL (for testing)
     */
    public SQLiteDatabaseManagerImpl(String databaseUrl) throws SQLException {
        this.databaseUrl = databaseUrl;
        initializeDatabase();
    }

    public SQLiteDatabaseManagerImpl(DatabaseConfig config) throws SQLException {
        this.databaseUrl = config.getUrl() != null ? config.getUrl() : DEFAULT_DATABASE_URL;
        initializeDatabase();
    }

    /**
     * Initialize database connection and create tables if they don't exist
     */
    public void initializeDatabase() throws SQLException {
        try {
            // make sure the SQLite db file exists
            File dbFile = new File(databaseUrl.replace("jdbc:sqlite:", ""));

            if (!dbFile.exists()) {
                log.info("Database file does not exist, creating: {}", dbFile.getAbsolutePath());
                boolean res = dbFile.createNewFile();

                if (!res) {
                    log.error("Failed to create database file: {}", dbFile.getAbsolutePath());
                    throw new IOException("Could not create database file");
                }
            }

            // Enable SQLite foreign key support and other optimizations
            Properties props = new Properties();
            props.setProperty("foreign_keys", "on");
            props.setProperty("journal_mode", "WAL");
            props.setProperty("synchronous", "NORMAL");

            connection = DriverManager.getConnection(databaseUrl, props);
            connection.setAutoCommit(true);

            createTables();
            log.info("Database initialized successfully");

        } catch (SQLException e) {
            log.error("Failed to initialize database: {}", e.getMessage());
            throw e;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Create necessary tables
     */
    public void createTables() throws SQLException {
        // Create FileMetadata table
        String createFileMetadataTable =
                """
                    CREATE TABLE IF NOT EXISTS file_metadata (
                        id TEXT PRIMARY KEY,
                        file_name TEXT NOT NULL,
                        file_hash TEXT NOT NULL,
                        upload_time TIMESTAMP NOT NULL,
                        uploader TEXT NOT NULL,
                        file_size INTEGER NOT NULL,
                        file_path TEXT NOT NULL,
                        description TEXT,
                        UNIQUE(file_name)
                    )
                """;

        // Create indices for better performance
        String createFileNameIndex = "CREATE INDEX IF NOT EXISTS idx_file_name ON file_metadata(file_name)";
        String createFileHashIndex = "CREATE INDEX IF NOT EXISTS idx_file_hash ON file_metadata(file_hash)";
        String createUploaderIndex = "CREATE INDEX IF NOT EXISTS idx_uploader ON file_metadata(uploader)";
        String createUploaderTimeIndex =
                "CREATE INDEX IF NOT EXISTS idx_uploader_time ON file_metadata(uploader, upload_time DESC)";
        String createUploadTimeIndex = "CREATE INDEX IF NOT EXISTS idx_upload_time ON file_metadata(upload_time)";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createFileMetadataTable);
            stmt.execute(createFileNameIndex);
            stmt.execute(createFileHashIndex);
            stmt.execute(createUploaderIndex);
            stmt.execute(createUploaderTimeIndex);
            stmt.execute(createUploadTimeIndex);

            log.debug("Database tables and indices created successfully");
        }
    }

    /**
     * Save file metadata to database Uses SQLite INSERT statement with automatic
     * constraint violation detection.
     *
     * @throws DatabaseException for any database constraint violations or operation
     *                           failures
     */
    public void saveFileMetadata(FileMetadata metadata) {
        String sql =
                """
                    INSERT INTO file_metadata
                    (id, file_name, file_hash, upload_time, uploader, file_size, file_path, description)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, metadata.getId());
            pstmt.setString(2, metadata.getFileName());
            pstmt.setString(3, metadata.getFileHash());
            pstmt.setObject(4, metadata.getUploadTime());
            pstmt.setString(5, metadata.getUploader());
            pstmt.setLong(6, metadata.getFileSize());
            pstmt.setString(7, metadata.getFilePath());
            pstmt.setString(8, metadata.getDescription());

            pstmt.executeUpdate();
            log.debug("Saved file metadata: {}", metadata.getFileName());
        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
    }

    /**
     * Find file metadata by ID
     */
    public FileMetadata findFileMetadataById(String fileId) {
        String sql = "SELECT * FROM file_metadata WHERE id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, fileId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return createFileMetadataFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
        return null;
    }

    /**
     * Find file metadata by file name
     */
    public FileMetadata findFileMetadataByName(String fileName) {
        String sql = "SELECT * FROM file_metadata WHERE file_name = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, fileName);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return createFileMetadataFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
        return null;
    }

    /**
     * Find file metadata by file hash
     */
    public FileMetadata findFileMetadataByHash(String fileHash) {
        String sql = "SELECT * FROM file_metadata WHERE file_hash = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, fileHash);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return createFileMetadataFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
        return null;
    }

    /**
     * Get all file metadata
     */
    public List<FileMetadata> getAllFileMetadata() {
        String sql = "SELECT * FROM file_metadata ORDER BY upload_time DESC";
        List<FileMetadata> metadataList = new ArrayList<>();

        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                metadataList.add(createFileMetadataFromResultSet(rs));
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
        return metadataList;
    }

    /**
     * Find file metadata by uploader
     */
    public List<FileMetadata> findFileMetadataByUploader(String uploader) {
        String sql = "SELECT * FROM file_metadata WHERE uploader = ? ORDER BY upload_time DESC";
        List<FileMetadata> metadataList = new ArrayList<>();

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uploader);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    metadataList.add(createFileMetadataFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
        return metadataList;
    }

    /**
     * Check if file exists using FileMetadata (more secure approach)
     */
    public boolean isFileExists(FileMetadata metadata) {
        String sql = "SELECT 1 FROM file_metadata WHERE file_name = ? OR file_hash = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, metadata.getFileName());
            pstmt.setString(2, metadata.getFileHash());

            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
    }

    /**
     * Remove file metadata
     *
     * @throws DatabaseException for any database operation failures
     */
    public boolean removeFile(String fileId) {
        String deleteMetadataSql = "DELETE FROM file_metadata WHERE id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(deleteMetadataSql)) {
            pstmt.setString(1, fileId);
            boolean deleted = pstmt.executeUpdate() > 0;

            if (deleted) {
                log.debug("Removed file: {}", fileId);
            }
            return deleted;

        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
    }

    /**
     * Remove file metadata using FileMetadata with precise targeting by ID, name,
     * and hash.
     * Provides additional verification to prevent accidental deletions.
     *
     * @throws DatabaseException for any database operation failures
     */
    public boolean removeFile(FileMetadata metadata) {
        // Delete file metadata using both file_name and file_hash for more precise
        // targeting
        String deleteMetadataSql = "DELETE FROM file_metadata WHERE id = ? AND file_name = ? AND file_hash = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(deleteMetadataSql)) {
            pstmt.setString(1, metadata.getId());
            pstmt.setString(2, metadata.getFileName());
            pstmt.setString(3, metadata.getFileHash());
            boolean deleted = pstmt.executeUpdate() > 0;

            if (deleted) {
                log.debug("Removed file: {} ({})", metadata.getFileName(), metadata.getId());
            }
            return deleted;

        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
    }

    /**
     * Clear all data from the database (for testing)
     *
     * @throws DatabaseException for any database operation failures
     */
    public void clearAllData() {
        String sql = "DELETE FROM file_metadata";

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
            log.debug("Cleared all data from database");
        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }
    }

    /**
     * Get database statistics Executes multiple SQLite queries to gather
     * comprehensive statistics.
     *
     * @throws DatabaseException for any query execution failures
     */
    public java.util.Map<String, Object> getStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();

        try {
            // Count total files
            String countFilesSql = "SELECT COUNT(*) FROM file_metadata";
            try (Statement stmt = connection.createStatement();
                    ResultSet rs = stmt.executeQuery(countFilesSql)) {
                if (rs.next()) {
                    stats.put("totalFiles", rs.getInt(1));
                }
            }

        } catch (SQLException e) {
            throw exceptionTranslator.translate(e);
        }

        return stats;
    }

    /**
     * Helper method to create FileMetadata from ResultSet
     */
    private FileMetadata createFileMetadataFromResultSet(ResultSet rs) throws SQLException {
        FileMetadata metadata = new FileMetadata();
        metadata.setId(rs.getString("id"));
        metadata.setFileName(rs.getString("file_name"));
        metadata.setFileHash(rs.getString("file_hash"));
        metadata.setUploadTime(rs.getObject("upload_time", LocalDateTime.class));
        metadata.setUploader(rs.getString("uploader"));
        metadata.setFileSize(rs.getLong("file_size"));
        metadata.setFilePath(rs.getString("file_path"));
        metadata.setDescription(rs.getString("description"));
        return metadata;
    }

    /**
     * Close database connection
     */
    public void close() throws SQLException {
        if (connection != null) {
            try {
                connection.close();
                log.info("Database connection closed");
            } catch (SQLException e) {
                log.error("Error closing database connection: {}", e.getMessage());
                throw e;
            }
        }
    }

    /**
     * Get database connection for advanced operations
     */
    public Connection getConnection() {
        return connection;
    }

    /**
     * Check database connection status
     */
    @Override
    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            log.debug("Database connection check failed: {}", e.getMessage());
            return false;
        }
    }
}
