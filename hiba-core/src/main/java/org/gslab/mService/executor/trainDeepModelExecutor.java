package org.gslab.mService.executor;

import io.netty.channel.Channel;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.gslab.Executor;
import org.gslab.util.PathUtil;
import org.gslab.model.RequestId;
import org.gslab.sdns.FileTransferClient;
import org.gslab.sdns.HiBAInitial;

public class trainDeepModelExecutor extends Executor {

    @Override
    public Map<String, Object> execute(Map<String, Object> information, Channel brokerChannel) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> resultInformation = new HashMap<>();
        String modelName = "";
        RequestId requestId = (RequestId) information.get("requestId");

        System.out.println("--------------- Node Start execute:");
        Map<String, Object> trainingModel = trainingModel(information);
        if (!trainingModel.isEmpty()) {
            String modelPath = (String) trainingModel.get("outputModel");
            String outputPath = (String) trainingModel.get("outputPath");
            modelName = (String) trainingModel.get("modelName");
            FileTransferClient.doTransfer(modelPath, outputPath, null, brokerChannel, HiBAInitial.DATA_PORT, requestId);
            resultInformation.put("transferred", true);
        } else {
            resultInformation.put("transferred", false);
        }

        long endTime = System.currentTimeMillis();
        long timeConsuming = endTime - startTime;
        resultInformation.put("nodeProvides", modelName);
        resultInformation.put("startTime", startTime);
        resultInformation.put("endTime", endTime);
        resultInformation.put("exeTime", timeConsuming / 1000.00);
        return resultInformation;
    }

    @Override
    public Map<String, Object> brokerExecute(Map<String, Object> information) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> resultInformation = new HashMap<>();
        String modelName = "";

        System.out.println("------------- Broker Start execute:");
        Map<String, Object> trainingModel = trainingModel(information);
        if (!trainingModel.isEmpty()) {
            modelName = (String) trainingModel.get("modelName");
        }

        long endTime = System.currentTimeMillis();
        long timeConsuming = endTime - startTime;
        resultInformation.put("nodeProvides", modelName);
        resultInformation.put("startTime", startTime);
        resultInformation.put("endTime", endTime);
        resultInformation.put("exeTime", timeConsuming / 1000.00);

        return resultInformation;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> trainingModel(Map<String, Object> information) {
        Map<String, Object> response = new HashMap<>();
        // 任務資訊
        String receiveTargetPath = (String) information.get("receiveTargetPath"); // receiveFile path
        String sampleDirectory = ((String) information.get("sampleDirectory")).replaceAll("\\.", "");
        Path taskFilePath = PathUtil.buildPath(receiveTargetPath, sampleDirectory); // task資料的資料夾目錄
        Path finishedPath = PathUtil.buildPath(receiveTargetPath, "finished");
        File taskFileDir = taskFilePath.toFile();

        Map<String, Object> tree = (Map<String, Object>) information.get("tree");
        String name = (String) tree.get("name");

        // 獲取tree.json路徑
        // String treeFile = "";
        ArrayList<String> searchFileList = new ArrayList<>();
        traverseDirectory(taskFileDir, searchFileList);
        // for (String file : searchFileList) {
        // if (file.endsWith(".json")) {
        // treeFile = file;
        // break;
        // }
        // }
        // 執行運算
        try {
            ArrayList<String> terminalResponse = new ArrayList<>();
            Path samplePath = name.equals("root") ? taskFilePath : PathUtil.buildPath(taskFilePath, name);
            String tree_path = findJson(samplePath.toString());
            String out_model_name = !name.equals("root") ? "root-" + name : "root";

            System.out.println("----------------- execute -----------------");
            System.out.println("Start training program...");
            System.out.println("Sample directory : " + samplePath);
            System.out.println("Training details : " + tree_path);
            System.out.println("Output model     : " + out_model_name);
            System.out.println("Output path      : " + finishedPath);
            System.out.println("Call training program: " + "python3 ./service" + File.separator + "trainingSVM.py "
                    + samplePath + " " + tree_path + " " + finishedPath + " " + out_model_name);
            System.out.println("--------------------------------------------");

            // terminalResponse = SystemCall.terminal("python3 ./service" + File.separator +
            // "trainingSVM.py " + sample_path + " " + tree_path + " " + out_model_path + "
            // " + out_model_name, true);
            // System.out.println("Call training module finish, System log:");
            // for (String text : terminalResponse) {
            // System.out.println(text);
            // }

            /*
             * ================================
             * 創建空檔案與資料夾, 以方便測試回傳功能
             */
            File outputPath_test = finishedPath.toFile();
            Path modelPath = PathUtil.buildPath(finishedPath, out_model_name + ".xml");
            File svmModel_test = modelPath.toFile();
            try {
                Random rand = new Random();
                Thread.sleep(rand.nextInt((7000 - 3000) + 1) + 3000);
            } catch (Exception e) {
                e.printStackTrace();
            }
            outputPath_test.mkdir();
            svmModel_test.createNewFile();

            response.put("modelName", out_model_name);
            response.put("outputModel", modelPath.toString());
            response.put("outputPath", finishedPath.toString());
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void traverseDirectory(File directory, ArrayList<String> list) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            for (File file : files) {
                if (file.isFile()) {
                    list.add(file.getPath());
                } else {
                    traverseDirectory(file, list);
                    continue;
                }
            }
        }
    }

    public static String findJson(String directoryPath) { // 找目錄下.json檔用的 (找tree.json)
        String jsonFile = "";
        File taskDir = new File(directoryPath);
        File[] taskDirList = taskDir.listFiles();
        for (File file : taskDirList) {
            String fileName = file.getName();
            if (fileName.contains(".json")) {
                jsonFile = file.toString();
                break;
            }
        }
        return jsonFile;
    }
}
