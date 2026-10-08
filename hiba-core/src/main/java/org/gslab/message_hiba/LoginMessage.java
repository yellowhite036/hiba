package org.gslab.message_hiba;

public class LoginMessage extends Message {

    private final String name;
    private final String serverIp;
    private final int serverPort;

    public LoginMessage(String name, String serverIp, int serverPort) {
        this.name = name;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
    }

    public String getName() {
        return name;
    }

    public String getServerIp() {
        return serverIp;
    }

    public int getServerPort() {
        return serverPort;
    }
}
