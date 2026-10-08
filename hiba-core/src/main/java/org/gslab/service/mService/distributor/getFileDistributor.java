package org.gslab.service.mService.distributor;

import io.netty.channel.Channel;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gslab.Distributor;
import org.gslab.Tool.PathUtil;
import org.gslab.configuration.ServiceConfiguration;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.model.RequestId;
import org.gslab.model.TaskAssignment;
import org.gslab.model.TaskRequest;
import org.gslab.sdns.FileTransferClient;
import org.gslab.sdns.HiBAInitial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class getFileDistributor extends Distributor {

    private static final Logger logger = LoggerFactory.getLogger(getFileDistributor.class);
    private ServiceConfiguration config;

    // 儲存所有節點的執行結果
    private final Map<String, Map<String, Object>> nodeAllResult = new HashMap<>();

    @Override
    public Distributor getNewInstance() {
        return new getFileDistributor();
    }

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

            // 取得請求的檔案名稱
            String requestedFileName = taskRequest.getFileName();
            if (requestedFileName == null || requestedFileName.isEmpty()) {
                logger.atInfo().log("No fileName specified in request");
                return null;
            }

            // 檢查節點資源清單 (resource 中的 "fileName" 對應到 service.json 中的 resource.fileName)
            String sendRequestNode = taskRequest.getNodeName();
            Object resourceObj = resource.get("fileName");
            List<String> nodeFileResources = new ArrayList<>();

            if (resourceObj instanceof List) {
                nodeFileResources = (List<String>) resourceObj;
            }

            boolean canHandleFile = false;

            // 檢查節點是否有請求的檔案
            for (String availableFile : nodeFileResources) {
                if (availableFile.equals(requestedFileName)) {
                    canHandleFile = true;
                    break;
                }
            }
            if (!receiveTaskNode.equals(sendRequestNode) && canHandleFile) {
                TaskAssignment assignment = new TaskAssignment();
                assignment.setRequestName(taskRequest.getRequestName());
                assignment.setServiceName(taskRequest.getServiceName());
                assignment.setNodeName(receiveTaskNode);

                // 設置檔案名稱（單一檔案）
                assignment.setFileName(requestedFileName);

                // 設置檔案清單（兼容性）
                List<String> fileList = new ArrayList<>();
                fileList.add(requestedFileName);
                assignment.setFiles(fileList);

                assignment.setReceiveTargetPath(taskRequest.getReceiveTargetPath());
                assignment.setDistributeFinish(false);

                logger.atInfo().log("Assigned file task {} to node: {}", requestedFileName, receiveTaskNode);
                return assignment;
            }

            return null;

        } catch (Exception e) {
            logger.atError().log("Error in file task mapping: ", e);
            return null;
        }
    }

    // 實作必需的抽象方法
    @Override
    public Map<String, Object> mapping(
            String name, Map<String, Object> resource, Channel networkChannel, Map<String, Object> resourceInThisTier) {
        TaskAssignment assignment = mappingTask(name, resource, networkChannel, resourceInThisTier);
        return assignment != null ? assignment.toMap() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> mappingLocal(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        try {
            TaskRequest taskRequest = getTaskRequest();
            if (taskRequest == null) {
                return null;
            }

            // 取得請求的檔案名稱
            String requestedFileName = taskRequest.getFileName();
            if (requestedFileName == null || requestedFileName.isEmpty()) {
                logger.atInfo().log("No fileName specified in local mapping request");
                return null;
            }

            // 檢查本地資源清單中是否有請求的檔案
            Object resourceObj = resourceInThisTier.get("fileName");
            List<String> localFileResources = new ArrayList<>();

            if (resourceObj instanceof List) {
                localFileResources = (List<String>) resourceObj;
            } // 檢查本地是否有請求的檔案

            if (localFileResources.contains(requestedFileName)) {
                TaskAssignment assignment = new TaskAssignment();
                assignment.setRequestName(taskRequest.getRequestName());
                assignment.setServiceName(taskRequest.getServiceName());
                assignment.setNodeName("localhost"); // 使用 "localhost" 而不是 "local"
                assignment.setFileName(requestedFileName); // 設置 fileName 欄位

                // 設置檔案清單
                List<String> fileList = new ArrayList<>();
                fileList.add(requestedFileName);
                assignment.setFiles(fileList);

                assignment.setReceiveTargetPath(taskRequest.getReceiveTargetPath());
                assignment.setDistributeFinish(false);

                logger.atInfo().log("Assigned local file task: {}", requestedFileName);
                return assignment.toMap();
            }

            return null;

        } catch (Exception e) {
            logger.atError().log("Error in local file task mapping: ", e);
            return null;
        }
    }

    @Override
    public void merge(Map<String, Map<String, Object>> allResult) {
        // 儲存合併的結果供 getResult() 使用
        nodeAllResult.putAll(allResult);

        // 彙整多個節點的檔案傳輸結果
        logger.atInfo().log("Merging file transfer results from {} nodes", allResult.size());

        int successfulTransfers = 0;
        int failedTransfers = 0;
        List<String> successfulNodes = new ArrayList<>();
        List<String> failedNodes = new ArrayList<>();

        // 用於檢查metadata一致性
        String referenceFileHash = null;
        List<String> metadataInconsistentNodes = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : allResult.entrySet()) {
            String nodeName = entry.getKey();
            Map<String, Object> result = entry.getValue();
            Boolean transferred = (Boolean) result.get("transferred");

            if (transferred == null || !transferred) {
                failedTransfers++;
                failedNodes.add(nodeName);
                String error = (String) result.get("error");
                logger.atWarn().log("File transfer failed from node {}: {}", nodeName, error);
                continue;
            }

            successfulTransfers++;
            successfulNodes.add(nodeName);
            logger.atInfo().log("File transfer successful from node: {}", nodeName);

            // 檢查metadata一致性
            String fileHash = (String) result.get("fileHash");

            if (fileHash == null) {
                logger.atWarn().log("Node {} did not provide blockchain metadata (fileHash or missing)", nodeName);
                continue;
            }

            if (referenceFileHash == null) {
                // 設定第一個成功節點的資料作為參考
                referenceFileHash = fileHash;
                logger.atInfo().log("Setting reference metadata from node {}: fileHash={}", nodeName, fileHash);
                continue;
            }

            // 檢查與參考節點的metadata是否一致
            if (referenceFileHash.equals(fileHash)) {
                logger.atInfo().log("Metadata consistency verified for node {}", nodeName);
                continue;
            }

            metadataInconsistentNodes.add(nodeName);
            logger.atWarn()
                    .log(
                            "Metadata inconsistency detected from node {}: expected fileHash={}, got={}",
                            nodeName,
                            referenceFileHash,
                            fileHash);
        }

        // 記錄metadata一致性檢查結果
        if (!metadataInconsistentNodes.isEmpty()) {
            logger.atError().log("METADATA INCONSISTENCY DETECTED! Inconsistent nodes: {}", metadataInconsistentNodes);
            logger.atError()
                    .log("This may indicate file corruption or security issues. Manual investigation required.");
        } else if (referenceFileHash != null) {
            logger.atInfo().log("All nodes provided consistent metadata. FileHash: {}", referenceFileHash);
        }

        logger.atInfo().log(
                "Merge summary: {} successful transfers from nodes {}, {} failed transfers from nodes {}",
                successfulTransfers,
                successfulNodes,
                failedTransfers,
                failedNodes);
    }

    @Override
    public Map<String, Object> getResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort) {
        Map<String, Object> resultInformation = new HashMap<>();
        Map<String, Object> result = new HashMap<>();

        result.put("serviceName", "getFile");
        result.put("status", "completed");
        result.put("timestamp", System.currentTimeMillis());

        // 處理檔案傳輸到請求節點
        TaskRequest currentTaskRequest = getTaskRequest();
        RequestId requestId = currentTaskRequest != null ? currentTaskRequest.getRequestId() : null;

        // Fallback: If TaskRequest is null (e.g., in handleBrokerRouteAndFinish),
        // try to get requestId from workInformation
        if (requestId == null) {
            Map<String, Object> workInfo = getWorkInformation();
            if (workInfo != null && workInfo.containsKey("requestId")) {
                requestId = (RequestId) workInfo.get("requestId");
                logger.atDebug().log("Retrieved requestId from workInformation: {}", requestId);
            }
        }

        // Final fallback: generate a unique ID if still null
        if (requestId == null) {
            requestId = RequestId.UNKNOWN;
            logger.atWarn().log("requestId not found in TaskRequest or workInformation, generated: {}", requestId);
        }

        // CRITICAL: Put requestId into result map so it propagates through RequestFinishMessage
        // This ensures downstream brokers (e.g., Node7 forwarding to Node9) can extract it
        result.put("requestId", requestId);

        // 從 TaskRequest 中取得 requestTimeStamp 避免 outputResponse 警告
        if (currentTaskRequest != null) {
            result.put("requestTimeStamp", currentTaskRequest.getRequestTimeStamp());
        }

        if (currentTaskRequest != null && currentTaskRequest.getFileName() != null) {
            String requestedFileName = currentTaskRequest.getFileName();
            // 優先使用 workInformation 中的原始 receiveTargetPath（來自原始請求）
            Map<String, Object> workInfo = getWorkInformation();
            String baseReceiveTargetPath = currentTaskRequest.getReceiveTargetPath();
            logger.atDebug().log("Current receiveTargetPath from TaskRequest: {}", baseReceiveTargetPath);

            if (workInfo != null) {
                logger.atDebug().log("workInformation keys: {}", workInfo.keySet());
                if (workInfo.containsKey("receiveTargetPath")) {
                    String originalReceiveTargetPath = (String) workInfo.get("receiveTargetPath");
                    logger.atDebug().log("Found receiveTargetPath in workInformation: {}", originalReceiveTargetPath);
                    if (originalReceiveTargetPath != null && !originalReceiveTargetPath.isEmpty()) {
                        baseReceiveTargetPath = originalReceiveTargetPath;
                        logger.atInfo().log(
                                "Using original receiveTargetPath from workInformation: {}", baseReceiveTargetPath);
                    }
                } else {
                    logger.atDebug().log("receiveTargetPath not found in workInformation");
                }
            } else {
                logger.atDebug().log("workInformation is null");
            }

            // 確保 baseReceiveTargetPath 不為 null
            if (baseReceiveTargetPath == null || baseReceiveTargetPath.isEmpty()) {
                java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_S");
                String dateTime = dateFormat.format(new java.util.Date());
                baseReceiveTargetPath = "./receiveFile/getFile_" + dateTime + "/";
                logger.atWarn().log("baseReceiveTargetPath is null or empty, using default: {}", baseReceiveTargetPath);
            }

            try {
                FileCollectionResult fileCollection = collectAvailableFiles(requestedFileName, workInfo);
                List<File> availableFiles = fileCollection.availableFiles;
                List<String> sourceNodes = fileCollection.sourceNodes;

                // 傳輸所有可用的檔案到請求節點
                if (availableFiles.isEmpty()) {
                    result.put("transferred", false);
                    result.put("error", "File not found in any location: " + requestedFileName);
                    logger.atWarn()
                            .log("File {} not found in local files or received from other nodes", requestedFileName);
                } else {
                    logger.atDebug()
                            .addKeyValue("requestId", requestId)
                            .log("Preparing to transfer {} files to request node", availableFiles.size());

                    List<Map<String, Object>> transferResults = performFileTransfers(
                            availableFiles,
                            sourceNodes,
                            requestedFileName,
                            baseReceiveTargetPath,
                            brokerChannel,
                            requestNodeChannel,
                            lowerBrokerPort,
                            requestId);

                    populateTransferResults(result, transferResults, availableFiles, requestedFileName, sourceNodes);
                }
            } catch (Exception e) {
                result.put("transferred", false);
                result.put("error", "Transfer failed: " + e.getMessage());
                logger.atError().log("File transfer failed: ", e);
            }
        }

        // 設置 result 字段，這是客戶端期望的
        resultInformation.put("result", result);

        // 確保最外層也有 transferred 欄位，供上層 broker 使用
        resultInformation.put("transferred", result.get("transferred"));

        logger.atInfo().log("File service completed, returning result to request node");
        return resultInformation;
    }

    @Override
    public void handleTaskFail(String name, Map<String, Object> resource, Map<String, Object> task) {
        logger.atWarn().log("File task failed for node: {} with resource: {}", name, resource);

        // 可以在這裡記錄失敗原因，或者嘗試重新分配任務
        String taskInfo = task != null ? task.toString() : "Unknown task";
        logger.atWarn().log("Failed task details: {}", taskInfo);
    }

    @Override
    public boolean hasMoreRound() {
        // getFile 服務通常只需要一輪，檔案傳輸完成後就結束
        return false;
    }

    /**
     * Constructor with ServiceConfiguration
     */
    public getFileDistributor(ServiceConfiguration config) {
        this.config = config;
        logger.atInfo().log("getFileDistributor initialized with ServiceConfiguration: {}", config.getServiceName());
    }

    /**
     * Default constructor for backward compatibility
     */
    public getFileDistributor() {
        logger.atInfo().log("getFileDistributor initialized with default constructor");
    }

    /**
     * Set service configuration (for backward compatibility)
     */
    public void setServiceConfiguration(ServiceConfiguration config) {
        this.config = config;
        logger.atInfo().log("Service configuration set for getFileDistributor: {}", config.getServiceName());
    }

    private static class FileCollectionResult {

        final List<File> availableFiles;
        final List<String> sourceNodes;

        FileCollectionResult(List<File> availableFiles, List<String> sourceNodes) {
            this.availableFiles = availableFiles;
            this.sourceNodes = sourceNodes;
        }
    }

    private FileCollectionResult collectAvailableFiles(String requestedFileName, Map<String, Object> workInfo) {
        List<File> availableFiles = new ArrayList<>();
        List<String> sourceNodes = new ArrayList<>();

        // 1. 檢查本地files目錄的檔案 (broker 本身的檔案)
        collectLocalFiles(requestedFileName, availableFiles, sourceNodes);

        // 2. 檢查從其他節點收到的檔案（在 broker 的 files/{nodeName}/ 目錄中）
        collectNodeFiles(requestedFileName, availableFiles, sourceNodes);

        // 3. 檢查從上層broker回傳的檔案資訊 (在 receiveFile 目錄中)
        collectTransferredFiles(requestedFileName, workInfo, availableFiles, sourceNodes);

        logger.atDebug().log("Total availableFiles found: {}, sourceNodes: {}", availableFiles.size(), sourceNodes);
        return new FileCollectionResult(availableFiles, sourceNodes);
    }

    private void collectLocalFiles(String requestedFileName, List<File> availableFiles, List<String> sourceNodes) {
        File localFilesDir = new File("./files");
        File localFile = new File(localFilesDir, requestedFileName);
        if (localFile.exists() && localFile.isFile()) {
            availableFiles.add(localFile);
            sourceNodes.add(HiBAInitial.nodeName);
            logger.atInfo().log("Found local file: {}", localFile.getPath());
        }
    }

    private void collectNodeFiles(String requestedFileName, List<File> availableFiles, List<String> sourceNodes) {
        logger.atDebug().log("nodeAllResult contains {} entries", nodeAllResult.size());

        for (Map.Entry<String, Map<String, Object>> entry : nodeAllResult.entrySet()) {
            String nodeName = entry.getKey();
            Map<String, Object> nodeResult = entry.getValue();

            logger.atDebug().log("Processing node {} with result: {}", nodeName, nodeResult);

            if (nodeName.equals(HiBAInitial.nodeName)) {
                logger.atDebug().log("Skipping local node: {}", nodeName);
                continue;
            }

            Boolean transferred = (Boolean) nodeResult.get("transferred");
            logger.atDebug().log("Node {} transferred: {}", nodeName, transferred);

            if (transferred == null || !transferred) {
                logger.atWarn().log("Node {} transferred is null or false", nodeName);
                continue;
            }

            if (nodeResult.containsKey("result")) {
                processNodeResultTransfers(nodeResult, requestedFileName, availableFiles, sourceNodes);
            } else {
                processLegacyNodeFiles(nodeName, requestedFileName, availableFiles, sourceNodes);
            }
        }
    }

    private void processNodeResultTransfers(
            Map<String, Object> nodeResult,
            String requestedFileName,
            List<File> availableFiles,
            List<String> sourceNodes) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nodeResultData = (Map<String, Object>) nodeResult.get("result");
        if (nodeResultData.containsKey("transfers")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) nodeResultData.get("transfers");
            for (Map<String, Object> transfer : transfers) {
                String targetPath = (String) transfer.get("targetPath");

                if (targetPath == null) {
                    continue;
                }

                File transferredFile = new File(targetPath, requestedFileName);
                logger.atDebug().log("Checking for transferred file at path: {}", transferredFile.getPath());

                if (transferredFile.exists() && transferredFile.isFile()) {
                    availableFiles.add(transferredFile);
                    sourceNodes.add((String) transfer.get("sourceNode"));

                    preserveBlockchainMetadata(transfer, nodeResult);
                    logger.atInfo().log(
                            "Found transferred file from {} at: {} with metadata",
                            transfer.get("sourceNode"),
                            transferredFile.getPath());
                }
            }
        }
    }

    private void preserveBlockchainMetadata(Map<String, Object> transfer, Map<String, Object> nodeResult) {
        String actualSourceNode = (String) transfer.get("sourceNode");
        String[] metadataKeys = {"fileHash", "fileMetadata", "fileId"};

        for (String key : metadataKeys) {
            if (transfer.containsKey(key)) {
                nodeResult.put(key, transfer.get(key));
                nodeAllResult
                        .computeIfAbsent(actualSourceNode, k -> new HashMap<>())
                        .put(key, transfer.get(key));
            }
        }
    }

    private void processLegacyNodeFiles(
            String nodeName, String requestedFileName, List<File> availableFiles, List<String> sourceNodes) {
        Path nodeFilesPath = PathUtil.buildPath("files", nodeName);
        Path nodeFilePath = PathUtil.buildPath(nodeFilesPath, requestedFileName);
        File nodeFile = nodeFilePath.toFile();

        logger.atDebug().log("Checking for file at path: {}", nodeFile.getPath());
        if (nodeFile.exists() && nodeFile.isFile()) {
            availableFiles.add(nodeFile);
            sourceNodes.add(nodeName);
            logger.atInfo().log("Found file from node {} in broker files: {}", nodeName, nodeFile.getPath());
        } else {
            logger.atWarn()
                    .log("Expected file from node {} not found in broker files at: {}", nodeName, nodeFile.getPath());
        }
    }

    private void collectTransferredFiles(
            String requestedFileName,
            Map<String, Object> workInfo,
            List<File> availableFiles,
            List<String> sourceNodes) {
        if (workInfo != null && workInfo.containsKey("transfers")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) workInfo.get("transfers");
            logger.atInfo().log("DEBUG: Found {} transfers in workInformation", transfers.size());

            for (Map<String, Object> transfer : transfers) {
                processWorkInfoTransfer(transfer, requestedFileName, availableFiles, sourceNodes);
            }
        }
    }

    private void processWorkInfoTransfer(
            Map<String, Object> transfer,
            String requestedFileName,
            List<File> availableFiles,
            List<String> sourceNodes) {
        String fileName = (String) transfer.get("fileName");
        String targetPath = (String) transfer.get("targetPath");
        String sourceNode = (String) transfer.get("sourceNode");

        logger.atDebug().log(
                "Processing transfer - fileName: {}, targetPath: {}, sourceNode: {}", fileName, targetPath, sourceNode);
        logger.atDebug().log(
                "Transfer blockchain metadata - fileHash: {}, fileMetadata present: {}",
                transfer.get("fileHash"),
                transfer.containsKey("fileMetadata"));

        if (!requestedFileName.equals(fileName) || targetPath == null) {
            return;
        }

        File transferredFile = new File(targetPath, fileName);
        logger.atDebug().log("Checking for transferred file at path: {}", transferredFile.getPath());
        if (!transferredFile.exists() || !transferredFile.isFile()) {
            logger.atWarn().log("Transferred file not found at expected path: {}", transferredFile.getPath());
            return;
        }

        availableFiles.add(transferredFile);
        sourceNodes.add(sourceNode != null ? sourceNode : "Unknown");
        logger.atInfo().log("Found transferred file from {} at: {}", sourceNode, transferredFile.getPath());
        preserveTransferMetadata(transfer, sourceNode);
    }

    private void preserveTransferMetadata(Map<String, Object> transfer, String sourceNode) {
        if (!nodeAllResult.containsKey(sourceNode)) {
            nodeAllResult.put(sourceNode, new HashMap<>());
        }
        Map<String, Object> nodeResult = nodeAllResult.get(sourceNode);

        String[] metadataKeys = {"fileMetadata", "fileHash", "fileId"};

        for (String key : metadataKeys) {
            if (transfer.containsKey(key)) {
                nodeResult.put(key, transfer.get(key));
                logger.atInfo().log(
                        "DEBUG: Preserved {} from transfer for node {}: {}", key, sourceNode, transfer.get(key));
            }
        }
        nodeResult.put("transferred", true);
        logger.atInfo().log("DEBUG: Updated nodeAllResult for {} with blockchain metadata", sourceNode);
    }

    private List<Map<String, Object>> performFileTransfers(
            List<File> availableFiles,
            List<String> sourceNodes,
            String requestedFileName,
            String baseReceiveTargetPath,
            Channel brokerChannel,
            Channel requestNodeChannel,
            Integer lowerBrokerPort,
            RequestId requestId) {
        List<Map<String, Object>> transferResults = new ArrayList<>();

        for (int i = 0; i < availableFiles.size(); i++) {
            File sourceFile = availableFiles.get(i);
            String sourceNode = sourceNodes.get(i);
            Path targetPath = PathUtil.buildPath(baseReceiveTargetPath, sourceNode);

            Map<String, Object> transferResult = executeFileTransfer(
                    sourceFile,
                    sourceNode,
                    targetPath.toString(),
                    requestedFileName,
                    brokerChannel,
                    requestNodeChannel,
                    lowerBrokerPort,
                    requestId);
            transferResults.add(transferResult);
        }

        return transferResults;
    }

    private Map<String, Object> executeFileTransfer(
            File sourceFile,
            String sourceNode,
            String targetPath,
            String requestedFileName,
            Channel brokerChannel,
            Channel requestNodeChannel,
            Integer lowerBrokerPort,
            RequestId requestId) {
        Map<String, Object> transferResult = new HashMap<>();
        transferResult.put("sourceNode", sourceNode);
        transferResult.put("fileName", requestedFileName);

        try {
            FileMetadata metadata = extractMetadata(sourceNode);

            performTransfer(sourceFile, targetPath, requestNodeChannel, lowerBrokerPort, metadata, requestId);

            transferResult.put("filePath", sourceFile.getPath());
            transferResult.put("targetPath", targetPath);
            transferResult.put("transferred", true);

            addBlockchainMetadataToResult(transferResult, sourceNode);
            logger.atInfo()
                    .addKeyValue("requestId", requestId)
                    .log(
                            "File {} from node {} successfully transferred to request node at {}",
                            requestedFileName,
                            sourceNode,
                            targetPath);

        } catch (Exception transferException) {
            transferResult.put("transferred", false);
            transferResult.put("error", "Transfer failed: " + transferException.getMessage());
            logger.atError().log("File transfer failed from node {}: {}", sourceNode, transferException);
        }

        return transferResult;
    }

    private FileMetadata extractMetadata(String sourceNode) {
        Map<String, Object> nodeResult = nodeAllResult.get(sourceNode);
        logger.atDebug().log("Looking for metadata from sourceNode: {}", sourceNode);
        logger.atDebug().log("nodeAllResult keys: {}", nodeAllResult.keySet());

        if (nodeResult == null) {
            logger.atWarn().log("No nodeResult found for sourceNode: {}", sourceNode);
            return null;
        }

        logger.atDebug().log("nodeResult contains: {}", nodeResult.keySet());
        FileMetadata metadata = (FileMetadata) nodeResult.get("fileMetadata");
        logger.atDebug().log("Found metadata={}", metadata != null);
        return metadata;
    }

    private void performTransfer(
            File sourceFile,
            String targetPath,
            Channel requestNodeChannel,
            Integer lowerBrokerPort,
            FileMetadata metadata,
            RequestId requestId)
            throws Exception {
        if (lowerBrokerPort != null) {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .log(
                            "Performing file transfer via broker channel on port {}: {} to {}",
                            lowerBrokerPort,
                            sourceFile.getPath(),
                            targetPath);

            FileTransferClient.doTransfer(
                    sourceFile.getPath(), targetPath, "localhost", null, lowerBrokerPort, metadata, requestId);
        } else if (requestNodeChannel != null) {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .log("Performing direct file transfer to request node: {} to {}", sourceFile.getPath(), targetPath);

            FileTransferClient.doTransfer(
                    sourceFile.getPath(), targetPath, null, requestNodeChannel, 8088, metadata, requestId);
        } else {
            throw new RuntimeException("No valid channel or port for file transfer");
        }
    }

    private void addBlockchainMetadataToResult(Map<String, Object> transferResult, String sourceNode) {
        Map<String, Object> nodeResult = nodeAllResult.get(sourceNode);
        if (nodeResult != null) {
            String[] metadataKeys = {"fileMetadata", "fileHash", "fileId"};

            for (String key : metadataKeys) {
                if (nodeResult.containsKey(key)) {
                    transferResult.put(key, nodeResult.get(key));
                }
            }
        }
    }

    private void populateTransferResults(
            Map<String, Object> result,
            List<Map<String, Object>> transferResults,
            List<File> availableFiles,
            String requestedFileName,
            List<String> sourceNodes) {
        result.put("transfers", transferResults);
        result.put("totalFiles", availableFiles.size());
        result.put("fileName", requestedFileName);
        result.put("transferred", true);

        if (!transferResults.isEmpty()) {
            addPrimaryMetadata(result, transferResults);
            addMetadataStatistics(result, transferResults);
        }

        logger.atInfo().log(
                "Completed file transfers: {} files from {} nodes", availableFiles.size(), sourceNodes.size());
    }

    private void addPrimaryMetadata(Map<String, Object> result, List<Map<String, Object>> transferResults) {
        Map<String, Object> firstSuccessfulTransfer = transferResults.getFirst();
        if (firstSuccessfulTransfer.containsKey("fileHash")) {
            result.put("primaryFileHash", firstSuccessfulTransfer.get("fileHash"));
        }
        if (firstSuccessfulTransfer.containsKey("fileId")) {
            result.put("primaryFileId", firstSuccessfulTransfer.get("fileId"));
        }
    }

    private void addMetadataStatistics(Map<String, Object> result, List<Map<String, Object>> transferResults) {
        long metadataConsistentCount = transferResults.stream()
                .mapToLong(tr -> tr.containsKey("blockchainTxHash") ? 1 : 0) // TODO: 移除blockchainTxHash
                .sum();
        result.put("metadataConsistentCount", metadataConsistentCount);
        result.put("totalTransferCount", transferResults.size());
        result.put("metadataAvailable", metadataConsistentCount > 0);
    }
}
