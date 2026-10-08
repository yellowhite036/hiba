package com.socomni.abstraction;

import java.util.Map;

public interface Taker {
    void setRequestInformation(Map<String, Object> requestInformation);

    Map<String, Object> getRequestInformation();

    Taker getNewInstance();

    boolean checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier);
}
