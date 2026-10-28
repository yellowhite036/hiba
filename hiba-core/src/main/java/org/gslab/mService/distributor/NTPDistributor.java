package org.gslab.mService.distributor;

import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.Map;
import org.gslab.Distributor;
import org.gslab.model.TaskAssignment;
import org.gslab.model.TaskRequest;
import org.gslab.model.TaskResult;
import org.gslab.sdns.HiBAInitial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NTPDistributor extends Distributor {

    private static final Logger logger = LoggerFactory.getLogger(NTPDistributor.class);

    final Map<String, Map<String, Object>> nodeAllResult = new HashMap<>();
    final Map<String, TaskResult> nodeAllTaskResult = new HashMap<>(); // 類型安全的結果儲存

    @Override
    public Distributor getNewInstance() {
        return new NTPDistributor();
    }

    // === 新的類型安全方法實作 ===

    @Override
    public TaskAssignment mappingTask(
            String nodeName,
            Map<String, Object> resource,
            Channel networkChannel,
            Map<String, Object> resourceInThisTier) {
        try {
            TaskRequest taskRequest = getTaskRequest();
            if (taskRequest == null) {
                return null;
            }

            logger.info("Node: {} handle NTP task.", nodeName);

            TaskAssignment assignment = new TaskAssignment();
            assignment.setRequestName(taskRequest.getRequestName());
            assignment.setServiceName(taskRequest.getServiceName());
            assignment.setNodeName(nodeName);
            assignment.setDistributeFinish(false);

            return assignment;

        } catch (Exception e) {
            logger.info("Node: {} can't handle this NTP task.", nodeName);
            return null;
        }
    }

    @Override
    public TaskAssignment mappingLocalTask(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        return mappingTask("local", localResource, null, resourceInThisTier);
    }

    @Override
    public void mergeResults(Map<String, TaskResult> allResults) {
        nodeAllTaskResult.putAll(allResults);
    }

    @Override
    public TaskResult getTaskResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort) {
        TaskResult result = new TaskResult();
        TaskRequest taskRequest = getTaskRequest();

        if (taskRequest != null) {
            result.setRequestName(taskRequest.getRequestName());
            result.setServiceName(taskRequest.getServiceName());
        }

        result.setState(true);
        result.setSuccess(true);
        result.setEndTime(System.currentTimeMillis());

        // 設置 NTP 相關結果 - 將 TaskResult 物件轉換為可序列化的 Map
        Map<String, Object> serializableNodeResults = new HashMap<>();
        nodeAllTaskResult.forEach((nodeName, taskResult) -> {
            serializableNodeResults.put(nodeName, taskResult.toMap());
        });

        result.addResultData("nodeResults", serializableNodeResults);
        result.addResultData("totalNodes", nodeAllTaskResult.size());

        return result;
    }

    // === 原有的 Map 基礎方法（保持向後兼容） === @Override
    public Map<String, Object> mapping(
            String name, Map<String, Object> resource, Channel networkChannel, Map<String, Object> resourceInThisTier) {
        // 使用新方法並轉換回 Map
        TaskAssignment assignment = mappingTask(name, resource, networkChannel, resourceInThisTier);
        return assignment != null ? assignment.toMap() : new HashMap<>();
    }

    @Override
    public Map<String, Object> mappingLocal(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        return mapping("local", localResource, null, resourceInThisTier);
    }

    @Override
    public void merge(Map<String, Map<String, Object>> allResult) {
        nodeAllResult.putAll(allResult);

        // 轉換為 TaskResult 並使用新方法
        Map<String, TaskResult> taskResults = new HashMap<>();
        allResult.forEach((name, information) -> {
            TaskResult result = TaskResult.fromMap(information);
            taskResults.put(name, result);
        });
        mergeResults(taskResults);
    }

    @Override
    public Map<String, Object> getResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort) {
        Map<String, Object> resultInformation = new HashMap<>();
        Map<String, Object> workInformation = getWorkInformation();

        workInformation.put(HiBAInitial.nodeName, nodeAllResult);
        resultInformation.put("result", workInformation);

        // 使用新方法獲取結果
        TaskResult taskResult = getTaskResult(brokerChannel, requestNodeChannel, lowerBrokerPort);
        resultInformation.put("taskResult", taskResult.toMap());

        return resultInformation;
    }

    @Override
    public void handleTaskFail(String name, Map<String, Object> resource, Map<String, Object> Task) {}

    @Override
    public boolean hasMoreRound() {

        return false;
    }
}
