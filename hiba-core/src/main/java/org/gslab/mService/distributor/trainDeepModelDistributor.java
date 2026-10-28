package org.gslab.mService.distributor;

import io.netty.channel.Channel;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gslab.Distributor;
import org.gslab.util.JsonFileUtil;
import org.gslab.model.RequestId;
import org.gslab.model.TaskAssignment;
import org.gslab.model.TaskRequest;
import org.gslab.model.TaskResult;
import org.gslab.sdns.FileTransferClient;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;
import org.json.JSONArray;
import org.json.JSONObject;

public class trainDeepModelDistributor extends Distributor { // private static final Logger LOGGER =

    // LoggerFactory.getLogger(trainDeepModelDistributor.class);
    private final Map<String, Object> assignedTaskResults = new HashMap<>();
    private final Map<String, TaskResult> assignedTaskTaskResults = new HashMap<>(); // 類型安全的結果儲存

    @Override
    public Distributor getNewInstance() {
        return new trainDeepModelDistributor();
    }

    // === 新的類型安全方法實作 ===

    @Override
    @SuppressWarnings("unchecked")
    public TaskAssignment mappingTask(
            String receiveTaskNode,
            Map<String, Object> resource,
            Channel networkChannel,
            Map<String, Object> resourceInThisTier) {
        try {
            TaskRequest taskRequest = getTaskRequest();
            if (taskRequest == null) {
                return null;
            }

            // 調用原有的 mapping 方法進行複雜的任務分配邏輯
            Map<String, Object> assignTask = mapping(receiveTaskNode, resource, networkChannel, resourceInThisTier);

            if (assignTask != null) {
                // 轉換 Map 結果為 TaskAssignment
                TaskAssignment assignment = new TaskAssignment();
                assignment.setRequestName(taskRequest.getRequestName());
                assignment.setServiceName(taskRequest.getServiceName());
                assignment.setNodeName(receiveTaskNode);

                // 複製所有必要的任務資料
                assignment.setTree((Map<String, Object>) assignTask.get("tree"));
                assignment.setReceiveTargetPath((String) assignTask.get("receiveTargetPath"));
                assignment.setSampleDirectory((String) assignTask.get("sampleDirectory"));
                assignment.setDistributeFinish((Boolean) assignTask.getOrDefault("distributeFinish", false));

                // 複製其他相關資料
                if (assignTask.containsKey("tier")) {
                    assignment.addTaskData("tier", assignTask.get("tier"));
                }

                return assignment;
            }

            return null;
        } catch (Exception e) {
            System.out.println("Error in mappingTask for " + receiveTaskNode + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public TaskAssignment mappingLocalTask(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        return mappingTask(HiBAInitial.nodeName, localResource, null, resourceInThisTier);
    }

    @Override
    public void mergeResults(Map<String, TaskResult> allResults) {
        assignedTaskTaskResults.putAll(allResults);
    }

    @Override
    public TaskResult getTaskResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort) {
        TaskResult result = new TaskResult();
        TaskRequest taskRequest = getTaskRequest();
        RequestId requestId = taskRequest != null ? taskRequest.getRequestId() : RequestId.UNKNOWN;

        if (taskRequest != null) {
            result.setRequestName(taskRequest.getRequestName());
            result.setServiceName(taskRequest.getServiceName());
        }

        result.setEndTime(System.currentTimeMillis());

        // 執行原有的複雜結果處理邏輯
        Map<String, Object> workInformation = getWorkInformation();

        System.out.println("-------------------- Aggregate results:");

        if (requestNodeChannel != null) {
            try {
                String receiveTargetPath = workInformation.get("receiveTargetPath") + "/finished/";

                File getModel = new File(receiveTargetPath);
                File[] modelDir = getModel.listFiles();

                if (getModel.isDirectory() && modelDir != null) {
                    int modelDirLength = modelDir.length;
                    for (File file : modelDir) {
                        String modelPath = file.toString();
                        System.out.println("Find model: " + modelPath);
                        if (lowerBrokerPort != null) {
                            FileTransferClient.doTransfer(
                                    modelPath, receiveTargetPath, "localhost", null, lowerBrokerPort, requestId);
                        } else {
                            FileTransferClient.doTransfer(
                                    modelPath,
                                    receiveTargetPath,
                                    null,
                                    requestNodeChannel,
                                    HiBAInitial.DATA_PORT,
                                    requestId);
                        }
                    }
                    result.setResult(workInformation);
                    result.setState(true);
                    result.setSuccess(true);
                } else {
                    result.setSuccess(false);
                    result.setErrorMessage("Model directory not found or empty");
                }
            } catch (Exception e) {
                result.setSuccess(false);
                result.setErrorMessage("Error processing training results: " + e.getMessage());
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", false);
                result.setResult(errorResult);
            }
        } else { // requester is broker himself
            result.setResult(workInformation);
            result.setState(true);
            result.setSuccess(true);
        }

        // 清理分發列表文件
        File file = new File("./deepModelDistributorList");
        if (file.exists()) {
            file.delete();
        } // 添加訓練模型相關的結果資料
        result.setAllResult(this.assignedTaskResults);

        // 將 TaskResult 物件轉換為可序列化的 Map
        Map<String, Object> serializableTaskResults = new HashMap<>();
        assignedTaskTaskResults.forEach((taskName, taskResult) -> {
            serializableTaskResults.put(taskName, taskResult.toMap());
        });
        result.addResultData("assignedTaskResults", serializableTaskResults);
        result.addResultData("modelTrainingCompleted", true);

        return result;
    }

    // === 輔助方法 ===

    private Map<String, Object> convertTaskAssignmentToMap(TaskAssignment assignment) {
        Map<String, Object> map = new HashMap<>();
        map.put("requestName", assignment.getRequestName());
        map.put("serviceName", assignment.getServiceName());
        map.put("nodeName", assignment.getNodeName());
        map.put("tree", assignment.getTree());
        map.put("receiveTargetPath", assignment.getReceiveTargetPath()); // 保持原有拼字以兼容舊系統
        map.put("sampleDirectory", assignment.getSampleDirectory());
        map.put("distributeFinish", assignment.isDistributeFinish());

        // 添加任務特定資料
        if (assignment.getTaskSpecificData() != null) {
            map.putAll(assignment.getTaskSpecificData());
        }

        return map;
    }

    private Map<String, Object> convertTaskResultToMap(TaskResult result) {
        Map<String, Object> map = new HashMap<>();
        map.put("result", result.getResult());
        map.put("allResult", assignedTaskResults);
        return map;
    }

    // === 原有的 Map 基礎方法（保持向後兼容） ===
    @SuppressWarnings("unchecked")
    public Map<String, Object> mapping(
            String name, Map<String, Object> resource, Channel networkChannel, Map<String, Object> resourceInThisTier) {
        Map<String, Object> requestInfo = getWorkInformation(); // 待指派的request
        Map<String, Object> assignTask = new HashMap<>(); // 向下轉發的task
        Map<String, Long> trainingTaskList = new HashMap<>(); // 訓練任務
        RequestId requestId = (RequestId) requestInfo.get("requestId");

        assignTask.putAll(requestInfo);
        // 當前分派節點資訊
        ArrayList<ArrayList<Float>> localScoreList = (ArrayList<ArrayList<Float>>) resource.get("computility");
        /*
         * 節點分數部分，未來可能需要重寫，
         * 因為是ArrayList<ArrayList<Float>>，這樣在操作上不直覺
         */
        ArrayList<Float> localScore = new ArrayList<>();
        for (ArrayList<Float> list : localScoreList) {
            localScore.add(list.getFirst());
        }
        ArrayList<ArrayList<Float>> groupScoreList =
                (ArrayList<ArrayList<Float>>) resourceInThisTier.get("computility");
        ArrayList<Float> groupScore = new ArrayList<>();
        for (ArrayList<Float> list : groupScoreList) {
            groupScore.add(list.getFirst());
        }
        // 判斷分派中節點是否為broker
        boolean isBroker = localScore.size() > 1;
        String lowerBrokerPort = "";
        if (isBroker) {
            Map<String, Object> lowerBrokerInfo = (Map<String, Object>) HiBANetwork.reverseChannelList.get(name);
            lowerBrokerPort = (String) lowerBrokerInfo.get("port");
        }

        // 任務資訊, 也就是分支樹的tree架構
        Map<String, Object> tree = (Map<String, Object>) requestInfo.get("tree");
        /*
         * 發Request與轉發Task時，傳輸檔案的部分，未來需修改
         * 因為目前都是包在Request一起發，應該另外寫，並附上檔案路徑、路游路徑之類的資訊。
         */
        String receiveTargetPath = (String) requestInfo.get("receiveTargetPath"); // 轉發時候的target路徑, ex:
        // receiveTargetPath=./receiveFile/trainDeepModel_2023-06-13_22-26-22_608/
        String sampleDirectory =
                ((String) requestInfo.get("sampleDirectory")).replaceAll("\\.", ""); // Requester發出訓練任務時的訓練樣本路徑,
        // 只取最內層的目錄名稱,
        // 因為轉發之後會多一層receiveTargetPath
        String receivedTargetModelSamplePath = receiveTargetPath + File.separator + sampleDirectory;
        File receivedTargetModelSampleDirectory = new File(receivedTargetModelSamplePath); // 本地端待份派訓練樣本的完整路徑

        try {
            ArrayList<String> targetModelTrainingChecklist = new ArrayList<>();
            ArrayList<Map<String, Object>> allChildrenTree = (ArrayList<Map<String, Object>>) tree.get("children");
            allChildrenTree.forEach(childTree -> targetModelTrainingChecklist.add((String) childTree.get("name")));
            System.out.println("--------------------");
            // System.out.println(" - Dispatching Node: " + name);
            // System.out.println(" - Node resource   : " + localScore);
            // System.out.println(" - Tier resource   : " + groupScore);
            // System.out.println(" - Target model checklist  : " + targetModelTrainingChecklist); // ex: [312, 422,
            // 322, 1, 2, 3] , 讀取tree, 將要訓練的模型列出, 這邊不包含root.xml

            File[] subDirectoryList = receivedTargetModelSampleDirectory.listFiles();
            int subDirLength = subDirectoryList.length;
            // 子模型訓練, 總樣本大小統計, ex: 1, 2, 3
            Long rootModelSize = 0L;
            for (File value : subDirectoryList) {
                if (value.isDirectory()) { // 讀取待份派訓練樣本目錄中, 子目錄(也就是子模型)中的檔案, ex: [312, 422, 322, 1, 2, 3] 中的 1, 2, 3
                    // 三個子分支
                    File[] subModelDirectory = value.listFiles();
                    int subModelDirLength = subModelDirectory.length;
                    for (File file : subModelDirectory) {
                        String fileName = file.getName();
                        if (fileName.contains(".json")) { // 判斷如果是子分支模型, ex: 分支樹中的1, 2, 3, ...
                            String subModelName = fileName.replaceFirst(
                                    "[.][^.]+$",
                                    ""); // 利用檔案名稱(1.json, 212.json, ...)獲取子目錄中的模型編號, 移除'.'之後的字串, ex: 1.json -> 1
                            if (targetModelTrainingChecklist.contains(subModelName)) {
                                Long subModeSize = getDirectorySize(value);
                                rootModelSize += subModeSize;
                                trainingTaskList.put(subModelName, subModeSize);
                            }
                        }
                    }
                }
            } // 最上層模型訓練大小加入訓練清單(trainingTaskList)中, 該部分的size不包含312, 422, 322, 僅加1, 2, 3的大小
            if (!findJson(receivedTargetModelSamplePath).isEmpty()
                    && ((Integer) assignTask.get("tier") == 0
                            || targetModelTrainingChecklist.contains("root"))) { // 下層broker收到判斷是否有root.xml要訓練,
                // 若有才新增root到訓練任務清單內
                trainingTaskList.put("root", rootModelSize);
            }
            // 難度排序
            Stream<Map.Entry<String, Long>> sorted = trainingTaskList.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed());
            Map<String, Long> sortedTrainingDifficulty = sorted.collect(
                    Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
            List<String> sortedTrainingList = new ArrayList<>(sortedTrainingDifficulty.keySet());
            createDistributorList(sortedTrainingList); // 輸出一個站存的任務分派清單, 以方便統計尚未分派的任務

            // 分派中節點能力大到小排序
            groupScore.sort(Collections.reverseOrder());
            localScore.sort(Collections.reverseOrder());

            // System.out.println(" - Number of training tasks: " + trainingTaskList.size());
            // System.out.println(" - Model difficulty Sort   : " + sortedTrainingDifficulty);

            // 任務分派
            Map<String, Object> taskTreeInfo = new HashMap<>();
            List<String> transferredDirectoryList = new ArrayList<>();
            String subTask = "";
            String subTaskDirectory = "";
            String subModelJsonFile = "";
            int rank = 0;
            if (!isBroker) { // 分派中節點為一般節點
                try {
                    rank = groupScore.indexOf(localScore.getFirst());
                    subTask = sortedTrainingList.get(rank);

                    if (editorDistributorList(subTask)) { // 將已經分派的從佔存清單中移除, 並判斷是否需要分派給該節點
                        subTaskDirectory = subTask.equals("root")
                                ? receivedTargetModelSamplePath + File.separator
                                :
                                // 判斷是否為root模型
                                receivedTargetModelSamplePath + File.separator + subTask;
                        subModelJsonFile = findJson(subTaskDirectory);
                        transferredDirectoryList.add(subTaskDirectory);
                        taskTreeInfo = JsonFileUtil.ReadFile(new File(subModelJsonFile))
                                .toMap();
                        assignTask.put("tree", taskTreeInfo);
                    } else {
                        subTask = null;
                        assignTask = null;
                    }
                } catch (Exception e) {
                    System.out.println(
                            "Failed in distributor in " + name + ", exception message is: " + e.getMessage());
                    return null;
                }
            } else { // 向下層broker分派
                List<JSONObject> lowerLayerTaskList = new ArrayList<>(); // 下層節點能力大到小排序
                List<String> assignedTaskList = new ArrayList<>();
                int noNotForwardTaskCount = 0;
                for (Float lowerNodeScore : localScore) {
                    try {
                        int lowerLayerRank = groupScore.indexOf(lowerNodeScore); // 這邊不用-1是因為for迴圈本來就從0開始數
                        subTask = sortedTrainingList.get(lowerLayerRank);

                        if (editorDistributorList(subTask)) {
                            subTaskDirectory = subTask.equals("root")
                                    ? receivedTargetModelSamplePath + File.separator
                                    :
                                    // 判斷是否為root模型
                                    receivedTargetModelSamplePath + File.separator + subTask;
                            subModelJsonFile = findJson(subTaskDirectory);
                            lowerLayerTaskList.add(JsonFileUtil.ReadFile(new File(subModelJsonFile)));
                            assignedTaskList.add(subTask);
                        }
                    } catch (Exception e) {
                        System.out.println(
                                "Failed in distributor in " + name + ", exception message is: " + e.getMessage());
                        noNotForwardTaskCount += 1;
                    }
                }
                if (assignedTaskList.contains("root")) { // 判斷若要向下轉發的任務為root, 則直接指定傳輸目錄為根目錄
                    transferredDirectoryList.add(receivedTargetModelSamplePath + File.separator);
                } else {
                    for (String subDirectory : assignedTaskList) {
                        transferredDirectoryList.add(receivedTargetModelSamplePath + File.separator + subDirectory);
                    }
                }
                // 把下層要執行的所有子json merge起來, 變成Request
                if (noNotForwardTaskCount < localScore.size()) { // 判斷下層是否需要轉發任務
                    JSONObject mergedJson = new JSONObject();
                    mergedJson.put("children", new JSONArray(lowerLayerTaskList));
                    assignTask.put("tree", mergedJson.toMap());
                    subTask = assignedTaskList.toString(); // 最後顯示用
                } else {
                    assignTask = null;
                    subTask = null;
                }
            }

            // System.out.println(" - Computing power ranking : " + rank);
            // System.out.println(" - Assign training tasks   : " + subTask);

            if (assignTask != null) {
                System.out.println(" - Dispatching Node: " + name);
                System.out.println(" - Node resource   : " + localScore);
                System.out.println(" - Tier resource   : " + groupScore);
                System.out.println(" - Target model checklist  : "
                        + targetModelTrainingChecklist); // ex: [312, 422, 322, 1, 2, 3] , 讀取tree, 將要訓練的模型列出,
                // 這邊不包含root.xml
                System.out.println(" - Number of training tasks: " + trainingTaskList.size());
                System.out.println(" - Model difficulty Sort   : " + sortedTrainingDifficulty);
                System.out.println(" - Computing power ranking : " + rank);
                System.out.println(" - Assign training tasks   : " + subTask);
                // 訓練樣本傳輸
                System.out.println("--------------------");
                System.out.println("-------------------- Start transferring training samples:");
                for (String path : transferredDirectoryList) {
                    ArrayList<String> transferList = new ArrayList<>();
                    traverseDirectory(new File(path), transferList);
                    for (String file : transferList) {
                        if (isBroker) {
                            FileTransferClient.doTransfer(
                                    file,
                                    new File(file).getParentFile().toString(),
                                    "localhost",
                                    null,
                                    Integer.valueOf(lowerBrokerPort),
                                    requestId);
                        } else {
                            if (!name.equals(HiBAInitial.nodeName)) {
                                FileTransferClient.doTransfer(
                                        file,
                                        new File(file).getParentFile().toString(),
                                        null,
                                        networkChannel,
                                        HiBAInitial.DATA_PORT,
                                        requestId);
                            } else {
                                File source = new File(file);
                                File target = new File(receiveTargetPath + File.separator + "local" + File.separator
                                        + source.getName());
                                File targetDir =
                                        new File(receiveTargetPath + File.separator + "local" + File.separator);
                                if (!targetDir.exists()) {
                                    targetDir.mkdirs();
                                }
                                // Files.copy(source.toPath(), target.toPath());
                                try {
                                    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException e) {
                                    System.out.println("An error occurred while copying: " + e.getMessage());
                                }
                            }
                        }
                    }
                }
                System.out.println("--------------------");
            }

            return assignTask;
        } catch (Exception e) {
            System.out.println("Failed in distributor: " + e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> mappingLocal(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        // 使用新的類型安全實作
        TaskAssignment assignment = mappingLocalTask(localResource, resourceInThisTier);
        if (assignment != null) {
            return convertTaskAssignmentToMap(assignment);
        }
        return mapping(HiBAInitial.nodeName, localResource, null, resourceInThisTier);
    }

    @Override
    public void merge(Map<String, Map<String, Object>> allResult) {
        // 轉換為 TaskResult 並使用新方法
        Map<String, TaskResult> taskResults = new HashMap<>();
        allResult.forEach((name, information) -> {
            TaskResult result = TaskResult.fromMap(information);
            taskResults.put(name, result);
        });
        mergeResults(taskResults);

        // 保持原有行為
        this.assignedTaskResults.putAll(allResult);
    }

    @Override
    public Map<String, Object> getResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort) {
        // 使用新的類型安全實作
        TaskResult taskResult = getTaskResult(brokerChannel, requestNodeChannel, lowerBrokerPort);
        return convertTaskResultToMap(taskResult);
    }

    @Override
    public void handleTaskFail(String name, Map<String, Object> resource, Map<String, Object> Task) {
        System.out.println("****************** Handle failed tasks ******************");
        System.out.println("Handle node : " + name);
        System.out.println("Task details: " + Task);
        System.out.println("*********************************************************");
        File file = new File("./deepModelDistributorList");
        if (file.exists()) {
            file.delete();
        }
    }

    @Override
    public boolean hasMoreRound() {
        return false;
    }

    public static long getDirectorySize(File directory) {
        long size = 0;
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else {
                    size += getDirectorySize(file);
                }
            }
        } else if (directory.isFile()) {
            size += directory.length();
        }
        return size;
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

    public static void createDistributorList(List<String> sortedTrainingList) {
        String distributorList = "./deepModelDistributorList";
        Path path = Paths.get(distributorList);

        if (!Files.exists(path)) {
            try {
                Files.createFile(path);
                try (FileWriter fileWriter = new FileWriter(distributorList)) {
                    fileWriter.write(sortedTrainingList.toString());
                }
            } catch (IOException e) {
                System.out.println("Failed to create file: " + e.getMessage());
            }
        }
    }

    public static boolean editorDistributorList(String toRemove) {
        try {
            File distributorList = new File("./deepModelDistributorList");
            BufferedReader reader = new BufferedReader(new FileReader(distributorList));
            String content = reader.readLine();
            reader.close();

            // Remove brackets and split by comma
            String[] parts = content.substring(1, content.length() - 1).split(", ");

            // Convert array to list and remove the specified string
            List<String> list = new ArrayList<>(Arrays.asList(parts));
            if (list.contains(toRemove)) {
                list.remove(toRemove);
                String newContent = list.toString();
                BufferedWriter writer = new BufferedWriter(new FileWriter(distributorList));
                writer.write(newContent);
                writer.close();
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            System.out.println("Failed to editor file: " + e.getMessage());
            return false;
        }
    }
}
