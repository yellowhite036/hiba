package org.gslab.Tool;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Random;
import org.gslab.service.mService.multicast.Traceroute;

public class NetworkDisconnectionTestTool {
    /*
     * 112年 HiBA論文
     * 實驗一所使用的亂數十間斷線重連腳本
     */

    public static void networkDisconnectionAndRestart(String networkInterface) {
        Random random = new Random();
        SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss");
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        // String disconnection = "sudo tc qdisc add dev " + networkInterface + " root netem loss 100%";
        // String reconnection = "sudo tc qdisc del dev " + networkInterface + " root netem" ;
        String disconnection = "sudo ip link set " + networkInterface + " down";
        String reconnection = "sudo ip link set " + networkInterface + " up";

        while (true) {
            int disconnectionTime = random.nextInt(300) + 10;

            int reconnectionTime = random.nextInt(15) + 5;
            try {
                String date = dateFormat.format(System.currentTimeMillis());
                String disconnectionLog = "./logs/Disconnection_" + date + ".log";
                FileWriter writerNetwork = new FileWriter(disconnectionLog, true);
                System.out.println(disconnectionTime + " 秒後斷線");
                Thread.sleep(disconnectionTime * 1000);
                Runtime.getRuntime().exec(disconnection);
                System.out.println(dateTimeFormat.format(System.currentTimeMillis()) + " 完成");
                writerNetwork.write(dateTimeFormat.format(System.currentTimeMillis()) + "=disconnected" + "\n");
                writerNetwork.close();

                FileWriter writerNetwork1 = new FileWriter(disconnectionLog, true);
                System.out.println(reconnectionTime + " 秒後重新連線");
                Thread.sleep(reconnectionTime * 1000);
                Runtime.getRuntime().exec(reconnection);
                System.out.println(dateTimeFormat.format(System.currentTimeMillis()) + " 完成");
                while (true) {
                    if (Traceroute.checkNetworkInterface("163.18.49.31") == null) {
                        Thread.sleep(500);
                    } else {
                        System.out.println("break");
                        break;
                    }
                }
                writerNetwork1.write(dateTimeFormat.format(System.currentTimeMillis()) + "=reconnected" + "\n");
                writerNetwork1.close();

            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        String networkInterface = args[0];
        System.out.println("Interface: " + networkInterface);
        networkDisconnectionAndRestart(networkInterface);
    }
}
