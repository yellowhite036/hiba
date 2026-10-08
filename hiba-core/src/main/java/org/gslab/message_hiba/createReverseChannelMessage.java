package org.gslab.message_hiba;

import java.util.ArrayList;

public class createReverseChannelMessage extends Message {

    private final String pubKey;
    private final String deviceName;
    private final String deviceIP;
    private final String localName;
    private final String connectionTime;
    private final ArrayList<String> usedPort;

    public createReverseChannelMessage(
            String pubKey,
            String deviceName,
            String deviceIP,
            String localName,
            String connectionTime,
            ArrayList<String> usedPort) {
        this.pubKey = pubKey;
        this.deviceName = deviceName;
        this.deviceIP = deviceIP;
        this.localName = localName;
        this.connectionTime = connectionTime;
        this.usedPort = usedPort;
    }

    public String getConnectionTime() {
        return connectionTime;
    }

    public String getPubKey() {
        return pubKey;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getLowerLayerAddress() {
        return deviceIP;
    }

    public String getLowerLayerNodeName() {
        return localName;
    }

    public ArrayList<String> getLowerNodeUsedPort() {
        return usedPort;
    }
}
