package com.socomni.hiba.service;

import com.socomni.hiba.service.Taker;

public class NtpTaker implements Taker {

    public NtpTaker() {

    }

    public void setRequestInformation(Map<String, Object> requestInformation) {

    }

    public Map<String, Object> getRequestInformation() {
        // Add implementation here if needed
        throw new UnsupportedOperationException("Method not implemented");
    }

    public Taker getNewInstance() {
        // Add implementation here if needed
        throw new UnsupportedOperationException("Method not implemented");
    }

    public boolean checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier) {
        return true;
    }
}
