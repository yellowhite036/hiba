package org.gslab.model.response;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Standardized error structure for HTTP responses.
 * Eliminates inconsistent error reporting across services.
 */
public class UnifiedError implements Serializable {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final String message;
    private final Map<String, Object> details;

    public UnifiedError(ErrorCode code, String message) {
        this(code, message, null);
    }

    public UnifiedError(ErrorCode code, String message, Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.details = details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("code", code.name());
        map.put("message", message);
        if (details != null && !details.isEmpty()) {
            map.put("details", details);
        }
        return map;
    }

    public static UnifiedError fromMap(Map<String, Object> map) {
        if (map == null) return null;

        String codeStr = (String) map.get("code");
        ErrorCode code = codeStr != null ? ErrorCode.valueOf(codeStr) : ErrorCode.INTERNAL_ERROR;
        String message = (String) map.get("message");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) map.get("details");

        return new UnifiedError(code, message, details);
    }

    /**
     * Standard error codes for all services.
     * Clear, actionable error categories.
     */
    public enum ErrorCode {
        // Request errors (4xx equivalent)
        INVALID_REQUEST,
        MISSING_PARAMETER,
        INVALID_PARAMETER,
        FILE_NOT_FOUND,
        RESOURCE_NOT_FOUND,
        UNAUTHORIZED,
        FORBIDDEN,

        // Execution errors (5xx equivalent)
        TIMEOUT,
        INTERNAL_ERROR,
        SERVICE_UNAVAILABLE,
        BLOCKCHAIN_ERROR,
        NETWORK_ERROR,
        STORAGE_ERROR,

        // Transfer errors
        TRANSFER_FAILED,
        CHECKSUM_MISMATCH,
        INCOMPLETE_TRANSFER,

        // Node errors
        NODE_UNREACHABLE,
        NODE_BUSY,
        CONSENSUS_FAILED
    }

    @Override
    public String toString() {
        return String.format("UnifiedError{code=%s, message='%s', details=%s}", code, message, details);
    }
}
