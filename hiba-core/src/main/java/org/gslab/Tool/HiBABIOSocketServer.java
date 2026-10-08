package org.gslab.Tool;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/*
 * 測試BIO Socket
 * 未來可以加入requestListener內, 用以接收本地端其他程式傳入的Request
 */

public class HiBABIOSocketServer implements Runnable {

    private final File requestDir;
    private ServerSocket server;

    public HiBABIOSocketServer(File requestDir) {
        this.requestDir = requestDir;
    }

    public void run() {
        int socketPort = 8039;
        try {
            server = new ServerSocket(socketPort);
            Thread listener = new Thread(() -> {
                while (true) {
                    try {
                        System.out.println("HiBA BIO Socket starts listening...");
                        Socket socket = server.accept();
                        if (receiveFile(socket)) {
                            System.out.println("Close BIO Socket.");
                            break;
                        }
                    } catch (Exception e) {
                    }
                }
            });
            listener.run(); // 啟動執行緒執行
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    public boolean receiveFile(Socket socket) {
        Map<String, Object> request = new HashMap<>();
        Map<String, Object> objRequest = new HashMap<>();
        // Map<String, Object> response = new HashMap<String, Object>();
        ObjectInputStream streamOI = null;
        ObjectOutputStream streamOO = null;
        FileOutputStream streamFO = null;
        DataInputStream streamDI = null;
        byte[] inputByte = null;
        boolean status = false;
        int length = 0;
        // long size;
        String fileName;

        try {
            streamOI = new ObjectInputStream(socket.getInputStream());
            request = (Map<String, Object>) streamOI.readObject();
            objRequest = (Map<String, Object>) request.get("objRequest");
            fileName = (String) request.get("fileName");
            // size = (long) request.get("size");

            System.out.println(": " + fileName);
            File streamFile = new File(this.requestDir + "/" + fileName);
            streamDI = new DataInputStream(socket.getInputStream());
            streamFO = new FileOutputStream(streamFile);
            inputByte = new byte[1024];
            while ((length = streamDI.read(inputByte, 0, inputByte.length)) > 0) {
                streamFO.write(inputByte, 0, length);
                streamFO.flush();
            }
            if (objRequest.get("transferred").equals(true)) {
                status = true;
            }
            // if (streamFile.length() != size) {
            // System.out.println("incomplete data, received file: " + streamFile.length() + "  size: " + size);
            // response.put("error", fileName);
            // streamOO = new ObjectOutputStream(socket.getOutputStream());
            // streamOO.writeObject(response);
            // streamOO.flush();
            // }
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
                if (streamFO != null) {
                    streamFO.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (streamDI != null) {
                    streamDI.close();
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
        return status;
    }
}
