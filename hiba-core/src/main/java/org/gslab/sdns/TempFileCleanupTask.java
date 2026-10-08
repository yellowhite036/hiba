package org.gslab.sdns;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled task to cleanup orphaned temporary upload files.
 *
 * Purpose: Safety net for files that weren't properly cleaned by HttpPostRequestDecoder.destroy()
 * due to exceptions, crashes, or other abnormal conditions.
 *
 * Behavior:
 * - Runs every hour (configurable)
 * - Deletes temp files older than 24 hours (configurable)
 * - Only touches files matching upload temp pattern
 * - Logs all deletion activity
 */
public class TempFileCleanupTask {

    private static final Logger logger = LoggerFactory.getLogger(TempFileCleanupTask.class);

    private final ScheduledExecutorService scheduler;
    private final Path tempDirectory;
    private final long maxAgeMs;

    /**
     * Create cleanup task with default settings.
     */
    public TempFileCleanupTask() {
        this(Paths.get(System.getProperty("java.io.tmpdir")), FileUploadConfig.TEMP_FILE_MAX_AGE_MS);
    }

    /**
     * Create cleanup task with custom settings.
     *
     * @param tempDirectory Directory to scan for temp files
     * @param maxAgeMs Maximum age of files before deletion (milliseconds)
     */
    public TempFileCleanupTask(Path tempDirectory, long maxAgeMs) {
        this.tempDirectory = tempDirectory;
        this.maxAgeMs = maxAgeMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TempFileCleanup");
            t.setDaemon(true); // Don't prevent JVM shutdown
            return t;
        });
    }

    /**
     * Start the cleanup task.
     */
    public void start() {
        logger.atInfo()
                .addKeyValue("directory", tempDirectory)
                .addKeyValue("maxAge", maxAgeMs)
                .addKeyValue("interval", FileUploadConfig.CLEANUP_INTERVAL_MS)
                .log(
                        "Starting temp file cleanup task: directory={}, maxAge={}ms, interval={}ms",
                        tempDirectory,
                        maxAgeMs,
                        FileUploadConfig.CLEANUP_INTERVAL_MS);

        scheduler.scheduleAtFixedRate(
                this::cleanup,
                FileUploadConfig.CLEANUP_INTERVAL_MS, // Initial delay
                FileUploadConfig.CLEANUP_INTERVAL_MS, // Repeat interval
                TimeUnit.MILLISECONDS);
    }

    /**
     * Stop the cleanup task.
     */
    public void stop() {
        logger.atInfo().log("Stopping temp file cleanup task");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Perform cleanup scan.
     */
    private void cleanup() {
        try {
            logger.atDebug()
                    .addKeyValue("tempDirectory", tempDirectory)
                    .log("Starting temp file cleanup scan in: {}", tempDirectory);
            long cutoffTime = System.currentTimeMillis() - maxAgeMs;
            int deletedCount = 0;
            int errorCount = 0;

            try (DirectoryStream<Path> stream =
                    Files.newDirectoryStream(tempDirectory, FileUploadConfig.TEMP_FILE_PREFIX + "*")) {

                for (Path file : stream) {
                    try {
                        if (shouldDelete(file, cutoffTime)) {
                            Files.delete(file);
                            deletedCount++;
                            logger.atInfo()
                                    .addKeyValue("fileName", file.getFileName())
                                    .addKeyValue(
                                            "ageMs",
                                            System.currentTimeMillis()
                                                    - Files.getLastModifiedTime(file)
                                                            .toMillis())
                                    .log(
                                            "Deleted orphaned temp file: {} (age: {}ms)",
                                            file.getFileName(),
                                            System.currentTimeMillis()
                                                    - Files.getLastModifiedTime(file)
                                                            .toMillis());
                        }
                    } catch (IOException e) {
                        errorCount++;
                        logger.atWarn()
                                .addKeyValue("file", file)
                                .setCause(e)
                                .log("Failed to delete temp file: {}", file);
                    }
                }
            }

            if (deletedCount > 0 || errorCount > 0) {
                logger.atInfo()
                        .addKeyValue("deletedCount", deletedCount)
                        .addKeyValue("errorCount", errorCount)
                        .log("Temp file cleanup completed: deleted={}, errors={}", deletedCount, errorCount);
            } else {
                logger.atDebug().log("Temp file cleanup completed: no files to delete");
            }

        } catch (IOException e) {
            logger.atError().setCause(e).log("Failed to scan temp directory for cleanup");
        } catch (Exception e) {
            logger.atError().setCause(e).log("Unexpected error during temp file cleanup");
        }
    }

    /**
     * Check if file should be deleted.
     *
     * @param file File to check
     * @param cutoffTime Cutoff time (files older than this will be deleted)
     * @return true if file should be deleted
     */
    private boolean shouldDelete(Path file, long cutoffTime) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }

        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        long fileTime = attrs.lastModifiedTime().toMillis();

        return fileTime < cutoffTime;
    }
}
