package org.gslab.model.response;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import org.gslab.model.RequestId;

/**
 * Unified HTTP response envelope for all services.
 * <p>
 * Design principles: - ONE success field (no more checking multiple status indicators) - NO redundancy (metadata only
 * at top level) - Clean error contract (error: null on success, error object on failure) - Service data isolated in
 * 'data' field
 */
public class UnifiedResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final SimpleDateFormat ISO_8601_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    static {
        ISO_8601_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private final String requestName;
    private final RequestId requestId;
    private final String serviceName;
    private final String timestamp;
    private final boolean success;
    private final Long executionTimeMs;
    private final T data;
    private final UnifiedError error;

    private UnifiedResponse(Builder<T> builder) {
        this.requestName = builder.requestName;
        this.requestId = builder.requestId;
        this.serviceName = builder.serviceName;
        this.timestamp = builder.timestamp != null ? builder.timestamp : formatTimestamp(new Date());
        this.success = builder.success;
        this.executionTimeMs = builder.executionTimeMs;
        this.data = builder.data;
        this.error = builder.error;
    }

    public String getRequestName() {
        return requestName;
    }

    public RequestId getRequestId() {
        return requestId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public boolean isSuccess() {
        return success;
    }

    public Long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public T getData() {
        return data;
    }

    public UnifiedError getError() {
        return error;
    }

    /**
     * Convert to Map for JSON serialization. Used by HttpRequestHandler to build response.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("requestName", requestName);
        map.put("requestId", requestId.value());
        map.put("serviceName", serviceName);
        map.put("timestamp", timestamp);
        map.put("success", success);

        if (executionTimeMs != null) {
            map.put("executionTimeMs", executionTimeMs);
        }

        // Always include data if present (for consistent format on both success and
        // error)
        if (data != null) {
            map.put("data", data);
        }

        // Include error only on failure
        if (!success && error != null) {
            map.put("error", error.toMap());
        }

        return map;
    }

    private static String formatTimestamp(Date date) {
        synchronized (ISO_8601_FORMAT) {
            return ISO_8601_FORMAT.format(date);
        }
    }

    /**
     * Builder for success responses.
     */
    public static <T> Builder<T> success(String requestName, RequestId requestId, String serviceName) {
        return new Builder<T>()
                .requestName(requestName)
                .requestId(requestId)
                .serviceName(serviceName)
                .success(true);
    }

    /**
     * Builder for error responses.
     */
    public static <T> Builder<T> error(
            String requestName, RequestId requestId, String serviceName, UnifiedError error) {
        return new Builder<T>()
                .requestName(requestName)
                .requestId(requestId)
                .serviceName(serviceName)
                .success(false)
                .error(error);
    }

    /**
     * Builder pattern for flexible construction.
     */
    public static class Builder<T> {

        private String requestName;
        private RequestId requestId;
        private String serviceName;
        private String timestamp;
        private boolean success;
        private Long executionTimeMs;
        private T data;
        private UnifiedError error;

        public Builder<T> requestName(String requestName) {
            this.requestName = requestName;
            return this;
        }

        public Builder<T> requestId(RequestId requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder<T> serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder<T> timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder<T> success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder<T> executionTimeMs(Long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
            return this;
        }

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public Builder<T> error(UnifiedError error) {
            this.error = error;
            return this;
        }

        public UnifiedResponse<T> build() {
            if (requestId == null || serviceName == null) {
                throw new IllegalStateException("requestId and serviceName are required");
            }
            if (success && data == null) {
                throw new IllegalStateException("data is required for success responses");
            }
            if (!success && error == null) {
                throw new IllegalStateException("error is required for failure responses");
            }
            return new UnifiedResponse<>(this);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "UnifiedResponse{requestId='%s', serviceName='%s', success=%s, executionTimeMs=%d}",
                requestId, serviceName, success, executionTimeMs);
    }
}
