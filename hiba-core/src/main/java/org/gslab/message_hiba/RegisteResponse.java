package org.gslab.message_hiba;

import org.gslab.model.RequestId;

public class RegisteResponse extends Message {

    private final String requestName;
    private final RequestId requestId;
    private final boolean success;
    private final String handleBrokerHost;

    public RegisteResponse(String requestName, RequestId requestId, boolean isSuccess, String handleBrokerHost) {
        this.requestName = requestName;
        this.requestId = requestId;
        this.success = isSuccess;
        this.handleBrokerHost = handleBrokerHost;
    }

    public String getRequestName() {
        return requestName;
    }

    public RequestId getRequestId() {
        return requestId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getHandleBrokerHost() {
        return handleBrokerHost;
    }
}
