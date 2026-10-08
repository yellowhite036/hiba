package org.gslab.sdns;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.gslab.model.RequestId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpRequestResultListener {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestResultListener.class);

    private final RequestId requestId;
    private final CompletableFuture<Map<String, Object>> resultFuture;

    public HttpRequestResultListener(RequestId requestId, CompletableFuture<Map<String, Object>> resultFuture) {
        this.requestId = requestId;
        this.resultFuture = resultFuture;
    }

    public void onResult(Map<String, Object> result) {
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .log("HttpRequestResultListener: Received result for requestId: {}", requestId);
        try {
            resultFuture.complete(Objects.requireNonNullElse(result, Collections.emptyMap()));
        } catch (Exception e) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .setCause(e)
                    .log("HttpRequestResultListener: Error setting result for requestId: {}", requestId);
            resultFuture.completeExceptionally(e);
        }
    }

    public void onError(String errorMessage) {
        logger.atError()
                .addKeyValue("requestId", requestId)
                .addKeyValue("errorMessage", errorMessage)
                .log("HttpRequestResultListener: Error for requestId: {}, message: {}", requestId, errorMessage);
        resultFuture.completeExceptionally(new RuntimeException("Request failed: " + errorMessage));
    }

    public RequestId getRequestId() {
        return requestId;
    }
}
