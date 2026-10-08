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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class autoScriptThesisExpe {

    private static final Logger logger = LoggerFactory.getLogger(autoScriptThesisExpe.class);
    private static final Map<String, String> errorInfo = new HashMap<>();
    private static int errorCount = 0;

    // public static void main(String treePath, String roadType) {
    public static void startScript(Map<Integer, String> scriptList) throws InterruptedException {

        logger.atInfo().log("Start the automatic deployment script.");
        for (Integer roundNum = 1; roundNum <= scriptList.size(); roundNum++) {
            String treePath = scriptList.get(roundNum);
            String scriptType = treePath.substring(0, treePath.length() - 5).split("_")[1];
            logger.atInfo().log("-----------------------------------");
            logger.atInfo().log("Training round   : {}", roundNum);
            logger.atInfo().log("Tree Path        : {}", treePath);
            logger.atInfo().log("Intersection type: {}", scriptType);
            logger.atInfo().log("-----------------------------------");

            Boolean FSM_STATE = true;
            String SCRIPT_STATE = "start";
            String requestName = ""; // 發出getModel的request檔案名稱 (會含時間戳)
            String requestResult = ""; // 結果檔案的名稱
            String trainRequestName = "";
            String trainRequestResult = "";
            String sampleDir = "";
            String currentTime = "";
            int getModelRequestErrorCount = 0;
            int trainingRequestErrorCount = 0;
            while (FSM_STATE) {
                try {
                    switch (SCRIPT_STATE) {
                            // 生成自動蒐集樣本與模型request並發出
                        case "start":
                            logger.atInfo().log("Start the auto-deployment program.");
                            requestName = getModelRequest(treePath, scriptType);
                            if (!requestName.isEmpty()) {
                                logger.atInfo().log("Generate request: {}", requestName);
                                Thread.sleep(1000);
                                SCRIPT_STATE = "waitingResponse";
                            } else {
                                logger.atInfo().log("Failed to generate Request, try to restart.");
                                SCRIPT_STATE = "start";
                                errorCount += 1;
                                errorInfo.put(
                                        getSystemCurrentTime(),
                                        "Failed to generate Automatic sample collection request.");
                                break;
                            }
                            break;

                            // 等待自動蒐集服務回覆
                        case "waitingResponse":
                            logger.atInfo().log(
                                    "Send request '{}' successfully, waiting for HiBA system response.", requestName);
                            requestResult = responseChecker(requestName, 2400);

                            if (requestResult.contains(requestName)) {
                                logger.atInfo().log("HiBA system reply detected, Result file: {}", requestResult);
                                Thread.sleep(1000);
                                SCRIPT_STATE = "sampleClassifier";
                            } else if ("false".equals(requestResult)) {
                                logger.atInfo().log(
                                        "The resources in the HiBA system cannot handle the request, try to regenerate.");
                                errorInfo.put(
                                        getSystemCurrentTime(),
                                        "A resource in the HiBA system cannot handle the request: " + requestName);
                                FSM_STATE = false;
                                Thread.sleep(1000);
                                break;
                            } else {
                                logger.atInfo().log("Handle timeout, try to restart the automatic script program.");
                                SCRIPT_STATE = "start";
                                errorCount += 1;
                                errorInfo.put(getSystemCurrentTime(), "Automatic sample collection occurs timeout.");
                                break;
                            }
                            break;

                            // 整理接收到的樣本與模型
                        case "sampleClassifier":
                            logger.atInfo().log("The sample path is: {}, Start sample classification.", requestResult);
                            sampleDir = sampleClassifier("./response" + File.separator + requestResult, treePath);

                            if (sampleDir.length() > 2) {
                                logger.atInfo().log("Done, sample directory path:{}", sampleDir);
                                Thread.sleep(3000);
                                SCRIPT_STATE = "fileIntegrityCheck";
                            } else {
                                currentTime = getSystemCurrentTime();
                                logger.atInfo().log(
                                        "Sample classifier interrupted with exception, restarted automated script.");
                                SCRIPT_STATE = "start";
                                errorCount += 1;
                                errorInfo.put(currentTime, "Sample classifier interrupted with exception.");
                                break;
                            }
                            break;

                        case "fileIntegrityCheck":
                            logger.atInfo().log(
                                    "Check that the sample is complete, sample directory path: {}", sampleDir);
                            if (fileIntegrityCheck(sampleDir, treePath)) {
                                logger.atInfo().log("Done.");
                                Thread.sleep(1000);
                                SCRIPT_STATE = "trainDeepModel";
                            } else {
                                if (getModelRequestErrorCount >= 3) {
                                    logger.atInfo().log(
                                            "The resources in the HiBA system cannot handle the request, try to regenerate.");
                                    errorInfo.put(
                                            getSystemCurrentTime(),
                                            "A resource in the HiBA system cannot handle the request.");
                                    Thread.sleep(1000);
                                    FSM_STATE = false;
                                    errorCount += 1;
                                    errorInfo.put(
                                            getSystemCurrentTime(),
                                            "The generated training request is invalid, restart the script.");
                                } else {
                                    logger.atInfo().log("Incomplete sample received.");
                                    Thread.sleep(1000);
                                    SCRIPT_STATE = "start";
                                    getModelRequestErrorCount += 1;
                                    errorCount += 1;
                                    errorInfo.put(getSystemCurrentTime(), "Incomplete sample received.");
                                }
                                break;
                            }
                            break;

                        case "trainDeepModel":
                            logger.atInfo().log("Start the model training program and generate a training request.");
                            trainRequestName = trainDeepModel(sampleDir, treePath);
                            if (!trainRequestName.isEmpty()) {
                                logger.atInfo().log("Generate request: {}", trainRequestName);
                                Thread.sleep(1000);
                                SCRIPT_STATE = "waitingTraining";
                            } else {
                                if (trainingRequestErrorCount >= 3) {
                                    logger.atInfo().log("Tried to no avail, restart the automatic script program.");
                                    Thread.sleep(1000);
                                    SCRIPT_STATE = "start";
                                    errorCount += 1;
                                    errorInfo.put(
                                            getSystemCurrentTime(),
                                            "The generated training request is invalid, restart the script.");
                                } else {
                                    logger.atInfo().log("Failed to generate Request, recheck the samples directory.");
                                    Thread.sleep(1000);
                                    SCRIPT_STATE = "fileIntegrityCheck";
                                    trainingRequestErrorCount += 1;
                                    errorCount += 1;
                                    errorInfo.put(
                                            getSystemCurrentTime(), "Failed to generate Automatic training request.");
                                }
                                break;
                            }
                            break;

                        case "waitingTraining":
                            logger.atInfo().log(
                                    "Send request '{}' successfully, waiting for HiBA system response.",
                                    trainRequestName);
                            trainRequestResult = responseChecker(trainRequestName, 2400);
                            if (trainRequestResult != null) {
                                logger.atInfo().log("HiBA system reply detected, Result file: {}", trainRequestResult);
                                Thread.sleep(1000);
                                SCRIPT_STATE = "end";
                            } else {
                                logger.atInfo().log("Handle timeout, try to restart the automatic script program.");
                                Thread.sleep(1000);
                                SCRIPT_STATE = "start";
                                errorCount += 1;
                                errorInfo.put(getSystemCurrentTime(), "Automatic training model occurs timeout.");
                                break;
                            }
                            break;

                        case "end":
                            FSM_STATE = false;
                            break;
                            // System.exit(0);

                        default:
                            logger.atInfo().log("default!");
                            System.exit(0);
                            break;
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(0);
                    break;
                }

                logger.atInfo().log("************ Error Log ************");
                if (errorInfo.isEmpty()) {
                    logger.atInfo().log("None.");
                } else {
                    for (String key : errorInfo.keySet()) {
                        logger.atInfo().log("{} : {}", key, errorInfo.get(key));
                    }
                    logger.atInfo().log("Error count: {}", errorCount);
                }
                logger.atInfo().log("***********************************");
                Thread.sleep(5000);
            }
        }
    }

    public static String getSystemCurrentTime() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
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
                Path target = Paths.get("./request" + File.separator + requestName + ".json");
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
                            Thread.sleep(1000);
                            File response = new File("./response" + File.separator + fileName);
                            Map<String, Object> responseInfo =
                                    modelTreeReader.ReadFile(response).toMap();
                            try {
                                Boolean state = (Boolean) responseInfo.get("state");
                                if (state) {
                                    requestResult = fileName;
                                } else if (!state) {
                                    requestResult = "false";
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            // System.out.println("System execution result: " + requestResult);
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
            logger.atInfo().log("--------------------");
            logger.atInfo().log("Tree structure : {}", treeStructure);
            // 接收到的檔案目錄整理
            File receivePath = new File(sampleDir);
            File[] dirAllFiles = receivePath.listFiles();
            Map<String, Object> receivedTree = new HashMap<>();
            for (File file : dirAllFiles) {
                if (file.isFile()) {
                    String fileName = file.getName();
                    logger.atInfo().log(" - {}", fileName);
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
            logger.atInfo().log("Received tree  : {}", receivedTree);

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
            logger.atInfo().log("--------------------");
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
            Path target = Paths.get(transferDir + "/" + tree.getName());
            Files.copy(source, target);
            return requestName;
        } catch (IOException e) {
            e.printStackTrace();
            return requestName;
        }
    }

    public static void main(String[] args) throws Exception {
        File treeDirectory = new File("./autoScript");
        Map<Integer, String> scriptList = new HashMap<>();
        // int scriptNumber = 0;
        if (treeDirectory.isDirectory()) {
            File[] treeList = treeDirectory.listFiles();
            for (File file : treeList) {
                String filePath = file.toString();
                String fileName = file.getName();
                if (fileName.contains(".json")) {
                    String treeName = fileName.substring(0, fileName.length() - 5);
                    Integer scriptNumber =
                            Integer.valueOf(treeName.split("_")[0].split("-")[1]);
                    scriptList.put(scriptNumber, filePath);
                }
            }
        }
        System.out.println(scriptList);
        startScript(scriptList);

        /*
        String treePath = args[0];
        String roadType = "roadSection";
        startScript(treePath, roadType);
        */

        // 各項測試用:
        // String treePath = "./tesTreeFile.json";
        // String roadType = "roadSection";
        // getModelRequest(treePath, roadType);
        // System.out.println(responseChecker("request_1683793603724"));
        // sampleClassifier("./response/request_1683805443013_2023-05-11_19-44-03_121.json", "./tesTreeFile.json");
        // fileIntegrityCheck("./receiveFile/getModel_2023-05-12_10-57-42_15/", "./receiveFile/tesTreeFile.json");
        // trainDeepModel("./receiveFile/getModel_2023-05-11_19-44-03_121/", "./tesTreeFile.json");
    }
}
