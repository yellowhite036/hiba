package org.gslab.handlers;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.gslab.model.MessageVerificationResult;

/**
 * Nonce 驗證器
 * <p>
 * 用於防止重放攻擊。每個訊息必須包含唯一的 Nonce，
 * 伺服器會記錄已使用的 Nonce 並拒絕重複使用。
 * </p>
 *
 * <h2>運作原理</h2>
 * <ol>
 * <li>客戶端發送訊息時產生唯一的 Nonce</li>
 * <li>伺服器驗證 Nonce 是否已被使用過</li>
 * <li>若未使用，記錄此 Nonce 並放行</li>
 * <li>若已使用，拒絕此訊息（可能是重放攻擊）</li>
 * <li>已記錄的 Nonce 在過期後自動清理</li>
 * </ol>
 *
 * <h2>使用範例</h2>
 *
 * <pre>{@code
 * // 產生 Nonce（客戶端）
 * String nonce = NonceValidator.generateNonce();
 *
 * // 驗證 Nonce（伺服器端）
 * NonceValidator validator = new NonceValidator();
 * VerificationResult result = validator.validateAndRecord(nonce);
 * }</pre>
 *
 * @author TPM Test Project
 */
@Slf4j
public class NonceValidator {

    /**
     * 預設 Nonce 過期時間：10 分鐘
     * 應大於時間戳最大有效期，確保所有有效訊息的 Nonce 都被記錄
     */
    private static final Duration DEFAULT_NONCE_EXPIRY = Duration.ofMinutes(10);

    /**
     * 預設最大快取大小
     */
    private static final long DEFAULT_MAX_SIZE = 100_000L;

    /**
     * Nonce 長度（bytes）
     */
    private static final int NONCE_LENGTH = 16;

    /**
     * 安全隨機數產生器
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 已使用的 Nonce 快取
     * Key: Nonce 字串
     * Value: 記錄時間（用於除錯）
     */
    private Cache<String, Long> usedNonces;

    /**
     * 統計：驗證總次數
     */
    private final AtomicLong totalValidations = new AtomicLong(0);

    /**
     * 統計：重放攻擊偵測次數
     */
    private final AtomicLong replayAttacksDetected = new AtomicLong(0);

    /**
     * 避免外部直接建立實例
     */
    private NonceValidator() {
        this.usedNonces = Caffeine.newBuilder()
                .expireAfterWrite(DEFAULT_NONCE_EXPIRY)
                .maximumSize(DEFAULT_MAX_SIZE)
                .recordStats()
                .build();

        log.info("Nonce validator shared instance initialized");
        log.info("   Expiry time: {}", DEFAULT_NONCE_EXPIRY);
        log.info("   Max cache size: {} entries", DEFAULT_MAX_SIZE);
    }

    /**
     * 取得共享的 NonceValidator 實例
     * <p>
     * 建議在多個 Channel 之間共享同一個 NonceValidator，
     * 以確保 Nonce 驗證的一致性並防止重放攻擊。
     * </p>
     *
     * @return 共享的 NonceValidator 實例
     */
    public static NonceValidator getInstance() {
        return NonceValidatorHolder.INSTANCE;
    }

    /**
     * 產生新的 Nonce（用於客戶端）
     *
     * @return Base64 編碼的 Nonce 字串
     */
    public static String generateNonce() {
        byte[] nonceBytes = new byte[NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(nonceBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
    }

    /**
     * 驗證並記錄 Nonce
     * <p>
     * 如果 Nonce 有效（未被使用過），會將其記錄到快取中。
     * 如果 Nonce 無效（已被使用過或為空），返回失敗結果。
     * </p>
     *
     * @param nonce 要驗證的 Nonce
     * @return 驗證結果
     */
    public MessageVerificationResult validateAndRecord(String nonce) {
        totalValidations.incrementAndGet();

        // 檢查 Nonce 是否為空
        if (nonce == null || nonce.trim().isEmpty()) {
            log.warn("Nonce validation failed: Nonce is empty");
            return MessageVerificationResult.failure(
                    MessageVerificationResult.ErrorCode.NONCE_MISSING, "Message is missing Nonce");
        }

        // 檢查 Nonce 是否已被使用
        Long previousUse = usedNonces.getIfPresent(nonce);
        if (previousUse != null) {
            replayAttacksDetected.incrementAndGet();
            long timeSinceFirstUse = System.currentTimeMillis() - previousUse;
            log.warn(
                    "Nonce validation failed: Nonce already used ({}ms ago) - possible replay attack!",
                    timeSinceFirstUse);
            log.warn("   Nonce: {}...", nonce.length() > 16 ? nonce.substring(0, 16) : nonce);
            return MessageVerificationResult.failure(
                    MessageVerificationResult.ErrorCode.NONCE_REUSED, "Nonce already used, message rejected");
        }

        // 記錄此 Nonce
        usedNonces.put(nonce, System.currentTimeMillis());
        log.debug("Nonce validation passed and recorded: {}...", nonce.length() > 16 ? nonce.substring(0, 16) : nonce);

        return MessageVerificationResult.success();
    }

    /**
     * 僅驗證 Nonce 是否已被使用（不記錄）
     *
     * @param nonce 要驗證的 Nonce
     * @return true 如果 Nonce 有效（未被使用過）
     */
    public boolean isValid(String nonce) {
        if (nonce == null || nonce.trim().isEmpty()) {
            return false;
        }
        return usedNonces.getIfPresent(nonce) == null;
    }

    /**
     * 取得已記錄的 Nonce 數量
     */
    public long getRecordedNonceCount() {
        return usedNonces.estimatedSize();
    }

    /**
     * 取得驗證總次數
     */
    public long getTotalValidations() {
        return totalValidations.get();
    }

    /**
     * 取得偵測到的重放攻擊次數
     */
    public long getReplayAttacksDetected() {
        return replayAttacksDetected.get();
    }

    /**
     * 列印統計資訊
     */
    public void printStats() {
        log.info("=====================================================================");
        log.info("                     Nonce Validator Statistics                      ");
        log.info("=====================================================================");
        log.info("  Recorded Nonce count: {:,}                                         ", usedNonces.estimatedSize());
        log.info("  Total validations: {:,}                                            ", totalValidations.get());
        log.info("  Replay attacks detected: {:,}                                      ", replayAttacksDetected.get());
        log.info(
                "  Cache hit rate: {:.2f}%                                            ",
                usedNonces.stats().hitRate() * 100);
        log.info("=====================================================================");
    }

    /**
     * 清空所有記錄（僅用於測試）
     */
    public void clear() {
        usedNonces.invalidateAll();
        totalValidations.set(0);
        replayAttacksDetected.set(0);
        log.warn("Nonce validator cleared (for testing only)");
    }

    /**
     * 共享實例持有器（Initialization-on-demand holder idiom）
     * 線程安全且延遲初始化
     */
    private static class NonceValidatorHolder {
        private static final NonceValidator INSTANCE = new NonceValidator();
    }
}
