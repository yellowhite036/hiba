package org.gslab.fileProtection.cache;

import java.util.Optional;
import org.gslab.fileProtection.model.FileMetadata;

/**
 * Cache strategy interface - Linus style: simple interface, single responsibility
 * Provides unified caching behavior for file metadata operations
 */
public interface CacheStrategy {

    /**
     * Get cached metadata
     */
    Optional<FileMetadata> get(String fileHash);

    /**
     * Cache metadata
     */
    void put(String fileHash, FileMetadata metadata);

    /**
     * Remove from cache
     */
    void evict(String fileHash);

    /**
     * Clear all cache
     */
    void clear();
}
