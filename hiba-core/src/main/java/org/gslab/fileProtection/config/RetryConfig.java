package org.gslab.fileProtection.config;

/**
 * Retry configuration settings
 */
public class RetryConfig {

    private int maxAttempts;
    private int baseDelayMs;
    private int asyncMaxAttempts;
    private int asyncBaseDelayMs;

    public RetryConfig() {
        // Default values
        this.maxAttempts = 3;
        this.baseDelayMs = 500;
        this.asyncMaxAttempts = 10;
        this.asyncBaseDelayMs = 1000;
    }

    public RetryConfig(int maxAttempts, int baseDelayMs, int asyncMaxAttempts, int asyncBaseDelayMs) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.asyncMaxAttempts = asyncMaxAttempts;
        this.asyncBaseDelayMs = asyncBaseDelayMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getBaseDelayMs() {
        return baseDelayMs;
    }

    public void setBaseDelayMs(int baseDelayMs) {
        this.baseDelayMs = baseDelayMs;
    }

    public int getAsyncMaxAttempts() {
        return asyncMaxAttempts;
    }

    public void setAsyncMaxAttempts(int asyncMaxAttempts) {
        this.asyncMaxAttempts = asyncMaxAttempts;
    }

    public int getAsyncBaseDelayMs() {
        return asyncBaseDelayMs;
    }

    public void setAsyncBaseDelayMs(int asyncBaseDelayMs) {
        this.asyncBaseDelayMs = asyncBaseDelayMs;
    }
}
