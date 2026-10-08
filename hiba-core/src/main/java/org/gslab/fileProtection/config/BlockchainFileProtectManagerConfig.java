package org.gslab.fileProtection.config;

/**
 * Unified configuration class for BlockchainManager
 * Following Linus principle: One clean data structure instead of scattered configurations
 */
public class BlockchainFileProtectManagerConfig {

    private DatabaseConfig databaseConfig;
    private BlockchainServiceConfig blockchainServiceConfig;
    private RetryConfig retryConfig;
    private FileProtectionConfig fileProtectionConfig;

    public BlockchainFileProtectManagerConfig() {
        this.databaseConfig = new DatabaseConfig();
        this.blockchainServiceConfig = new BlockchainServiceConfig();
        this.retryConfig = new RetryConfig();
        this.fileProtectionConfig = new FileProtectionConfig();
    }

    public BlockchainFileProtectManagerConfig(
            DatabaseConfig databaseConfig,
            BlockchainServiceConfig blockchainServiceConfig,
            RetryConfig retryConfig,
            FileProtectionConfig fileProtectionConfig) {
        this.databaseConfig = databaseConfig;
        this.blockchainServiceConfig = blockchainServiceConfig;
        this.retryConfig = retryConfig;
        this.fileProtectionConfig = fileProtectionConfig;
    }

    public DatabaseConfig getDatabaseConfig() {
        return databaseConfig;
    }

    public void setDatabaseConfig(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    public BlockchainServiceConfig getBlockchainServiceConfig() {
        return blockchainServiceConfig;
    }

    public void setBlockchainServiceConfig(BlockchainServiceConfig blockchainServiceConfig) {
        this.blockchainServiceConfig = blockchainServiceConfig;
    }

    public RetryConfig getRetryConfig() {
        return retryConfig;
    }

    public void setRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
    }

    public FileProtectionConfig getFileProtectionConfig() {
        return fileProtectionConfig;
    }

    public void setFileProtectionConfig(FileProtectionConfig fileProtectionConfig) {
        this.fileProtectionConfig = fileProtectionConfig;
    }

    /**
     * Builder pattern for easier configuration creation
     */
    public static class Builder {
        private DatabaseConfig databaseConfig = new DatabaseConfig();
        private BlockchainServiceConfig blockchainServiceConfig = new BlockchainServiceConfig();
        private RetryConfig retryConfig = new RetryConfig();
        private FileProtectionConfig fileProtectionConfig = new FileProtectionConfig();

        public Builder withDatabaseConfig(DatabaseConfig databaseConfig) {
            this.databaseConfig = databaseConfig;
            return this;
        }

        public Builder withBlockchainServiceConfig(BlockchainServiceConfig blockchainServiceConfig) {
            this.blockchainServiceConfig = blockchainServiceConfig;
            return this;
        }

        public Builder withRetryConfig(RetryConfig retryConfig) {
            this.retryConfig = retryConfig;
            return this;
        }

        public Builder withFileProtectionConfig(FileProtectionConfig fileProtectionConfig) {
            this.fileProtectionConfig = fileProtectionConfig;
            return this;
        }

        public BlockchainFileProtectManagerConfig build() {
            return new BlockchainFileProtectManagerConfig(
                    databaseConfig, blockchainServiceConfig, retryConfig, fileProtectionConfig);
        }
    }

    /**
     * Create a builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "BlockchainManagerConfig{" + "databaseConfig="
                + databaseConfig + ", blockchainServiceConfig="
                + blockchainServiceConfig + ", retryConfig="
                + retryConfig + ", fileProtectionConfig="
                + fileProtectionConfig + '}';
    }
}
