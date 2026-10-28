package org.gslab.mService.executor;

import io.netty.channel.Channel;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.gslab.Executor;

public class InitializeDeviceExecutor extends Executor {

    @Override
    public Executor getNewInstance() {
        return this;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> information, Channel brokerChannel) {
        Map<String, Object> resultInformation = new HashMap<>();
        long startTime = System.currentTimeMillis();
        resultInformation.put("startTime", startTime);

        deleteAllFile("./logs", ".log");
        deleteAllFile("./receiveFile", "all/Files");

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

        deleteAllFile("./logs", ".log");
        deleteAllFile("./receiveFile", "all/Files");

        long endTime = System.currentTimeMillis();
        long exeTime = endTime - startTime;
        resultInformation.put("endTime", endTime);
        resultInformation.put("exeTime", exeTime);
        return resultInformation;
    }

    public static void deleteAllFile(String directoryPath, String containsString) {
        File directory = new File(directoryPath);
        File[] fileList = directory.listFiles();
        for (File file : fileList) {
            try {
                File filePath = file;
                if (filePath.isFile() && filePath.toString().contains(containsString)) {
                    System.out.println(filePath);
                    filePath.delete();
                } else if ("all/Files".equals(containsString)) {
                    deleteDirectory(directory);
                    directory.mkdirs();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void deleteDirectory(File directory) {
        File[] contents = directory.listFiles();
        if (contents != null) {
            for (File subFile : contents) {
                System.out.println(subFile);
                deleteDirectory(subFile);
            }
        }
        directory.delete();
    }
}
