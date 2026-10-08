package org.gslab.message_hiba;

import java.util.Map;
import org.gslab.model.RequestId;

public class TaskStartMessage extends Message {

    private final String requestName;
    private final RequestId requestId;
    private final Map<String, Object> taskInformation;

    public TaskStartMessage(
            final String requestName, final RequestId requestId, final Map<String, Object> taskInformation) {
        this.requestName = requestName;
        this.requestId = requestId;
        this.taskInformation = taskInformation;
    }

    public String getRequestName() {
        return requestName;
    }

    public RequestId getRequestId() {
        return requestId;
    }

    public Map<String, Object> getTaskInformation() {
        return taskInformation;
    }
}
