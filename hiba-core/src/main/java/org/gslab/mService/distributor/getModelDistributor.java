package org.gslab.mService.distributor;

import io.netty.channel.Channel;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gslab.Distributor;
import org.gslab.model.RequestId;
import org.gslab.model.TaskAssignment;
import org.gslab.model.TaskRequest;
import org.gslab.model.TaskResult;
import org.gslab.sdns.FileTransferClient;
import org.gslab.sdns.HiBAInitial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class getModelDistributor extends Distributor {

    private static final Logger logger = LoggerFactory.getLogger(getModelDistributor.class);
    // private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    private final Map<String, Object> assignedTaskResults = new HashMap<>();
    private final Map<String, TaskResult> assignedTaskTaskResults = new HashMap<>(); // 類型安全的結果儲存

    @Override
    public Distributor getNewInstance() {
        return new getModelDistributor();
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

            // request內容: 所要求的模型類別 & 路口類別
            List<List<String>> requestCategory = new ArrayList<>(taskRequest.getRecognitionCategory());
            List<String> requestRoadType = taskRequest.getRoadType();
            String sendRequestNode = taskRequest.getNodeName();

            // 節點資源
            ArrayList<ArrayList<String>> nodeCategory =
                    (ArrayList<ArrayList<String>>) resource.get("recognitionCategory");
            ArrayList<String> nodeRoadType = (ArrayList<String>) resource.get("roadType");

            // 設定向下轉發的task, 依照節點有的模型資源來派工
            List<List<String>> taskCategories = new ArrayList<>();
            if (nodeRoadType.equals(requestRoadType) || nodeRoadType.containsAll(requestRoadType)) {
                // 查看節點是否有相對應的模型
                for (List<String> reqCategory : requestCategory) {
                    for (ArrayList<String> nodeCategory_ : nodeCategory) {
                        if (reqCategory.equals(nodeCategory_)) {
                            taskCategories.add(new ArrayList<>(nodeCategory_));
                        }
                    }
                }

                // 更新原 TaskRequest，移除已分配的任務
                requestCategory.removeAll(taskCategories);
                taskRequest.setRecognitionCategory(requestCategory);
                setTaskRequest(taskRequest); // 更新待指派的request
            }

            if (!receiveTaskNode.equals(sendRequestNode) && !taskCategories.isEmpty()) {
                TaskAssignment assignment = new TaskAssignment();
                assignment.setRequestName(taskRequest.getRequestName());
                assignment.setServiceName(taskRequest.getServiceName());
                assignment.setNodeName(receiveTaskNode);
                assignment.setRecognitionCategory(taskCategories);
                assignment.setRoadType(requestRoadType);
                assignment.setReceiveTargetPath(taskRequest.getReceiveTargetPath());
                assignment.setDistributeFinish(false);

                // 確保目標目錄存在
                String receiveTargetPath = taskRequest.getReceiveTargetPath();
                if (receiveTargetPath != null) {
                    File requestDir = new File(receiveTargetPath);
                    requestDir.mkdirs();
                }

                logger.atInfo().log("Node: {} handle model retrieval task.", receiveTaskNode);
                return assignment;
            } else {
                logger.atInfo().log("Node: {} can't handle this model task.", receiveTaskNode);
                return null;
            }
        } catch (Exception e) {
            logger.atError().log("Error in mappingTask: {}", e.getMessage());
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

        result.setState(true);
        result.setSuccess(true);
        result.setEndTime(System.currentTimeMillis());

        // 處理模型檔案傳輸
        if (requestNodeChannel != null && taskRequest != null) {
            try {
                String requestNodeReceiveTargetPath = taskRequest.getReceiveTargetPath();
                if (requestNodeReceiveTargetPath != null) {
                    File getModel = new File(requestNodeReceiveTargetPath);

                    if (getModel.exists() && getModel.isDirectory()) {
                        File[] modelDir = getModel.listFiles();
                        if (modelDir != null) {
                            List<String> transferredModels = new ArrayList<>();

                            for (File modelFile : modelDir) {
                                String modelPath = modelFile.toString();
                                System.out.println("Find model: " + modelPath);
                                transferredModels.add(modelPath);

                                try {
                                    if (lowerBrokerPort != null) {
                                        FileTransferClient.doTransfer(
                                                modelPath,
                                                requestNodeReceiveTargetPath,
                                                "localhost",
                                                null,
                                                lowerBrokerPort,
                                                requestId);
                                    } else {
                                        FileTransferClient.doTransfer(
                                                modelPath,
                                                requestNodeReceiveTargetPath,
                                                null,
                                                requestNodeChannel,
                                                HiBAInitial.DATA_PORT,
                                                requestId);
                                    }
                                } catch (Exception e) {
                                    logger.atError().setCause(e).log("Failed to transfer model: {}", modelPath);
                                    result.setSuccess(false);
                                }
                            }

                            result.addResultData("transferredModels", transferredModels);
                            result.addResultData("totalModels", transferredModels.size());
                        }
                    }
                }
            } catch (Exception e) {
                logger.atError().log("Error in model transfer: {}", e.getMessage());
                result.setSuccess(false);
                result.setErrorMessage("Model transfer failed: " + e.getMessage());
            }
        }

        // 設置任務結果 - 將 TaskResult 物件轉換為可序列化的 Map
        Map<String, Object> serializableTaskResults = new HashMap<>();
        assignedTaskTaskResults.forEach((taskName, taskResult) -> {
            serializableTaskResults.put(taskName, taskResult.toMap());
        });
        result.addResultData("assignedTaskResults", serializableTaskResults);
        return result;
    }

    // === 輔助方法 ===

    private Map<String, Object> convertTaskAssignmentToMap(TaskAssignment assignment) {
        Map<String, Object> map = new HashMap<>();
        map.put("requestName", assignment.getRequestName());
        map.put("serviceName", assignment.getServiceName());
        map.put("nodeName", assignment.getNodeName());
        map.put("recognitionCategory", assignment.getRecognitionCategory());
        map.put("roadType", assignment.getRoadType());
        map.put("receiveTargetPath", assignment.getReceiveTargetPath()); // 保持原有拼字以兼容舊系統
        map.put("distributeFinish", assignment.isDistributeFinish());
        return map;
    }

    private Map<String, Object> convertTaskResultToMap(TaskResult result) {
        Map<String, Object> map = new HashMap<>();
        map.put("result", result.getResult());
        map.put("allResult", assignedTaskResults);
        return map;
    }

    // === 原有的 Map 基礎方法（保持向後兼容） ===
    @Override
    public Map<String, Object> mapping(
            String receiveTaskNode,
            Map<String, Object> resource,
            Channel networkChannel,
            Map<String, Object> resourceInThisTier) {
        // 使用新的類型安全實作
        TaskAssignment assignment = mappingTask(receiveTaskNode, resource, networkChannel, resourceInThisTier);
        if (assignment != null) {
            return convertTaskAssignmentToMap(assignment);
        }
        return null;
    }

    @Override
    public Map<String, Object> mappingLocal(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        // 使用新的類型安全實作
        TaskAssignment assignment = mappingLocalTask(localResource, resourceInThisTier);
        if (assignment != null) {
            return convertTaskAssignmentToMap(assignment);
        }
        return null;
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
        TaskResult result = getTaskResult(brokerChannel, requestNodeChannel, lowerBrokerPort);
        return convertTaskResultToMap(result);
    }

    @Override
    public void handleTaskFail(String name, Map<String, Object> resource, Map<String, Object> Task) { // 節點斷線的話, 處理方式
        // TODO Auto-generated method stub
    }

    @Override
    public boolean hasMoreRound() {
        // TODO Auto-generated method stub
        return false;
    }
}
