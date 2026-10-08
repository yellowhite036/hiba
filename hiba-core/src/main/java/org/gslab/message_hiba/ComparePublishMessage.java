package org.gslab.message_hiba;

public class ComparePublishMessage extends Message {

    private String publishID = "";
    private String localName = "";

    public ComparePublishMessage(String localName, String publishName) {
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
