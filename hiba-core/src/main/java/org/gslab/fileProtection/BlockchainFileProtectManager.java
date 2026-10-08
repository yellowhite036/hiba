package org.gslab.fileProtection;

import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.config.BlockchainFileProtectManagerConfig;
import org.gslab.fileProtection.database.DatabaseManager;
import org.gslab.fileProtection.database.impl.PostgresDatabaseManagerImpl;
import org.gslab.fileProtection.database.impl.SQLiteDatabaseManagerImpl;
import org.gslab.fileProtection.service.BlockchainService;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.fileProtection.service.factory.BlockchainServiceFactory;
import org.gslab.fileProtection.util.ConfigUtil;

/**
 * BlockchainFileProtectManager - Main entry point for managing blockchain services and file protection This class
 * provides factory
 * methods to create instances of FileProtectionService with various configurations.
 */
@Slf4j
public class BlockchainFileProtectManager {

    /**
     * Default constructor initializes the BlockchainFileProtectManager with default settings. This constructor is used when no
     * custom blockchain service or database manager is provided.
     */
    private BlockchainFileProtectManager() {}

    /**
     * Create a new instance of FileProtectionService with unified configuration.
     *
     * @param config BlockchainFileProtectManagerConfig instance
     * @return FileProtectionService instance
     * @throws RuntimeException if initialization fails
     */
    public static FileProtectionService getFileProtectionService(BlockchainFileProtectManagerConfig config) {
        try {
            // Create blockchain service from config
            BlockchainService blockchainService = BlockchainServiceFactory.createFromConfig(config);

            // Create database manager from config
            DatabaseManager databaseManager = createDatabaseManagerFromConfig(config);

            return new FileProtectionService(blockchainService, databaseManager, config);

        } catch (Exception e) {
            log.error("Failed to initialize FileProtectionService with unified configuration: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize FileProtectionService", e);
        }
    }

    /**
     * Create a new instance of FileProtectionService with default settings from configuration.
     *
     * @return FileProtectionService instance
     * @throws RuntimeException if initialization fails
     */
    public static FileProtectionService getFileProtectionService() {
        try {
            // Initialize database manager from configuration
            DatabaseManager databaseManager = createDatabaseManagerFromConfig();

            // Initialize blockchain service from environment
            return getFileProtectionService(databaseManager);

        } catch (Exception e) {
            log.error("Failed to initialize FileProtectionService with default configuration: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize FileProtectionService", e);
        }
    }

    /**
     * Create a new instance of FileProtectionService with the specified blockchain service
     *
     * @param blockchainService BlockchainService instance
     * @return FileProtectionService instance
     * @throws RuntimeException if initialization fails
     */
    public static FileProtectionService getFileProtectionService(BlockchainService blockchainService) {
        try {
            DatabaseManager databaseManager = createDatabaseManagerFromConfig();
            return new FileProtectionService(blockchainService, databaseManager);
        } catch (Exception e) {
            log.error("Failed to initialize FileProtectionService with custom blockchain service: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize FileProtectionService", e);
        }
    }

    /**
     * Create a new instance of FileProtectionService with the specified database manager
     *
     * @param databaseManager DatabaseManager instance
     * @return FileProtectionService instance
     */
    public static FileProtectionService getFileProtectionService(DatabaseManager databaseManager) {
        BlockchainService blockchainService = BlockchainServiceFactory.createFromEnvironment();
        return new FileProtectionService(blockchainService, databaseManager);
    }

    /**
     * Create a new instance of FileProtectionService with the specified blockchain service and database manager
     *
     * @param blockchainService BlockchainService instance
     * @param databaseManager   DatabaseManager instance
     * @return FileProtectionService instance
     */
    public static FileProtectionService getFileProtectionService(
            BlockchainService blockchainService, DatabaseManager databaseManager) {
        return new FileProtectionService(blockchainService, databaseManager);
    }

    /**
     * Create database manager from unified configuration
     *
     * @param config BlockchainFileProtectManagerConfig instance
     * @return DatabaseManager instance
     * @throws SQLException if database initialization fails
     */
    private static DatabaseManager createDatabaseManagerFromConfig(BlockchainFileProtectManagerConfig config)
            throws SQLException {
        String dbUrl = config.getDatabaseConfig().getUrl();
        String[] parts = dbUrl.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid database URL format: " + dbUrl);
        }
        String dbType = parts[1];

        return switch (dbType) {
            case "postgresql" -> {
                log.info("Using PostgreSQL database: {}", dbUrl);
                yield new PostgresDatabaseManagerImpl(
                        dbUrl,
                        config.getDatabaseConfig().getUsername(),
                        config.getDatabaseConfig().getPassword());
            }
            case "sqlite" -> {
                log.info("Using SQLite database: {}", dbUrl);
                yield new SQLiteDatabaseManagerImpl(dbUrl);
            }
            default -> throw new IllegalArgumentException("Unsupported database type: " + dbType);
        };
    }

    /**
     * Create database manager from configuration (backward compatibility)
     *
     * @return DatabaseManager instance
     * @throws SQLException if database initialization fails
     */
    private static DatabaseManager createDatabaseManagerFromConfig() throws SQLException {
        String dbUrl = ConfigUtil.getDatabaseUrl();
        String[] parts = dbUrl.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid database URL format: " + dbUrl);
        }
        String dbType = parts[1];

        return switch (dbType) {
            case "postgresql" -> {
                log.info("Using PostgreSQL database: {}", dbUrl);
                yield new PostgresDatabaseManagerImpl(
                        dbUrl, ConfigUtil.getDatabaseUsername(), ConfigUtil.getDatabasePassword());
            }
            case "sqlite" -> {
                log.info("Using SQLite database: {}", dbUrl);
                yield new SQLiteDatabaseManagerImpl(dbUrl);
            }
            default -> throw new IllegalArgumentException("Unsupported database type: " + dbType);
        };
    }
}
