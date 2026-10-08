package org.gslab.message_hiba;

public class SyncMessage extends Message {

    private final String name;
    private final String serverIp;
    private final int serverPort;
    private final boolean Login; // false logout

    public SyncMessage(String name, String serverIp, int serverPort, boolean isLogin) {
        this.name = name;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.Login = isLogin;
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

    public boolean isLogin() {
        return Login;
    }
}
