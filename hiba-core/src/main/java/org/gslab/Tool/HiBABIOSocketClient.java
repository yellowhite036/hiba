package org.gslab.Tool;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;

/*
 * 測試BIO Socket
 * 未來可以加入requestListener內, 用以接收本地端其他程式傳入的Request
 */

public class HiBABIOSocketClient {

    public static Map<String, Object> BIOTransfer(
            File packet, Map<String, Object> objRequest, SocketAddress socketAddress, int brokerPort) {
        Map<String, Object> request = new HashMap<>();
        // Map<String, Object> serverResponse = new HashMap<>();
        Map<String, Object> transportResponse = new HashMap<>();
        // ArrayList<String> success = new ArrayList<String>(); // 傳輸成功的檔案
        // ArrayList<String> error = new ArrayList<String>(); // 傳輸失敗的檔案
        Socket socket = new Socket();
        FileInputStream streamFI = null;
        DataOutputStream streamDO = null;
        ObjectInputStream streamOI = null;
        ObjectOutputStream streamOO = null;
        byte[] sendBytes = null;
        int length = 0;
        try {
            socket.setSoTimeout(30 * 1000);
            socket.connect(new InetSocketAddress(((InetSocketAddress) socketAddress).getAddress(), 8039));
            request.put("fileName", packet.getName());
            request.put("size", packet.length());
            request.put("objRequest", objRequest);

            streamOO = new ObjectOutputStream(socket.getOutputStream());
            streamOO.writeObject(request);
            streamOO.flush();
            streamDO = new DataOutputStream(socket.getOutputStream());
            streamFI = new FileInputStream(packet);
            sendBytes = new byte[1024];
            while ((length = streamFI.read(sendBytes, 0, sendBytes.length)) > 0) {
                streamDO.write(sendBytes, 0, length);
                streamDO.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (streamOI != null) {
                    streamOI.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (streamOO != null) {
                    streamOO.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (streamFI != null) {
                    streamFI.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return transportResponse;
    }
}
