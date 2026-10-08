package org.gslab.message_hiba;

import java.util.Map;

public class TaskFinishMessage extends Message {

    private Map<String, Object> taskResult = null;

    public TaskFinishMessage(Map<String, Object> taskResult) {
        this.taskResult = taskResult;
    }

    public Map<String, Object> getAttachment() {
        return taskResult;
    }
}
