package org.gslab.Tool;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

public class CPUUsageRate {

    public static Map<String, Object> getCPURate() {
        Process child;
        String command = "bash ./scripts/getHiBASysCPURatio.sh";
        Map<String, Object> deviceInfo = new HashMap<>();
        try {
            child = Runtime.getRuntime().exec(command);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                if (content.contains("CPU_usage")) {
                    deviceInfo.put("cpuRate", content.split(":")[1]);
                } else if (content.contains("RAM_usage")) {
                    deviceInfo.put("ramRate", content.split(":")[1]);
                } else if (content.contains("RAM_available")) {
                    deviceInfo.put("ramUsed", content.split(":")[1]);
                }
            }
            return deviceInfo;
        } catch (IOException e) {
            e.printStackTrace();
            return deviceInfo;
        }
    }

    /*
     * 執行後, 會不斷寫出當前CPU與RAM使用率
     * 為實驗用腳本
     * 單獨打包成一個.jar檔執行
     */
    public static void outputInfoLog() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss");
        // long startTime = System.currentTimeMillis();

        while (true) {
            // long currentSystemTime = System.currentTimeMillis();
            String currentLogTime = dateTimeFormat.format(System.currentTimeMillis());
            String date = dateFormat.format(System.currentTimeMillis());
            Map<String, Object> deviceInfo = getCPURate();
            String cpuRate = (String) deviceInfo.get("cpuRate");
            String ramRate = (String) deviceInfo.get("ramRate");
            String ramUsed = (String) deviceInfo.get("ramUsed");

            try {

                // long elapsedTime = currentSystemTime - startTime;
                // if (elapsedTime >= 5000) {
                String cpuLog = "./logs/CPU_" + date + ".log";
                FileWriter writerCpu = new FileWriter(cpuLog, true); // 使用 true 參數表示接續寫入
                writerCpu.write(currentLogTime + " = " + cpuRate + "\n"); // 將 cpuInfo 寫入檔案
                writerCpu.close(); // 關閉檔案

                String ramLog = "./logs/RAM_" + date + ".log";
                FileWriter writerRAM = new FileWriter(ramLog, true); // 使用 true 參數表示接續寫入
                writerRAM.write(currentLogTime + " = " + ramRate + "_" + ramUsed + "\n"); // 將 cpuInfo 寫入檔案
                writerRAM.close(); // 關閉檔案
                // startTime = System.currentTimeMillis();

                Thread.sleep(5000);
            } catch (IOException | InterruptedException e) {
                System.out.println("寫入檔案時發生錯誤：" + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        outputInfoLog();
    }
}
