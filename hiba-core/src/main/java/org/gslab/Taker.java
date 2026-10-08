package org.gslab;

import java.util.Map;

public abstract class Taker {

    private Map<String, Object> requestInformation;

    public Taker() {}

    public void setRequestInformation(Map<String, Object> requestInformation) {
        this.requestInformation = requestInformation;
    }

    public Map<String, Object> getRequestInformation() {
        return requestInformation;
    }

    public Taker getNewInstance() {
        return this;
    }

    public abstract boolean checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier);
}
