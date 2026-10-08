package org.gslab.service.mService;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

public class NTPClient {

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getNTPTime(String socketAddress, int brokerPort) {
        Map<String, Object> request = new HashMap<>();
        Map<String, Object> response = new HashMap<>();
        Socket socket = new Socket();
        ObjectInputStream streamOI = null;
        ObjectOutputStream streamOO = null;

        try {
            socket.setSoTimeout(15 * 1000);
            socket.connect(new InetSocketAddress(socketAddress, brokerPort));

            streamOO = new ObjectOutputStream(socket.getOutputStream());
            streamOO.writeObject(request);
            streamOO.flush();

            streamOI = new ObjectInputStream(socket.getInputStream());
            response.putAll((Map<String, Object>) streamOI.readObject());

        } catch (Exception e) {
            System.out.println("connecting ntp server failed.");
            return null;
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
        return response;
    }

    public static String ntpTimeCalculate() {
        long t0 = System.currentTimeMillis();
        Map<String, Object> response = NTPClient.getNTPTime("127.0.0.1", 9081);
        if (!response.isEmpty()) {
            long t1 = (long) response.get("t1");
            long t2 = (long) response.get("t2");
            long t3 = System.currentTimeMillis();
            long variable = ((t1 - t0) + (t2 - t3)) / 2;
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            long time = System.currentTimeMillis() + variable;
            return format.format(time);
        } else {
            System.out.println("NTP error.");
            return null;
        }
    }

    public static boolean createSetNTPFile(String time) {
        try {
            FileWriter executeFW = new FileWriter("./setNTP.sh");
            BufferedWriter executeBW = new BufferedWriter(executeFW);
            executeFW.write("sudo date -s '" + time + "'\r\n");
            executeFW.write("clock -w");
            executeFW.close();
            executeBW.close();
            String command = "sh ./setNTP.sh";
            Runtime.getRuntime().exec(command);
            Runtime.getRuntime().exec("rm ./setNTP.sh");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) {
        String ntpTime = ntpTimeCalculate();
        if (ntpTime != null) {
            if (createSetNTPFile(ntpTime)) {
                System.out.println("NTP setup successful. current time: " + ntpTime);
            }
        }
    }
}
