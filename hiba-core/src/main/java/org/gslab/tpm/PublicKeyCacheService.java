package org.gslab.tpm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * TPM 公鑰快取服務
 * <p>
 * 使用 Caffeine 實現高效能的公鑰快取機制。
 * 這是一個純快取層，不直接依賴區塊鏈服務。
 * 當快取未命中時，可透過提供的 loader 函數從外部來源載入。
 * </p>
 *
 * <h2>快取策略</h2>
 * <ul>
 * <li>最大容量：1000 個設備公鑰</li>
 * <li>過期時間：寫入後 1 小時過期</li>
 * <li>存取刷新：存取後延長 30 分鐘</li>
 * <li>統計資訊：啟用快取命中率統計</li>
 * </ul>
 *
 * <h2>使用範例</h2>
 *
 * <pre>{@code
 * PublicKeyCacheService cache = new PublicKeyCacheService();
 *
 * // 方式一：僅從快取查詢
 * Optional<PublicKey> publicKey = cache.get(ekFingerprint);
 *
 * // 方式二：帶有 fallback loader（快取未命中時從區塊鏈載入）
 * Optional<PublicKey> publicKey = cache.getOrLoad(ekFingerprint,
 *         fingerprint -> blockchainService.getPublicKey(fingerprint));
 *
 * // 手動放入快取
 * cache.put(ekFingerprint, publicKey);
 *
 * // 查看快取統計
 * cache.printStats();
 * }</pre>
 *
 * @author TPM Test Project
 */
@Slf4j
public class PublicKeyCacheService {

    /**
     * 快取配置常量
     */
    private static final int MAX_CACHE_SIZE = 1000;

    private static final Duration EXPIRE_AFTER_WRITE = Duration.ofMinutes(60);
    private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(30);

    /**
     * Caffeine 快取實例
     * Key: EK Fingerprint
     * Value: PublicKey
     */
    private final Cache<String, PublicKey> cache;

    /**
     * 統計計數器
     */
    private final AtomicLong cacheHits = new AtomicLong(0);

    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong externalLoads = new AtomicLong(0);

    public PublicKeyCacheService() {
        this(MAX_CACHE_SIZE, EXPIRE_AFTER_WRITE, EXPIRE_AFTER_ACCESS);
    }

    /**
     * 建構子 - 自訂快取配置
     *
     * @param maxCacheSize      最大快取容量
     * @param expireAfterWrite  寫入後過期時間(分鐘)
     * @param expireAfterAccess 存取後刷新過期時間(分鐘)
     */
    public PublicKeyCacheService(int maxCacheSize, Duration expireAfterWrite, Duration expireAfterAccess) {
        log.info("Initializing public key cache service...");

        // 建立 Caffeine 快取
        this.cache = Caffeine.newBuilder()
                // 最大容量
                .maximumSize(maxCacheSize)
                // 寫入後過期
                .expireAfterWrite(expireAfterWrite)
                // 存取後刷新過期時間
                .expireAfterAccess(expireAfterAccess)
                // 啟用統計
                .recordStats()
                // 移除監聯器
                .removalListener((String key, PublicKey value, RemovalCause cause) -> log.debug(
                        "Cache removed: {}... (reason: {})",
                        key != null && key.length() > 16 ? key.substring(0, 16) : key,
                        cause))
                .build();

        log.info("Public key cache service initialized");
        log.info("   Max capacity: {} entries", maxCacheSize);
        log.info("   Write expiry: {}", expireAfterWrite);
        log.info("   Access expiry: {}", expireAfterAccess);
    }

    /**
     * 僅從快取取得公鑰（不會從外部載入）
     *
     * @param ekFingerprint TPM EK 指紋
     * @return 公鑰，若快取中不存在則返回 Optional.empty()
     */
    public Optional<PublicKey> get(final String ekFingerprint) {
        log.debug("Querying cache: {}...", truncate(ekFingerprint, 16));

        PublicKey cachedKey = cache.getIfPresent(ekFingerprint);

        if (cachedKey != null) {
            cacheHits.incrementAndGet();
            log.debug("Cache hit!");
            return Optional.of(cachedKey);
        }

        cacheMisses.incrementAndGet();
        log.debug("Cache miss");
        return Optional.empty();
    }

    /**
     * 手動放入快取
     * <p>
     * 用於設備註冊時，將公鑰放入快取。
     * </p>
     *
     * @param ekFingerprint TPM EK 指紋
     * @param publicKey     公鑰
     */
    public void put(final String ekFingerprint, final PublicKey publicKey) {
        log.debug("Manually adding to cache: {}...", truncate(ekFingerprint, 16));
        cache.put(ekFingerprint, publicKey);
    }

    /**
     * 使快取項目失效
     *
     * @param ekFingerprint TPM EK 指紋
     */
    public void invalidate(final String ekFingerprint) {
        log.debug("Invalidating cache: {}...", truncate(ekFingerprint, 16));
        cache.invalidate(ekFingerprint);
    }

    /**
     * 清空所有快取
     */
    public void invalidateAll() {
        log.warn("Clearing all cache entries");
        cache.invalidateAll();
    }

    /**
     * 取得快取統計資訊
     */
    public CacheStats getStats() {
        return cache.stats();
    }

    /**
     * 取得目前快取大小
     */
    public long getSize() {
        return cache.estimatedSize();
    }

    /**
     * 取得命中率
     */
    public double getHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 取得命中率字串
     */
    private String getHitRateString() {
        return String.format(
                "%.2f%% (%d/%d)", getHitRate() * 100, cacheHits.get(), cacheHits.get() + cacheMisses.get());
    }

    /**
     * 列印快取統計資訊
     */
    public void printStats() {
        CacheStats stats = cache.stats();
        log.info("=====================================================================");
        log.info("                       Cache Statistics                             ");
        log.info("=====================================================================");
        log.info("  Current cache size: {} entries                                  ", cache.estimatedSize());
        log.info("  Cache hits: {}                                                  ", cacheHits.get());
        log.info("  Cache misses: {}                                                ", cacheMisses.get());
        log.info("  External loads: {}                                              ", externalLoads.get());
        log.info("  Hit rate: {}                                                      ", getHitRateString());
        log.info("                                                                    ");
        log.info("  Caffeine Stats:                                                   ");
        log.info("    - Request count: {}                                           ", stats.requestCount());
        log.info("    - Hit rate: {}%                                             ", stats.hitRate() * 100);
        log.info("    - Load count: {}                                              ", stats.loadCount());
        log.info("    - Eviction count: {}                                          ", stats.evictionCount());
        log.info("=====================================================================");
    }

    /**
     * 截斷字串
     */
    private String truncate(final String str, final int maxLength) {
        if (str == null) {
            return "null";
        }
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
    }

    /**
     * 重置統計計數器（僅用於測試）
     */
    public void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
        externalLoads.set(0);
    }
}
