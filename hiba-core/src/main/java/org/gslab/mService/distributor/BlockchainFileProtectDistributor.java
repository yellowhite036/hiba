package org.gslab.mService.distributor;

import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.Map;
import org.gslab.Distributor;
import org.gslab.configuration.ServiceConfiguration;
import org.gslab.model.TaskAssignment;
import org.gslab.model.TaskRequest;
import org.gslab.model.TaskResult;
import org.gslab.sdns.HiBAInitial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockchainFileProtectDistributor extends Distributor {

    private static final Logger logger = LoggerFactory.getLogger(BlockchainFileProtectDistributor.class);
    private ServiceConfiguration config;
    private final Map<String, Map<String, Object>> nodeAllResult = new HashMap<>();
    private final Map<String, TaskResult> nodeAllTaskResult = new HashMap<>();
    private boolean assignedOnce = false;

    public BlockchainFileProtectDistributor() {
        logger.atDebug().log("BlockchainFileProtectDistributor initialized");
    }

    public BlockchainFileProtectDistributor(ServiceConfiguration config) {
        this.config = config;
        logger.atDebug().log("BlockchainFileProtectDistributor initialized with config");
    }

    @Override
    public Distributor getNewInstance() {
        return new BlockchainFileProtectDistributor(config);
    }

    @Override
    public void initial() {
        assignedOnce = false;
        nodeAllResult.clear();
        nodeAllTaskResult.clear();
    }

    @Override
    public Map<String, Object> mapping(
            String name, Map<String, Object> resource, Channel networkChannel, Map<String, Object> resourceInThisTier) {
        TaskAssignment assignment = mappingTask(name, resource, networkChannel, resourceInThisTier);
        return assignment != null ? assignment.toMap() : null;
    }

    @Override
    public Map<String, Object> mappingLocal(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        TaskAssignment assignment = mappingLocalTask(localResource, resourceInThisTier);
        return assignment != null ? assignment.toMap() : null;
    }

    @Override
    public void merge(Map<String, Map<String, Object>> allResult) {
        nodeAllResult.putAll(allResult);

        Map<String, TaskResult> taskResults = new HashMap<>();
        allResult.forEach((name, information) -> taskResults.put(name, TaskResult.fromMap(information)));
        mergeResults(taskResults);
    }

    @Override
    public Map<String, Object> getResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort) {
        if (nodeAllResult.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "No execution result received");
            return error;
        }

        if (nodeAllResult.size() == 1) {
            Map<String, Object> singleResult = nodeAllResult.values().iterator().next();
            if (singleResult != null
                    && !singleResult.containsKey("success")
                    && (singleResult.containsKey("error") || singleResult.containsKey("errorMessage"))) {
                singleResult.put("success", false);
            }
            return singleResult;
        }

        Map<String, Object> result = new HashMap<>();
        boolean overallSuccess = true;
        for (Map<String, Object> nodeResult : nodeAllResult.values()) {
            Object success = nodeResult != null ? nodeResult.get("success") : null;
            if (success instanceof Boolean successFlag && !successFlag) {
                overallSuccess = false;
                break;
            }
        }
        result.put("success", overallSuccess);
        if (!overallSuccess) {
            result.put("errorMessage", "One or more nodes failed to protect files");
        }
        result.put("allResult", nodeAllResult);
        return result;
    }

    @Override
    public void handleTaskFail(String name, Map<String, Object> resource, Map<String, Object> task) {
        // No retry policy defined for this distributor yet.
    }

    @Override
    public boolean hasMoreRound() {
        return false;
    }

    @Override
    public TaskAssignment mappingTask(
            String receiveTaskNode,
            Map<String, Object> resource,
            Channel networkChannel,
            Map<String, Object> resourceInThisTier) {
        logger.atDebug().log("BlockchainFileProtect is broker-only; skip remote assignment");
        return null;
    }

    @Override
    public TaskAssignment mappingLocalTask(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        if (!HiBAInitial.blockchainFileProtect) {
            logger.atInfo().log("BlockchainFileProtect disabled locally, skip local assignment");
            return null;
        }

        TaskRequest taskRequest = getTaskRequest();
        if (taskRequest == null || assignedOnce) {
            return null;
        }

        TaskAssignment assignment = buildAssignment(taskRequest, HiBAInitial.nodeName);
        assignment.setDistributeFinish(true);
        assignedOnce = true;
        logger.atInfo().log("Assigned blockchain protect task to local node");
        return assignment;
    }

    @Override
    public void mergeResults(Map<String, TaskResult> allResults) {
        nodeAllTaskResult.putAll(allResults);
    }

    @Override
    public TaskResult getTaskResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort) {
        TaskResult result = new TaskResult();
        TaskRequest taskRequest = getTaskRequest();
        result.setRequestName(taskRequest != null ? taskRequest.getRequestName() : "BlockchainFileProtect");
        result.setServiceName(taskRequest != null ? taskRequest.getServiceName() : "BlockchainFileProtect");
        result.setEndTime(System.currentTimeMillis());

        Map<String, Object> serializableTaskResults = new HashMap<>();
        nodeAllTaskResult.forEach((nodeName, taskResult) -> serializableTaskResults.put(nodeName, taskResult.toMap()));
        result.addResultData("assignedTaskResults", serializableTaskResults);

        if (!nodeAllResult.isEmpty()) {
            result.setState(true);
            result.setSuccess(true);
            Map<String, Object> resultPayload = new HashMap<>();
            resultPayload.put("nodeResults", nodeAllResult);
            result.setResult(resultPayload);
        } else {
            result.setState(false);
            result.setSuccess(false);
            result.setErrorMessage("No execution result received");
        }

        return result;
    }

    private TaskAssignment buildAssignment(TaskRequest taskRequest, String nodeName) {
        TaskAssignment assignment = new TaskAssignment();
        assignment.setRequestId(taskRequest.getRequestId());
        assignment.setRequestName(taskRequest.getRequestName());
        assignment.setServiceName(taskRequest.getServiceName());
        assignment.setNodeName(nodeName);
        assignment.setReceiveTargetPath(taskRequest.getReceiveTargetPath());
        assignment.setBrokerRoute(taskRequest.getBrokerRoute());

        if (taskRequest.hasTransferFile()) {
            assignment.setTransferFileList(taskRequest.getTransferFileList());
        }

        if (taskRequest.getUploadedFiles() != null
                && !taskRequest.getUploadedFiles().isEmpty()) {
            assignment.addTaskData("uploadedFiles", taskRequest.getUploadedFiles());
        }

        if (taskRequest.getRequestDate() != null) {
            assignment.addTaskData("requestDate", taskRequest.getRequestDate());
        }

        if (taskRequest.getTaskSpecificData() != null
                && !taskRequest.getTaskSpecificData().isEmpty()) {
            taskRequest.getTaskSpecificData().forEach(assignment::addTaskData);
        }

        return assignment;
    }
}
