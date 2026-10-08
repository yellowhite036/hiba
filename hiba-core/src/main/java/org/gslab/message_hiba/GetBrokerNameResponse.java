package org.gslab.message_hiba;

public class GetBrokerNameResponse extends Message {

    private final String name;

    public GetBrokerNameResponse(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
