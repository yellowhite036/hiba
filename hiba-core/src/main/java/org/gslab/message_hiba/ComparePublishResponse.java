package org.gslab.message_hiba;

public class ComparePublishResponse extends Message {

    private String publishID = "";
    private String localName = "";

    public ComparePublishResponse(String localName, String publishName) {
        this.localName = localName;
        this.publishID = publishName;
    }

    public String getLocalName() {
        return localName;
    }

    public String getPublishID() {
        return publishID;
    }
}
