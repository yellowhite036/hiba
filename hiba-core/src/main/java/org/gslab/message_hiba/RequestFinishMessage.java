package org.gslab.message_hiba;

import java.util.Map;

public class RequestFinishMessage extends Message {

    private final Map<String, Object> requestResult;

    public RequestFinishMessage(Map<String, Object> requestResult) {
        this.requestResult = requestResult;
    }

    public Map<String, Object> getRequestResult() {
        return requestResult;
    }

    @Override
    public String toString() {
        return "RequestFinishMessage{" + "requestResult=" + requestResult + '}';
    }
}
