package org.gslab.model;

import lombok.Getter;

/**
 * 驗證結果
 * <p>
 * 封裝驗證過程的結果，包含成功/失敗狀態、錯誤碼和錯誤訊息。
 * </p>
 *
 * @author TPM Test Project
 */
@Getter
public class MessageVerificationResult {

    /**
     * 錯誤碼列舉
     */
    public enum ErrorCode {
        SUCCESS("SUCCESS", "驗證成功"),
        TIMESTAMP_EXPIRED("TIMESTAMP_EXPIRED", "訊息時間戳已過期"),
        TIMESTAMP_FUTURE("TIMESTAMP_FUTURE", "訊息時間戳在未來"),
        NONCE_MISSING("NONCE_MISSING", "訊息缺少 Nonce"),
        NONCE_REUSED("NONCE_REUSED", "Nonce 已被使用，疑似重放攻擊"),
        SIGNATURE_INVALID("SIGNATURE_INVALID", "簽章驗證失敗"),
        PUBLIC_KEY_MISMATCH("PUBLIC_KEY_MISMATCH", "公鑰不匹配"),
        DEVICE_NOT_REGISTERED("DEVICE_NOT_REGISTERED", "設備未註冊"),
        PARSE_ERROR("PARSE_ERROR", "封包解析錯誤"),
        INTERNAL_ERROR("INTERNAL_ERROR", "內部錯誤");

        private final String code;
        private final String defaultMessage;

        ErrorCode(String code, String defaultMessage) {
            this.code = code;
            this.defaultMessage = defaultMessage;
        }

        public String getCode() {
            return code;
        }

        public String getDefaultMessage() {
            return defaultMessage;
        }
    }

    private final boolean success;
    private final ErrorCode errorCode;
    private final String message;

    private MessageVerificationResult(boolean success, ErrorCode errorCode, String message) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * 建立成功結果
     */
    public static MessageVerificationResult success() {
        return new MessageVerificationResult(true, ErrorCode.SUCCESS, "驗證成功");
    }

    /**
     * 建立失敗結果
     */
    public static MessageVerificationResult failure(ErrorCode errorCode) {
        return new MessageVerificationResult(false, errorCode, errorCode.getDefaultMessage());
    }

    /**
     * 建立失敗結果（含自訂訊息）
     */
    public static MessageVerificationResult failure(ErrorCode errorCode, String message) {
        return new MessageVerificationResult(false, errorCode, message);
    }

    /**
     * 轉換為 JSON 錯誤回應
     */
    public String toJsonResponse() {
        return String.format(
                "{\"success\":%s,\"error\":\"%s\",\"message\":\"%s\"}", success, errorCode.getCode(), message);
    }

    @Override
    public String toString() {
        return String.format("VerificationResult{success=%s, errorCode=%s, message='%s'}", success, errorCode, message);
    }
}
