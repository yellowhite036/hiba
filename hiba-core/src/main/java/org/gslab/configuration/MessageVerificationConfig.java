package org.gslab.configuration;

import lombok.Builder;
import lombok.Getter;

/**
 * TPM 驗證配置
 * <p>
 * 提供可配置的驗證參數，包括時間戳驗證、Nonce 驗證、錯誤處理等。
 * </p>
 *
 * @author TPM Test Project
 */
@Getter
@Builder
public class MessageVerificationConfig {

    /**
     * 預設時間容差：5 分鐘
     */
    public static final long DEFAULT_MAX_AGE_MS = 5 * 60 * 1000L;

    /**
     * 預設時鐘偏移容差：30 秒（允許客戶端與伺服器時間有微小差異）
     */
    public static final long DEFAULT_CLOCK_SKEW_MS = 30 * 1000L;

    /**
     * 時間戳最大有效期（毫秒）
     * 超過此時間的訊息將被視為過期
     */
    @Builder.Default
    private long maxAgeMs = DEFAULT_MAX_AGE_MS;

    /**
     * 允許的時鐘偏移（毫秒）
     * 用於處理客戶端與伺服器時間不完全同步的情況
     */
    @Builder.Default
    private long clockSkewMs = DEFAULT_CLOCK_SKEW_MS;

    /**
     * 是否啟用時間戳驗證
     */
    @Builder.Default
    private boolean timestampValidationEnabled = true;

    /**
     * 是否啟用 Nonce 驗證（防止重放攻擊）
     */
    @Builder.Default
    private boolean nonceValidationEnabled = true;

    /**
     * 驗證失敗時是否發送錯誤訊息給客戶端
     */
    @Builder.Default
    private boolean sendErrorResponse = true;

    /**
     * 取得預設配置
     */
    public static MessageVerificationConfig defaultConfig() {
        return MessageVerificationConfig.builder().build();
    }

    /**
     * 取得寬鬆配置（較長的時間容差，適用於測試）
     */
    public static MessageVerificationConfig relaxedConfig() {
        return MessageVerificationConfig.builder()
                .maxAgeMs(30 * 60 * 1000L) // 30 分鐘
                .clockSkewMs(60 * 1000L) // 1 分鐘
                .build();
    }

    /**
     * 取得嚴格配置（較短的時間容差，適用於高安全性場景）
     */
    public static MessageVerificationConfig strictConfig() {
        return MessageVerificationConfig.builder()
                .maxAgeMs(60 * 1000L) // 1 分鐘
                .clockSkewMs(10 * 1000L) // 10 秒
                .build();
    }

    /**
     * 取得停用時間戳驗證的配置
     */
    public static MessageVerificationConfig noTimestampValidation() {
        return MessageVerificationConfig.builder()
                .timestampValidationEnabled(false)
                .build();
    }

    /**
     * 取得停用 Nonce 驗證的配置
     */
    public static MessageVerificationConfig noNonceValidation() {
        return MessageVerificationConfig.builder().nonceValidationEnabled(false).build();
    }

    /**
     * 取得停用所有重放攻擊防護的配置（僅用於測試）
     */
    public static MessageVerificationConfig noReplayProtection() {
        return MessageVerificationConfig.builder()
                .timestampValidationEnabled(false)
                .nonceValidationEnabled(false)
                .build();
    }
}
