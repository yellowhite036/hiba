package org.gslab.service.mService.multicast;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.HashMap;
import java.util.Map;
import org.gslab.sdns.HiBAInitial;

public class DatagramChannelBuilder {

    private static final int MULTICAST_PORT = HiBAInitial.multicastPort;
    private static final String MULTICAST_IP = HiBAInitial.multicastIP;

    public static DatagramChannel openChannel() throws IOException {
        // create the client datagram channel
        return DatagramChannel.open();
    }

    public static DatagramChannel bindChannel(SocketAddress local) throws IOException {
        return openChannel().bind(local);
    }

    public static NetworkInterface ifaceName(String iface) throws IOException {
        return NetworkInterface.getByName(iface);
    }

    public static InetSocketAddress inetSocketAddr() {
        return new InetSocketAddress(MULTICAST_IP, MULTICAST_PORT);
    }

    // receive channel
    public static DatagramChannel receiveChannel(ProtocolFamily family) throws IOException {
        return DatagramChannel.open(family);
    }

    public static String receiveBuffer(DatagramChannel dChannel) {
        String data = "";
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
        try {
            if (dChannel != null) {
                dChannel.receive(byteBuffer);
                byteBuffer.flip(); // flip -> From write mode to read mode
                byte[] bytes = new byte[byteBuffer.limit()];
                byteBuffer.get(bytes, 0, byteBuffer.limit());
                data = new String(bytes);
                byteBuffer.clear();
            }
            return data;
        } catch (Exception e) {
            return data;
        }
    }

    // HashMap to String
    /*
    public static String convertWithStream(HashMap<String, Object> initInfo) {
        String mapAsString = initInfo.keySet().stream()
        .map(key -> key + "=" + initInfo.get(key))
        .collect(Collectors.joining(", ", "{", "}"));
        return mapAsString;
    }
    */

    public static Map<String, Object> converToMap(String data) {
        Map<String, Object> stringAsHashMap = new HashMap<>();
        try {
            String stringMap = data.replace("}", "").replace("{", "").replace(" ", "");
            String[] elements = stringMap.split(",");
            for (String parts : elements) {
                String[] keyValue = parts.split("=");
                stringAsHashMap.put(keyValue[0], keyValue[1]);
            }
            return stringAsHashMap;
        } catch (Exception e) {
            return stringAsHashMap;
        }
    }
}
