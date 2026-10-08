package org.gslab.message_hiba;

public class createReverseChannelResponse extends Message {

    private final String pubKey;
    private final String deviceName;
    private final String deviceIP;
    private final Integer port;
    private final String connectionTime;

    public createReverseChannelResponse(
            String pubKey, String deviceName, String deviceIP, Integer port, String connectionTime) {
        this.pubKey = pubKey;
        this.deviceName = deviceName;
        this.deviceIP = deviceIP;
        this.port = port;
        this.connectionTime = connectionTime;
    }

    public String getConnectionTime() {
        return connectionTime;
    }

    public String getPubKey() {
        return pubKey;
    }

    public String getUpperLayerAddress() {
        return deviceIP;
    }

    public String getUpperLayerDeviceName() {
        return deviceName;
    }

    public Integer getPort() {
        return port;
    }
}
