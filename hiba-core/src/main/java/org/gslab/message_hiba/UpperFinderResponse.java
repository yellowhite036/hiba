package org.gslab.message_hiba;

import java.util.Set;

public class UpperFinderResponse extends Message {

    private Set<String> SecondaryTierNodeList = null;
    private boolean broker = false;

    public UpperFinderResponse(boolean broker, Set<String> NodeList) {
        this.SecondaryTierNodeList = NodeList;
        this.broker = broker;
    }

    public boolean isBroker() {
        return broker;
    }

    public Set<String> getSecondaryTierNodeList() {
        return SecondaryTierNodeList;
    }
}
