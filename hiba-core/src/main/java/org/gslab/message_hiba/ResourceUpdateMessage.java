package org.gslab.message_hiba;

import java.util.Map;

public class ResourceUpdateMessage extends Message {

    private final Map<String, Object> resource;

    public ResourceUpdateMessage(Map<String, Object> resource) {
        this.resource = resource;
    }

    public Map<String, Object> getResource() {
        return resource;
    }
}
