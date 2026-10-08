package org.gslab.message_hiba;

public class lowerBrokerScansResponse extends Message {

    private String brokerAddress = "";

    public lowerBrokerScansResponse(String brokerAddress) {
        this.brokerAddress = brokerAddress;
    }

    public String getBrokerAddress() {
        return brokerAddress;
    }
}
