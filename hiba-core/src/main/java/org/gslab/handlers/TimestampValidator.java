package org.gslab.handlers;

import lombok.extern.slf4j.Slf4j;
import org.gslab.configuration.MessageVerificationConfig;
import org.gslab.model.MessageVerificationResult;

/**
 * 時間戳驗證器
 * <p>
 * 負責驗證訊息時間戳是否在有效範圍內，防止重放攻擊。
 * </p>
 *
 * <h2>驗證邏輯</h2>
 * <ul>
 * <li>檢查時間戳是否過期（超過 maxAgeMs）</li>
 * <li>檢查時間戳是否在未來（超過 clockSkewMs）</li>
 * </ul>
 *
 * @author TPM Test Project
 */
@Slf4j
public class TimestampValidator {

    private final MessageVerificationConfig config;

    public TimestampValidator(MessageVerificationConfig config) {
        this.config = config;
    }

    public TimestampValidator() {
        this(MessageVerificationConfig.defaultConfig());
    }

    /**
     * 驗證時間戳是否有效
     *
     * @param timestamp 訊息時間戳（毫秒）
     * @return 驗證結果
     */
    public MessageVerificationResult validate(long timestamp) {
        if (!config.isTimestampValidationEnabled()) {
            log.debug("Timestamp validation is disabled");
            return MessageVerificationResult.success();
        }

        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - timestamp;

        log.debug(
                "Timestamp validation - current time: {}, message time: {}, difference: {}ms",
                currentTime,
                timestamp,
                timeDiff);

        // 檢查是否過期（訊息時間戳太舊）
        if (timeDiff > config.getMaxAgeMs()) {
            long expiredByMs = timeDiff - config.getMaxAgeMs();
            String message = String.format(
                    "Message expired by %d seconds (allowed %d seconds)",
                    expiredByMs / 1000, config.getMaxAgeMs() / 1000);
            log.warn("Timestamp validation failed: {}", message);
            return MessageVerificationResult.failure(MessageVerificationResult.ErrorCode.TIMESTAMP_EXPIRED, message);
        }

        // 檢查是否在未來（考慮時鐘偏移）
        if (timeDiff < -config.getClockSkewMs()) {
            long futureByMs = Math.abs(timeDiff) - config.getClockSkewMs();
            String message = String.format(
                    "Message timestamp is %d seconds in the future (allowed skew %d seconds)",
                    futureByMs / 1000, config.getClockSkewMs() / 1000);
            log.warn("Timestamp validation failed: {}", message);
            return MessageVerificationResult.failure(MessageVerificationResult.ErrorCode.TIMESTAMP_FUTURE, message);
        }

        log.debug("Timestamp validation passed, time difference: {}ms", timeDiff);
        return MessageVerificationResult.success();
    }

    /**
     * 取得當前配置
     */
    public MessageVerificationConfig getConfig() {
        return config;
    }
}
