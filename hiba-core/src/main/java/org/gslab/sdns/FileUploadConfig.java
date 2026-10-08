package org.gslab.sdns;

/**
 * Configuration for HTTP file upload handling.
 * Defines memory thresholds and size limits to prevent resource exhaustion.
 */
public class FileUploadConfig {

    /**
     * Memory threshold before spilling to disk.
     * Files larger than this will be written to temp files instead of heap.
     *
     * For large file scenarios (100MB-1GB), keeping this at 1MB ensures
     * all files immediately go to disk, preventing heap exhaustion.
     *
     * Default: 1MB
     */
    public static final long MEMORY_THRESHOLD = 1024 * 1024; // 1MB

    /**
     * Maximum size for a single uploaded file.
     *
     * Increased to 2GB to support large file uploads (100MB-1GB range).
     * Note: Limited by Integer.MAX_VALUE (2GB) due to Netty's HttpObjectAggregator constraint.
     *
     * Default: 2GB
     */
    public static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024; // 2GB

    /**
     * Maximum total size for entire HTTP request (all files + form data).
     *
     * Set to match MAX_FILE_SIZE assuming single large file per request.
     * For multiple concurrent large files, increase proportionally.
     *
     * Default: 2GB
     */
    public static final long MAX_REQUEST_SIZE = 2L * 1024 * 1024 * 1024; // 2GB

    /**
     * Prefix for temporary upload files.
     */
    public static final String TEMP_FILE_PREFIX = "hiba-upload-";

    /**
     * Suffix for temporary upload files.
     */
    public static final String TEMP_FILE_SUFFIX = ".tmp";

    /**
     * Maximum age of temporary files before cleanup (milliseconds).
     *
     * Reduced to 4 hours for large files:
     * - 1GB upload at 10Mbps: ~13 minutes
     * - Processing time: varies by service
     * - 4 hours provides ample buffer while avoiding excessive disk usage
     *
     * Default: 4 hours
     */
    public static final long TEMP_FILE_MAX_AGE_MS = 4 * 60 * 60 * 1000; // 4 hours

    /**
     * Cleanup task interval (milliseconds).
     *
     * Increased frequency (30 min vs 1 hour) for large file scenarios
     * to avoid disk space exhaustion with concurrent 1GB uploads.
     *
     * Default: 30 minutes
     */
    public static final long CLEANUP_INTERVAL_MS = 30 * 60 * 1000; // 30 minutes

    private FileUploadConfig() {
        // Utility class, prevent instantiation
    }
}
