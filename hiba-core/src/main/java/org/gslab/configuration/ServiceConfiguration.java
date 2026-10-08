package org.gslab.configuration;

import java.util.List;
import org.json.JSONObject;

public class ServiceConfiguration {

    private final String serviceName;
    private final String type;
    private final List<String> inputs;
    private JSONObject resources;
    private final JSONObject methods;

    public ServiceConfiguration(
            String serviceName, String type, List<String> inputs, JSONObject resources, JSONObject methods) {
        this.serviceName = serviceName;
        this.type = type;
        this.inputs = inputs;
        this.resources = resources;
        this.methods = methods;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getType() {
        return type;
    }

    public List<String> getInputs() {
        return inputs;
    }

    public JSONObject getResources() {
        return resources;
    }

    public void setResources(JSONObject resources) {
        this.resources = resources;
    }

    public JSONObject getMethods() {
        return methods;
    }
}
