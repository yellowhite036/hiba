package org.gslab.mService.executor;

import io.netty.channel.Channel;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.gslab.Executor;
import org.gslab.util.JsonFileUtil;
import org.gslab.model.RequestId;
import org.gslab.sdns.FileTransferClient;
import org.gslab.sdns.HiBAInitial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class getModelExecutor extends Executor {

    private static final Logger logger = LoggerFactory.getLogger(getModelExecutor.class);

    private static final String textFilter = "[^a-zA-Z0-9]";
    private static final ArrayList<String> providedFile = new ArrayList<>(); // 節點提供出去的檔案清單, 回傳結果條列用

    @Override
    public Executor getNewInstance() {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> information, Channel brokerChannel) {

        Map<String, Object> resultInformation = new HashMap<>();
        long startTime = System.currentTimeMillis();
        resultInformation.put("startTime", startTime);
        String requestIdStr = (String) information.get("requestId");
        RequestId requestId = requestIdStr != null ? RequestId.of(requestIdStr) : RequestId.UNKNOWN;

        String receiveTargetPath = (String) information.get("receiveTargetPath");
        ArrayList<ArrayList<String>> requestCategory =
                (ArrayList<ArrayList<String>>) information.get("recognitionCategory");
        ArrayList<String> canHandelModel = new ArrayList<>(); // 節點自己可回傳的model list

        logger.atInfo().log("Node received the task: {}", information);

        try {
            // 讀取自己的model tree, 確認可回傳的model
            File hasTreeStructure = new File((String) HiBAInitial.getMethod().get("modelTree"));
            Map<String, Object> trackTree =
                    JsonFileUtil.ReadFile(hasTreeStructure).toMap();
            Map<String, Object> treeStructure = getModelCategory(trackTree);
            for (ArrayList<String> strings : requestCategory) {
                for (String modelName : treeStructure.keySet()) {
                    if (strings.equals(treeStructure.get(modelName))) {
                        modelName = modelName + ".xml";
                        canHandelModel.add(modelName.replaceAll(textFilter, "")); // 檔名的文字過濾, 將'-' '.' 等符號去除
                    }
                }
            }
            resultInformation.put("taskRequirements", requestCategory);

            // System.out.println("treeStructure: " + treeStructure);

            Map<String, Object> method = HiBAInitial.getMethod(); // 讀取原先service.json載入時, method欄位的資訊
            String directory = (String) method.get("getModel");
            String sampleDir = (String) method.get("nodeSample");
            File getModel = new File(directory); // model存放路徑
            File[] modelDir = getModel.listFiles();
            int modelDirLength = modelDir.length;
            if (getModel.isDirectory() && modelDirLength > 0) {
                for (File value : modelDir) {
                    String modelName = value.getName().replaceAll(textFilter, "");
                    if (canHandelModel.contains(modelName)) {
                        String modelPath = value.toString();
                        System.out.println("Find model: " + modelPath);

                        // 修改欲傳輸的模型的檔案名稱, 修改為root-1_模型組織結構.xml  -> ex: root-1-980_990_312_322.xml
                        String childrenName = modelName.replace("xml", "");
                        ArrayList<String> childrenTree = (ArrayList<String>) treeStructure.get(childrenName);
                        String structure = String.join("_", childrenTree);
                        String newFileName = "root-" + structure + ".xml";
                        File oldFile = new File(modelPath);
                        File newFile = new File(directory + newFileName);
                        // 傳輸模型
                        if (oldFile.renameTo(newFile)) {
                            FileTransferClient.doTransfer(
                                    directory + newFileName,
                                    receiveTargetPath,
                                    null,
                                    brokerChannel,
                                    HiBAInitial.DATA_PORT,
                                    requestId);
                            providedFile.add(newFileName);
                            newFile.renameTo(value);
                        }
                        // 產HoG特徵與傳輸特徵.npz檔
                        for (String name : childrenTree) {
                            String outputPath = sampleDir + File.separator + name + "-" + structure + ".npz";
                            if (getSampleHOGFile(sampleDir + name + "/", outputPath)) {
                                File file = new File(outputPath);
                                if (file.exists()) {
                                    FileTransferClient.doTransfer(
                                            outputPath,
                                            receiveTargetPath,
                                            null,
                                            brokerChannel,
                                            HiBAInitial.DATA_PORT,
                                            requestId);
                                    providedFile.add(name + "-" + structure + ".npz");
                                } else {
                                    System.out.println("No such file: " + outputPath);
                                }
                            }
                        }
                        canHandelModel.remove(modelName);
                    }
                }
                if (!canHandelModel.isEmpty()) {
                    for (String modelName : canHandelModel) {
                        String childrenName = modelName.replace("xml", "");
                        ArrayList<String> childrenTree = (ArrayList<String>) treeStructure.get(childrenName);
                        for (String name : childrenTree) {
                            String outputPath = sampleDir + File.separator + name + ".npz";
                            if (getSampleHOGFile(sampleDir + name + File.separator, outputPath)) {
                                File file = new File(outputPath);
                                if (file.exists()) {
                                    FileTransferClient.doTransfer(
                                            outputPath,
                                            receiveTargetPath,
                                            null,
                                            brokerChannel,
                                            HiBAInitial.DATA_PORT,
                                            requestId);
                                    providedFile.add(name + ".npz");
                                } else {
                                    System.out.println("No such file: " + outputPath);
                                }
                            }
                        }
                    }
                }
                resultInformation.put("transferred", true);
            }
        } catch (Exception e) {
            System.out.println("Exception: " + e);
            resultInformation.put("transferred", false);
        }

        long endTime = System.currentTimeMillis();
        long timeConsuming = endTime - startTime;
        resultInformation.put("nodeProvides", providedFile);
        resultInformation.put("endTime", endTime);
        resultInformation.put("exeTime", timeConsuming / 1000.00);
        System.out.println("執行任務結果: " + resultInformation);

        return resultInformation;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> brokerExecute(Map<String, Object> information) {
        Map<String, Object> resultInformation = new HashMap<>();
        long startTime = System.currentTimeMillis();
        resultInformation.put("startTime", startTime);
        String receiveTargetPath = (String) information.get("receiveTargetPath");
        ArrayList<ArrayList<String>> requestCategory =
                (ArrayList<ArrayList<String>>) information.get("recognitionCategory");
        ArrayList<String> canHandelModel = new ArrayList<>(); // 節點自己可回傳的model list

        logger.atInfo().log("Node received the task: {}", information);

        try {
            // 讀取自己的model tree, 確認可回傳的model
            File hasTreeStructure = new File((String) HiBAInitial.getMethod().get("modelTree"));
            Map<String, Object> trackTree =
                    JsonFileUtil.ReadFile(hasTreeStructure).toMap();
            Map<String, Object> treeStructure = getModelCategory(trackTree);
            for (ArrayList<String> strings : requestCategory) {
                for (String modelName : treeStructure.keySet()) {
                    if (strings.equals(treeStructure.get(modelName))) {
                        modelName = modelName + ".xml";
                        canHandelModel.add(modelName.replaceAll(textFilter, "")); // 檔名的文字過濾, 將'-' '.' 等符號去除
                    }
                }
            }
            resultInformation.put("taskRequirements", requestCategory);
            resultInformation.put("nodeProvides", canHandelModel);

            // System.out.println("treeStructure: " + treeStructure);

            Map<String, Object> method = HiBAInitial.getMethod(); // 讀取原先service.json載入時, method欄位的資訊
            String directory = (String) method.get("getModel");
            String sampleDir = (String) method.get("nodeSample");
            File getModel = new File(directory); // model存放路徑
            File[] modelDir = getModel.listFiles();
            int modelDirLength = modelDir.length;
            if (getModel.isDirectory() && modelDirLength > 0) {
                for (File file : modelDir) {
                    String modelName = file.getName().replaceAll(textFilter, "");
                    if (canHandelModel.contains(modelName)) {
                        String modelPath = file.toString();
                        System.out.println("Find model: " + modelPath);

                        // 修改欲傳輸的模型的檔案名稱, 修改為root-1_模型組織結構.xml  -> ex: root-1-980_990_312_322.xml
                        String childrenName = modelName.replace("xml", "");
                        ArrayList<String> childrenTree = (ArrayList<String>) treeStructure.get(childrenName);
                        String structure = String.join("_", childrenTree);
                        String newFileName = "root-" + structure + ".xml";
                        File oldFile = new File(modelPath);
                        File newFile = new File(directory + newFileName);
                        if (oldFile.renameTo(newFile)) {
                            File targetPath = new File(receiveTargetPath + "/" + newFileName);
                            File fileFormat = new File(directory + newFileName);
                            Files.copy(fileFormat.toPath(), targetPath.toPath());
                            providedFile.add(newFileName);
                            newFile.renameTo(file);
                        }
                        // 產HoG特徵與傳輸特徵.npz檔
                        for (String name : childrenTree) {
                            String outputPath = sampleDir + File.separator + name + "-" + structure + ".npz";
                            if (getSampleHOGFile(sampleDir + name + "/", outputPath)) {
                                File source = new File(outputPath);
                                File target =
                                        new File(receiveTargetPath + File.separator + name + "-" + structure + ".npz");
                                Files.copy(source.toPath(), target.toPath());
                                providedFile.add(name + "-" + structure + ".npz");
                            }
                        }
                        canHandelModel.remove(modelName);
                    }
                }
                if (!canHandelModel.isEmpty()) {
                    for (String modelName : canHandelModel) {
                        String childrenName = modelName.replace("xml", "");
                        ArrayList<String> childrenTree = (ArrayList<String>) treeStructure.get(childrenName);
                        for (String name : childrenTree) {
                            String outputPath = sampleDir + File.separator + name + ".npz";
                            String targetPath = receiveTargetPath + File.separator + name + ".npz";
                            if (new File(targetPath).exists()) {
                                continue;
                            }
                            if (getSampleHOGFile(sampleDir + name + "/", outputPath)) {
                                File source = new File(outputPath);
                                File target = new File(targetPath);
                                Files.copy(source.toPath(), target.toPath());
                                providedFile.add(name + "-" + name + ".npz");
                            }
                        }
                    }
                }
                resultInformation.put("transferred", true);
            }
        } catch (Exception e) {
            System.out.println("Exception: " + e);
            resultInformation.put("transferred", false);
        }

        long endTime = System.currentTimeMillis();
        long timeConsuming = endTime - startTime;
        resultInformation.put("nodeProvides", providedFile);
        resultInformation.put("endTime", endTime);
        resultInformation.put("exeTime", timeConsuming / 1000.00);
        return resultInformation;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getConfigurationPath() {
        File serviceFile = new File("./service.json");
        Map<String, Object> serviceTree = JsonFileUtil.ReadFile(serviceFile).toMap();
        Map<String, Object> mService = (Map<String, Object>) serviceTree.get("mService");
        return (Map<String, Object>) mService.get("track");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getModelCategory(Map<String, Object> branchNode) { // 樹狀分類器
        Map<String, Object> childCategory = new HashMap<>();
        ArrayList<Map<String, Object>> allTreeCategory = (ArrayList<Map<String, Object>>) branchNode.get("children");
        String modelName = (String) branchNode.get("name");

        allTreeCategory.forEach(childTree -> {
            String name = modelName + childTree.get("name");
            childCategory.put(name, childTree.get("value"));
        });

        return childCategory;
    }

    public static boolean getSampleHOGFile(String samplePath, String outputPath) {
        Process child;
        String command = "python3 ./service" + File.separator + "getDescriptors.py " + samplePath + " " + outputPath;
        System.out.println("--------------------");
        System.out.println("Sample path: " + samplePath);
        System.out.println("HoG path   : " + outputPath);
        int timeout = 0;
        boolean startCommand = false;
        try {
            while (true) {
                if (Files.exists(Paths.get(outputPath)) || timeout >= 120) {
                    break;
                } else {
                    if (!startCommand) {
                        child = Runtime.getRuntime().exec(command);
                        new BufferedReader(new InputStreamReader(child.getInputStream()));
                        startCommand = true;
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                }
                timeout += 1;
            }
            System.out.println("Get HoG file success.");
            return true;
        } catch (IOException e) {
            System.out.println("Get HoG file error.");
            return false;
        }
    }
}
