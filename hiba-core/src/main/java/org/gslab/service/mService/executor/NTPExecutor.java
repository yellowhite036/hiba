package org.gslab.service.mService.executor;

import io.netty.channel.Channel;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import org.gslab.Executor;
import org.gslab.sdns.HiBAInitial;
import org.gslab.service.mService.NTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NTPExecutor extends Executor {

    private static final Logger logger = LoggerFactory.getLogger(NTPExecutor.class);

    @Override
    public Executor getNewInstance() {
        return this;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> information, Channel brokerChannel) {
        Map<String, Object> resultInformation = new HashMap<>();
        long startTime = System.currentTimeMillis();
        resultInformation.put("startTime", startTime);

        String ntpTime = ntpTimeCalculate();
        if (ntpTime != null) {
            if (createSetNTPFile(ntpTime)) {
                logger.atInfo().log("NTP setup successful. current time: {}", ntpTime);
            }
        } else {
            logger.atInfo().log(
                    "NTP setup failed. current time: {}",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(System.currentTimeMillis()));
        }

        long endTime = System.currentTimeMillis();
        long exeTime = endTime - startTime;
        resultInformation.put("endTime", endTime);
        resultInformation.put("exeTime", exeTime);
        return resultInformation;
    }

    @Override
    public Map<String, Object> brokerExecute(Map<String, Object> information) {
        Map<String, Object> resultInformation = new HashMap<>();
        long startTime = System.currentTimeMillis();
        resultInformation.put("startTime", startTime);

        String ntpTime = ntpTimeCalculate();
        if (ntpTime != null) {
            if (createSetNTPFile(ntpTime)) {
                logger.atInfo().log("NTP setup successful. current time: {}", ntpTime);
            }
        } else {
            logger.atInfo().log(
                    "NTP setup failed. current time: {}",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(System.currentTimeMillis()));
        }

        long endTime = System.currentTimeMillis();
        long exeTime = endTime - startTime;
        resultInformation.put("endTime", endTime);
        resultInformation.put("exeTime", exeTime);
        return resultInformation;
    }

    public static String ntpTimeCalculate() {
        long t0 = System.currentTimeMillis();
        Map<String, Object> response = NTPClient.getNTPTime(HiBAInitial.accountingServer, 9081);
        if (!response.isEmpty()) {
            long t1 = (long) response.get("t1");
            long t2 = (long) response.get("t2");
            long t3 = System.currentTimeMillis();
            long variable = ((t1 - t0) + (t2 - t3)) / 2;
            logger.atInfo().log("System Time difference: {} Timestamp Millis.", variable);
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            long time = System.currentTimeMillis() + variable;
            return format.format(time);
        } else {
            logger.atInfo().log("NTP Server connecting failed, server return is NULL.");
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
            System.out.println(command);
            Runtime.getRuntime().exec(command);
            Runtime.getRuntime().exec("rm ./setNTP.sh");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
