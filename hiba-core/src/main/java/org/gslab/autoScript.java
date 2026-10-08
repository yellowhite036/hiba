package org.gslab;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gslab.Tool.JsonFileTool;
import org.gslab.Tool.modelTreeReader;

public class autoScript {

    private static int errorCount = 0;
    private static final Map<String, String> errorInfo = new HashMap<>();

    public static void main(String treePath, String roadType) {
        String state = "start";
        String requestName = ""; // 發出getModel的request檔案名稱 (會含時間戳)
        String requestResult = ""; // 結果檔案的名稱
        String trainRequestName = "";
        String trainRequestResult = "";
        String sampleDir = "";
        String currentTime = "";
        while (true) {
            try {
                switch (state) {
                    case "start":
                        System.out.println("---------------------");
                        System.out.println(systemCurrentTime() + " - 啟動自動佈署程序, getModel service");
                        requestName = getModelRequest(treePath, roadType);
                        if (!requestName.isEmpty()) {
                            System.out.println(" > 產生Request: " + requestName);
                            state = "waitingResponse";
                        } else {
                            currentTime = systemCurrentTime();
                            System.err.println(" > An error occurred. " + currentTime + "  生成getModel Request失敗, 重新程序");
                            errorCount += 1;
                            errorInfo.put(currentTime, "生成getModel Request失敗");
                            state = "start";
                            Thread.sleep(1000);
                            break;
                        }

                    case "waitingResponse":
                        System.out.println(systemCurrentTime() + " - 等待HiBA系統回覆處理結果.. ");
                        requestResult = responseChecker(requestName, 300);
                        if (requestResult != null) {
                            System.out.println(" > 已接收回覆: " + requestResult);
                            state = "sampleClassifier";
                        } else {
                            currentTime = systemCurrentTime();
                            System.err.println(
                                    " > An error occurred. " + currentTime + "  等待HiBA系統回覆getModel結果Timeout");
                            errorCount += 1;
                            errorInfo.put(currentTime, "等待HiBA系統回覆getModel結果Timeout");
                            state = "start";
                            Thread.sleep(1000);
                            break;
                        }

                    case "sampleClassifier":
                        System.out.println(systemCurrentTime() + " - 樣本整理中..");
                        sampleDir = sampleClassifier("./response/" + requestResult, treePath);

                        if (sampleDir.length() > 2) {
                            System.out.println(" > 完成, 樣本目錄: " + sampleDir);
                            Thread.sleep(3000);
                            state = "fileIntegrityCheck";
                        } else {
                            currentTime = systemCurrentTime();
                            System.err.println(" > An error occurred. " + currentTime + " Sample classifier error.");
                            errorCount += 1;
                            errorInfo.put(currentTime, "樣本整理發生錯誤");
                            state = "start";
                            Thread.sleep(1000);
                            break;
                        }

                    case "fileIntegrityCheck":
                        System.out.println(systemCurrentTime() + " - 檢測是否接收完整..");
                        System.out.println(" > 樣本路徑: " + sampleDir);
                        if (fileIntegrityCheck(sampleDir, treePath)) {
                            state = "trainDeepModel";
                        } else {
                            currentTime = systemCurrentTime();
                            System.err.println(" > An error occurred. " + currentTime + " 接收樣本檢測失敗, 接收不完整");
                            errorCount += 1;
                            errorInfo.put(currentTime, "樣本接收不完整");
                            state = "start";
                            Thread.sleep(1000);
                            break;
                        }
                    case "trainDeepModel":
                        System.out.println(systemCurrentTime() + " - 開始訓練程序, 生成訓練request");
                        trainRequestName = trainDeepModel(sampleDir, treePath);
                        if (!trainRequestName.isEmpty()) {
                            System.out.println(" > 產生Request: " + trainRequestName);
                            state = "waitingTraining";
                        } else {
                            currentTime = systemCurrentTime();
                            System.err.println(" > An error occurred. " + currentTime + "  生成Training Request失敗, 重新程序");
                            errorCount += 1;
                            errorInfo.put(currentTime, "生成Training Request失敗");
                            state = "start";
                            Thread.sleep(1000);
                            break;
                        }

                    case "waitingTraining":
                        System.out.println(systemCurrentTime() + " - 等待HiBA系統回覆處理結果.. ");
                        trainRequestResult = responseChecker(trainRequestName, 300);
                        if (trainRequestResult != null) {
                            System.out.println(" > 已接收回覆: " + trainRequestResult);
                            state = "end";
                        } else {
                            currentTime = systemCurrentTime();
                            System.err.println(" > An error occurred. " + currentTime + "  等待HiBA系統回覆訓練結果Timeout");
                            errorCount += 1;
                            errorInfo.put(currentTime, "等待HiBA系統回覆訓練結果Timeout");
                            state = "start";
                            Thread.sleep(1000);
                            break;
                        }

                    case "end":
                        System.exit(0);

                    default:
                        System.out.println("default!");
                        System.exit(0);
                        break;
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(0);
                break;
            }
            System.out.println("------------ Error Log ------------");
            for (String key : errorInfo.keySet()) {
                System.out.println(key + " : " + errorInfo.get(key));
            }
            System.out.println("Error count: " + errorCount);
            System.out.println("-----------------------------------");
        }
    }

    public static String systemCurrentTime() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        return dateFormat.format(System.currentTimeMillis());
    }

    /*
     * 自動化getModel service執行
     * input : tree.json (treePath), 路口類型 (roadType)
     * output: none
     *
     * 備註: 這部份要加入任務是否被接收的判斷
     */
    public static String getModelRequest(String treePath, String roadType) {
        // 讀取tree file 轉成 getModel request
        Map<String, Object> request = new HashMap<>();
        String requestName = "";
        String createTime = Long.toString(System.currentTimeMillis());
        try {
            File tree = new File(treePath);
            Map<String, Object> treeContent = modelTreeReader.ReadFile(tree).toMap();
            Map<String, Object> treeStructure = modelTreeReader.getModelCategory(treeContent);
            ArrayList<Object> recognitionCategoryList = new ArrayList<>(treeStructure.values());
            ArrayList<String> roadTypeList = new ArrayList<>();
            roadTypeList.add(roadType);
            requestName = "request_" + createTime;
            request.put("requestName", requestName);
            request.put("serviceName", "getModel");
            request.put("roadType", roadTypeList);
            request.put("recognitionCategory", recognitionCategoryList);
            if (JsonFileTool.createJson(request, requestName)) {
                Path source = Paths.get(requestName);
                Path target = Paths.get("./request/" + requestName + ".json");
                Files.move(source, target);
            }
            return requestName;
        } catch (IOException e) {
            e.printStackTrace();
            return requestName;
        }
    }

    /*
     * 等待系統執行完畢後的response.json
     * input : requestName
     * output: 當前request執行完畢回傳的response.json 檔案名稱
     *
     * 備註: 這部份要加入timeout機制
     */
    public static String responseChecker(String requestName, int timeout) {
        File responseDir = new File("./response/");
        String requestResult = null;
        int timeCount = 0;
        try {
            while (requestResult == null) {
                File[] dirAllFiles = responseDir.listFiles();
                for (File file : dirAllFiles) {
                    if (file.isFile()) {
                        String fileName = file.getName();
                        if (fileName.contains(requestName)) {
                            requestResult = fileName;
                            System.out.println("System execution result: " + requestResult);
                            break;
                        }
                    }
                }
                Thread.sleep(1000);
                timeCount += 1;
                if (timeCount >= timeout) {
                    break;
                }
            }
            return requestResult;
        } catch (Exception e) {
            e.printStackTrace();
            return requestResult;
        }
    }

    // 讀取response.json 來get receiveTargetPath, 並呼叫callSampleClassifier 來整理資料夾目錄
    /*
     * 整理getModel接收到的檔案
     * input : 任務執行結果json檔 (sysResult), getModel所使用的tree.json檔(treePath)
     * output: result檔 檔案名稱
     *
     * 備註: 這部份要加入timeout機制
     */
    public static String sampleClassifier(String requestResult, String treePath) {
        File resultFile = new File(requestResult);
        String sampleDir = "";
        Map<String, Object> resultContent = modelTreeReader.ReadFile(resultFile).toMap();
        String receiveTargetPath = (String) resultContent.get("receiveTargetPath");
        System.out.println(" > " + "接收檔案之路徑為: " + receiveTargetPath);
        if (callSampleClassifier(receiveTargetPath, treePath)) {
            sampleDir = receiveTargetPath;
        }
        return sampleDir;
    }

    /*
     * 系統呼叫sampleClassifier.py 整理接收到的npy檔
     * input : 收到npy檔的目錄(receiveTargetPath), getModel所使用的tree.json檔(treePath)
     * output: 直接將收到npy檔的目錄(receiveTargetPath) 依照tree.json 以資料夾來分類樹狀類別
     */
    public static boolean callSampleClassifier(String receiveTargetPath, String treePath) {
        Process child;
        String command = "python3 ./service/sampleClassifier.py " + receiveTargetPath + " " + treePath;
        boolean state = false;
        try {
            child = Runtime.getRuntime().exec(command);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                if (content.contains("True")) {
                    state = true;
                }
            }
            return state;
        } catch (IOException e) {
            e.printStackTrace();
            return state;
        }
    }

    /*
     * 檢查getModel收到的檔案是否與tree.json一致
     * input : 收到npy檔的目錄(receiveTargetPath), getModel所使用的tree.json檔(treePath)
     * output: 一致 (true) / 不一致 (false)
     */
    @SuppressWarnings("unchecked")
    public static Boolean fileIntegrityCheck(String sampleDir, String treePath) {
        boolean isSame = true;
        try {
            // request的tree結構
            File tree = new File(treePath);
            Map<String, Object> treeContent = modelTreeReader.ReadFile(tree).toMap();
            Map<String, Object> treeStructure = modelTreeReader.getModelCategory(treeContent);
            System.out.println("treeStructure: " + treeStructure);
            // 接收到的檔案目錄整理
            File receivePath = new File(sampleDir);
            File[] dirAllFiles = receivePath.listFiles();
            Map<String, Object> receivedTree = new HashMap<>();
            for (File file : dirAllFiles) {
                if (file.isFile()) {
                    String fileName = file.getName();
                    System.out.println(fileName);
                } else if (file.isDirectory()) {
                    ArrayList<String> subTree = new ArrayList<>();
                    String subDir = file.toString();
                    String subDirName = subDir.substring(subDir.lastIndexOf(File.separatorChar) + 1);
                    for (File subFile : file.listFiles()) {
                        String fileName = subFile.getName().replace(".npz", "");
                        subTree.add(fileName);
                    }
                    subDirName = "root" + subDirName;
                    receivedTree.put(subDirName, subTree);
                }
            }
            System.out.println("receivedTree: " + receivedTree);

            // 比較兩map
            for (String key : treeStructure.keySet()) {
                if (!receivedTree.containsKey(key)) {
                    System.out.println("Received tree is missing category: " + key);
                    System.out.println(treeStructure.get(key));
                    isSame = false;
                } else {
                    List<String> treeStructureValues = (List<String>) treeStructure.get(key);
                    List<String> receivedTreeValues = (List<String>) receivedTree.get(key);
                    if (!treeStructureValues.containsAll(receivedTreeValues)
                            || !receivedTreeValues.containsAll(treeStructureValues)) {
                        System.out.println("Received tree has different category: " + key);
                        System.out.println(treeStructure.get(key));
                        isSame = false;
                    }
                }
            }
            System.out.println("檢測結果: " + isSame);
            return isSame;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /*
     * 自動化trainDeepModel service執行
     * input : 收到npy檔的目錄(receiveTargetPath), trainDeepModel所使用的tree.json檔(treePath)
     * output: none
     *
     * 備註: 這部份要加入任務是否被接收的判斷
     */
    public static String trainDeepModel(String transferDir, String treePath) {
        Map<String, Object> request = new HashMap<>();
        String createTime = Long.toString(System.currentTimeMillis());
        String requestName = "";
        try {
            File tree = new File(treePath);
            Map<String, Object> treeContent = modelTreeReader.ReadFile(tree).toMap();
            requestName = "request_" + createTime;
            request.put("requestName", requestName);
            request.put("serviceName", "trainDeepModel");
            request.put("tree", treeContent);
            request.put("sampleDirectory", transferDir);
            ArrayList<String> transferFile = new ArrayList<>();
            transferFile.add(transferDir);
            request.put("transferFile", transferFile);
            if (JsonFileTool.createJson(request, requestName)) {
                Path source = Paths.get(requestName);
                Path target = Paths.get("./request/" + requestName + ".json");
                Files.move(source, target);
            }
            // 將tree.json也打包到傳輸的檔案目錄中
            Path source = Paths.get(treePath);
            Path target = Paths.get(transferDir + "/" + treePath);
            Files.move(source, target);
            return requestName;
        } catch (IOException e) {
            e.printStackTrace();
            return requestName;
        }
    }

    public static void main(String[] args) {
        String treePath = args[0];
        String roadType = "roadSection";
        main(treePath, roadType);

        // 各項測試用:
        // String treePath = "./tesTreeFile.json";
        // String roadType = "roadSection";
        // getModelRequest(treePath, roadType);
        // System.out.println(responseChecker("request_1683793603724"));
        // sampleClassifier("./response/request_1683805443013_2023-05-11_19-44-03_121.json",
        // "./tesTreeFile.json");
        // fileIntegrityCheck("./receiveFile/getModel_2023-05-12_10-57-42_15/",
        // "./receiveFile/tesTreeFile.json");
        // trainDeepModel("./receiveFile/getModel_2023-05-11_19-44-03_121/",
        // "./tesTreeFile.json");
    }
}
