package org.gslab.message_hiba;

import java.util.ArrayList;

public class GetBrokerListResponse extends Message {

    private final ArrayList<String> brokerList;

    public GetBrokerListResponse(ArrayList<String> brokerList) {
        this.brokerList = brokerList;
    }

    public ArrayList<String> getBrokerList() {
        return brokerList;
    }
}
