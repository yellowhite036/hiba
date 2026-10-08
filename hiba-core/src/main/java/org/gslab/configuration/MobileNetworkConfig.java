package org.gslab.configuration;

public class MobileNetworkConfig {

    private Boolean state;
    private int connectionSignalQuality;
    private int disconnectSignalQuality;
    private String wifiInterface;
    private Boolean obu;
    private String obuRouterInterface;
    private String obuRouterAddress;
    private String obuRouterGateway;
    private int obuMulticastPort;

    public Boolean getState() {
        return state;
    }

    public void setState(Boolean state) {
        this.state = state;
    }

    public int getConnectionSignalQuality() {
        return connectionSignalQuality;
    }

    public void setConnectionSignalQuality(int connectionSignalQuality) {
        this.connectionSignalQuality = connectionSignalQuality;
    }

    public int getDisconnectSignalQuality() {
        return disconnectSignalQuality;
    }

    public void setDisconnectSignalQuality(int disconnectSignalQuality) {
        this.disconnectSignalQuality = disconnectSignalQuality;
    }

    public String getWifiInterface() {
        return wifiInterface;
    }

    public void setWifiInterface(String wifiInterface) {
        this.wifiInterface = wifiInterface;
    }

    public Boolean getObu() {
        return obu;
    }

    public void setObu(Boolean obu) {
        this.obu = obu;
    }

    public String getObuRouterInterface() {
        return obuRouterInterface;
    }

    public void setObuRouterInterface(String obuRouterInterface) {
        this.obuRouterInterface = obuRouterInterface;
    }

    public String getObuRouterAddress() {
        return obuRouterAddress;
    }

    public void setObuRouterAddress(String obuRouterAddress) {
        this.obuRouterAddress = obuRouterAddress;
    }

    public String getObuRouterGateway() {
        return obuRouterGateway;
    }

    public void setObuRouterGateway(String obuRouterGateway) {
        this.obuRouterGateway = obuRouterGateway;
    }

    public int getObuMulticastPort() {
        return obuMulticastPort;
    }

    public void setObuMulticastPort(int obuMulticastPort) {
        this.obuMulticastPort = obuMulticastPort;
    }
}
