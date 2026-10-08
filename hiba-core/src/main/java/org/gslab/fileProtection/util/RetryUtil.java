package org.gslab.fileProtection.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Unified retry utility - Linus style: one tool does one thing well
 * Replaces scattered retry logic with a single, clear implementation
 */
@Slf4j
public class RetryUtil {

    // Default retry configuration - can be overridden via system properties
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_BASE_DELAY_MS = 500;
    private static final int DEFAULT_ASYNC_MAX_RETRIES = 10;
    private static final int DEFAULT_ASYNC_BASE_DELAY_MS = 30000;

    private RetryUtil() {
        // Utility class
    }

    /**
     * Execute operation with synchronous retry using cached configuration
     *
     * @param operation Operation to retry
     * @param context   Context for logging
     * @param <T>       Return type
     * @return Result of successful operation
     * @throws Exception If all retries fail
     */
    public static <T> T executeWithRetry(Supplier<T> operation, String context) throws Exception {
        return executeWithRetry(operation, context, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY_MS);
    }

    /**
     * Execute operation with synchronous retry and custom parameters
     */
    public static <T> T executeWithRetry(Supplier<T> operation, String context, int maxRetries, int baseDelayMs)
            throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                T result = operation.get();
                if (attempt > 1) {
                    log.info("{} successful on attempt {}", context, attempt);
                }
                return result;

            } catch (Exception e) {
                lastException = e;
                log.warn("{} attempt {} failed: {}", context, attempt, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        long delay = (long) baseDelayMs * attempt;
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Retry interrupted for " + context, ie);
                    }
                }
            }
        }

        log.error("All {} retry attempts failed for {}", maxRetries, context);
        throw new Exception("All retry attempts failed for " + context, lastException);
    }

    /**
     * Execute operation with asynchronous retry using cached configuration
     */
    public static <T> CompletableFuture<T> executeWithAsyncRetry(Supplier<T> operation, String context) {
        return executeWithAsyncRetry(operation, context, DEFAULT_ASYNC_MAX_RETRIES, DEFAULT_ASYNC_BASE_DELAY_MS);
    }

    /**
     * Execute operation with asynchronous retry and custom parameters
     */
    public static <T> CompletableFuture<T> executeWithAsyncRetry(
            Supplier<T> operation, String context, int maxRetries, int baseDelayMs) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    long delay = (long) baseDelayMs * attempt;
                    Thread.sleep(delay);

                    T result = operation.get();
                    log.info("Async {} successful on attempt {}", context, attempt);
                    return result;

                } catch (Exception e) {
                    log.warn("Async {} attempt {} failed: {}", context, attempt, e.getMessage());
                    if (attempt == maxRetries) {
                        log.error("All async retry attempts failed for {}", context);
                        throw new RuntimeException("All async retry attempts failed for " + context, e);
                    }
                }
            }
            throw new RuntimeException("Unexpected retry loop exit for " + context);
        });
    }

    /**
     * Execute Runnable operation with synchronous retry using cached configuration
     */
    public static void executeWithRetry(Runnable operation, String context, int maxAttempts, int baseDelayMs)
            throws Exception {
        executeWithRetry(
                () -> {
                    operation.run();
                    return null;
                },
                context);
    }

    /**
     * Execute Runnable operation with asynchronous retry using cached configuration
     */
    public static CompletableFuture<Void> executeWithAsyncRetry(
            Runnable operation, String context, int asyncMaxAttempts, int asyncBaseDelayMs) {
        return executeWithAsyncRetry(
                        () -> {
                            operation.run();
                            return null;
                        },
                        context)
                .thenApply(v -> null);
    }
}
