package org.gslab.message_hiba;

import java.util.Map;
import org.gslab.model.RequestId;

public class RegisteMessage extends Message {

    private final String requestName;
    private final RequestId requestId;
    private final long joinTime;
    private final long deadline;
    private final Map<String, Object> requestInformation;

    public RegisteMessage(
            String requestName,
            RequestId requestId,
            long deadline,
            long joinTime,
            Map<String, Object> requestInformation) {
        this.requestName = requestName;
        this.requestId = requestId;
        this.joinTime = joinTime;
        this.deadline = deadline;
        this.requestInformation = requestInformation;
    }

    public long getDeadline() {
        return deadline;
    }

    public long getJoinTime() {
        return joinTime;
    }

    public String getRequestName() {
        return requestName;
    }

    public RequestId getRequestId() {
        return requestId;
    }

    public Map<String, Object> getRequestInformation() {
        return requestInformation;
    }
}
