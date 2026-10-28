package org.gslab.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

public class CPUInfo {

    public static Map<String, String> getCPUInfo() {
        Map<String, String> response = new HashMap<>();
        String cpuClock = "1";
        String cpuCore = "1";
        try {
            /*
             * 測試了同時下兩個指令 lscpu ; nproc 不知為何輸出為null
             * 也測試了lscpu | grep CPU max 等 grep 的指令, 輸出也為null
             * 姑且複製兩次exec, 直接下兩組命令, 要趕論文, 先頂著用
             */
            Process child;
            String command = "lscpu";
            child = Runtime.getRuntime().exec(command);
            BufferedReader Info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = Info.readLine()) != null) {
                if (content.contains("CPU max")) {
                    cpuClock = Float.toString(Float.parseFloat(content.replaceAll("[^\\d.]+", "")) / 1000);
                }
            }
            String command2 = "nproc";
            child = Runtime.getRuntime().exec(command2);
            BufferedReader Info1 = new BufferedReader(new InputStreamReader(child.getInputStream()));
            while ((content = Info1.readLine()) != null) {
                cpuCore = content;
            }
            response.put("core", cpuCore);
            response.put("clock", cpuClock);
            // System.out.println("Core: " + cpuCore + "  Clock: "+ cpuClock + " MHz");

            createJson(response, "cpu");

            return response;
        } catch (IOException e) {
            e.printStackTrace();
            response.put("core", cpuCore);
            response.put("clock", cpuClock);
            return response;
        }
    }

    public static String getTOPS(String clock, String core) {
        Map<String, String> response = new HashMap<>();
        String flops = "0";
        try {
            Process child;
            String command = "python3 ./scripts/CpuPrediction.py " + clock + " " + core;
            child = Runtime.getRuntime().exec(command);
            BufferedReader Info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = Info.readLine()) != null) {
                flops = String.format("%.7f", Double.parseDouble(content));
            }
            response.put("score", flops);
            createJson(response, "score");
            return flops;
        } catch (IOException e) {
            e.printStackTrace();
            return flops;
        }
    }

    public static void createJson(Map<String, String> info, String fileName) {
        JSONObject json = new JSONObject();
        FileWriter cpuFile;
        File device = new File("./device");
        if (!device.exists()) {
            device.mkdir();
        }
        try {
            for (String context : info.keySet()) {
                json.put(context, info.get(context));
                json.put(context, info.get(context));
            }
            cpuFile = new FileWriter("./device/" + fileName + ".json");
            BufferedWriter bw = new BufferedWriter(cpuFile);
            bw.write(json.toString());
            bw.flush();
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
