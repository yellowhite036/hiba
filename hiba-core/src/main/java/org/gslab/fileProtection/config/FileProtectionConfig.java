package org.gslab.fileProtection.config;

/**
 * File protection configuration settings (Not implemented yet)
 */
public class FileProtectionConfig {

    private boolean verifyOnUpload;
    private boolean backupEnabled;
    private String backupInterval;
    private boolean enableDatabaseRetry; // Is implemented
    private boolean enableBlockchainRetry;

    public FileProtectionConfig() {
        // Default values
        this.verifyOnUpload = true;
        this.backupEnabled = false;
        this.backupInterval = "24h";
        this.enableDatabaseRetry = false;
        this.enableBlockchainRetry = false;
    }

    public FileProtectionConfig(
            boolean verifyOnUpload,
            boolean backupEnabled,
            String backupInterval,
            boolean enableDatabaseRetry,
            boolean enableBlockchainRetry) {
        this.verifyOnUpload = verifyOnUpload;
        this.backupEnabled = backupEnabled;
        this.backupInterval = backupInterval;
        this.enableDatabaseRetry = enableDatabaseRetry;
        this.enableBlockchainRetry = enableBlockchainRetry;
    }

    public boolean isVerifyOnUpload() {
        return verifyOnUpload;
    }

    public void setVerifyOnUpload(boolean verifyOnUpload) {
        this.verifyOnUpload = verifyOnUpload;
    }

    public boolean isBackupEnabled() {
        return backupEnabled;
    }

    public void setBackupEnabled(boolean backupEnabled) {
        this.backupEnabled = backupEnabled;
    }

    public String getBackupInterval() {
        return backupInterval;
    }

    public void setBackupInterval(String backupInterval) {
        this.backupInterval = backupInterval;
    }

    public boolean isEnableDatabaseRetry() {
        return enableDatabaseRetry;
    }

    public void setEnableDatabaseRetry(boolean enableDatabaseRetry) {
        this.enableDatabaseRetry = enableDatabaseRetry;
    }

    public boolean isEnableBlockchainRetry() {
        return enableBlockchainRetry;
    }

    public void setEnableBlockchainRetry(boolean enableBlockchainRetry) {
        this.enableBlockchainRetry = enableBlockchainRetry;
    }
}
