package org.gslab.util;

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
}