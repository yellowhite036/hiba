package org.gslab.message_hiba;

import java.util.HashMap;
import java.util.Map;

public class PublishFinishMessage extends Message {

    private Map<String, Object> ProcessRequest = new HashMap<>();

    public PublishFinishMessage(Map<String, Object> ProcessRequest) {
        this.ProcessRequest = ProcessRequest;
    }

    public Map<String, Object> getProcessRequest() {
        return ProcessRequest;
    }
}
