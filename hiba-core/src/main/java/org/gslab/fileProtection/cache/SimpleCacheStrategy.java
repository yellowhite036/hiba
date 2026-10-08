package org.gslab.fileProtection.cache;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.model.FileMetadata;

/**
 * Simple cache implementation - Linus style: do one thing well
 * Thread-safe, memory-based cache for FileMetadata
 */
@Slf4j
public class SimpleCacheStrategy implements CacheStrategy {

    private final Map<String, FileMetadata> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<FileMetadata> get(String fileHash) {
        FileMetadata metadata = cache.get(fileHash);
        if (metadata != null) {
            log.debug("Cache hit for: {}", fileHash);
        }
        return Optional.ofNullable(metadata);
    }

    @Override
    public void put(String fileHash, FileMetadata metadata) {
        cache.put(fileHash, metadata);
        log.debug("Cached metadata for: {}", fileHash);
    }

    @Override
    public void evict(String fileHash) {
        cache.remove(fileHash);
        log.debug("Evicted from cache: {}", fileHash);
    }

    @Override
    public void clear() {
        cache.clear();
        log.debug("Cache cleared");
    }
}
