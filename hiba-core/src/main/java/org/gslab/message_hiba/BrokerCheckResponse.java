package org.gslab.message_hiba;

public class BrokerCheckResponse extends Message {

    private String brokerHost = "";

    public BrokerCheckResponse(String brokerHost) {
        this.brokerHost = brokerHost;
    }

    public String getBrokerHost() {
        return brokerHost;
    }
}
