package org.gslab.message_hiba;

import org.gslab.model.RequestId;

public class WaitingRequestMessage extends Message {

    private final String requestName;
    private final RequestId requestId;

    public WaitingRequestMessage(String requestName, RequestId requestId) {
        this.requestName = requestName;
        this.requestId = requestId;
    }

    public String getRequestName() {
        return requestName;
    }

    public RequestId getRequestId() {
        return requestId;
    }
}
