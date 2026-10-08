package org.gslab;

import io.netty.channel.Channel;
import java.util.Map;
import org.gslab.model.RequestId;
import org.gslab.model.TaskAssignment;
import org.gslab.model.TaskRequest;
import org.gslab.model.TaskResult;

public abstract class Distributor {

    private Map<String, Object> workInformation;
    private TaskRequest taskRequest; // 新的類型安全工作資訊

    private int clientSize = 0;
    private int brokerTier = 0;
    private RequestId requestID = RequestId.UNKNOWN;

    public Distributor() {}

    public void setRequestID(RequestId requestId) {
        this.requestID = requestId;
    }

    public void setWorkInformation(Map<String, Object> workInformation) {
        this.workInformation = workInformation;
        // 同時更新類型安全版本
        if (workInformation != null) {
            this.taskRequest = TaskRequest.fromMap(workInformation);
        }
    }

    /**
     * 類型安全的工作資訊設定方法
     */
    public void setTaskRequest(TaskRequest taskRequest) {
        this.taskRequest = taskRequest;
        // 保持向後兼容
        if (taskRequest != null) {
            this.workInformation = taskRequest.toMap();
        }
    }

    public void setBrokerTier(int brokerTier) {
        this.brokerTier = brokerTier;
    }

    public void setClientSize(int size) {
        this.clientSize = size;
    }

    public int getClientSize() {
        return clientSize;
    }

    public Map<String, Object> getWorkInformation() {
        return workInformation;
    }

    /**
     * 類型安全的工作資訊取得方法
     */
    public TaskRequest getTaskRequest() {
        return taskRequest;
    }

    public int getBrokerTier() {
        return brokerTier;
    }

    public RequestId getRequestID() {
        return requestID;
    }

    public void sendFileToRequestNode(Channel requestNodeChannel) {}

    public Distributor getNewInstance() {
        return this;
    }

    public void initial() {}

    // === 原有的 Map 基礎方法（保持向後兼容） ===
    public abstract Map<String, Object> mapping(
            String name, Map<String, Object> resource, Channel networkChannel, Map<String, Object> resourceInThisTier);

    public abstract Map<String, Object> mappingLocal(
            Map<String, Object> localResource, Map<String, Object> resourceInThisTier);

    public abstract void merge(Map<String, Map<String, Object>> allResult);

    public abstract Map<String, Object> getResult(
            Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort);

    public abstract void handleTaskFail(String name, Map<String, Object> resource, Map<String, Object> Task);

    public abstract boolean hasMoreRound();

    // === 新的類型安全方法 ===

    /**
     * 類型安全的任務分配方法
     *
     * @param nodeName           節點名稱
     * @param resource           節點資源資訊（暫時保持 Map 格式，後續階段會進一步重構）
     * @param networkChannel     網路通道
     * @param resourceInThisTier 本層級資源資訊
     * @return TaskAssignment 物件，如果沒有任務分配則返回 null
     */
    public TaskAssignment mappingTask(
            String nodeName,
            Map<String, Object> resource,
            Channel networkChannel,
            Map<String, Object> resourceInThisTier) {
        // 預設實作：調用原有方法並轉換
        Map<String, Object> taskMap = mapping(nodeName, resource, networkChannel, resourceInThisTier);
        if (taskMap == null || taskMap.isEmpty()) {
            return null;
        }
        return org.gslab.model.TaskAssignment.fromMap(taskMap);
    }

    /**
     * 類型安全的本地任務分配方法
     */
    public TaskAssignment mappingLocalTask(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        Map<String, Object> taskMap = mappingLocal(localResource, resourceInThisTier);
        if (taskMap == null || taskMap.isEmpty()) {
            return null;
        }
        return org.gslab.model.TaskAssignment.fromMap(taskMap);
    }

    /**
     * 類型安全的結果合併方法
     *
     * @param allResult 所有節點的任務結果
     */
    public void mergeResults(Map<String, TaskResult> allResult) {
        // 轉換為 Map 格式調用原有方法
        Map<String, Map<String, Object>> mapResults = new java.util.HashMap<>();
        allResult.forEach((nodeName, taskResult) -> mapResults.put(nodeName, taskResult.toMap()));
        merge(mapResults);
    }

    /**
     * 類型安全的結果取得方法
     */
    public TaskResult getTaskResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort) {
        Map<String, Object> resultMap = getResult(brokerChannel, requestNodeChannel, lowerBrokerPort);
        if (resultMap == null || resultMap.isEmpty()) {
            return new TaskResult();
        }
        return TaskResult.fromMap(resultMap);
    }
}
