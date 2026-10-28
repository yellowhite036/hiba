package org.gslab.mService;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class NTPServer implements Runnable {

    private ServerSocket server;
    private long t1;
    private long t2;

    public NTPServer() {}

    public void run() {
        int socketPort = 9081;
        try {
            server = new ServerSocket(socketPort);
            Thread listener = new Thread(() -> {
                while (true) {
                    try {
                        System.out.println("HiBA BIO Socket starts listening...");
                        Socket socket = server.accept();
                        t1 = System.currentTimeMillis();
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

    public boolean receiveFile(Socket socket) {
        Map<String, Object> response = new HashMap<>();
        ObjectInputStream streamOI = null;
        ObjectOutputStream streamOO = null;

        try {
            streamOI = new ObjectInputStream(socket.getInputStream());
            t2 = System.currentTimeMillis();

            response.put("t1", t1);
            response.put("t2", t2);
            streamOO = new ObjectOutputStream(socket.getOutputStream());
            streamOO.writeObject(response);
            streamOO.flush();
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
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    public static void main(String[] args) {
        new Thread(new NTPServer()).start();
    }
}
