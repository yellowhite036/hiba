package org.gslab.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任務請求的資料結構 替代原本使用 Map<String, Object> 傳遞請求資訊的方式
 */
public class TaskRequest {

    // 基本資訊
    private RequestId requestId; // UUID for distributed tracing
    private String requestName;
    private String serviceName;
    private String nodeName;
    private int tier;
    private long requestTimeStamp;

    // Broker 路由資訊
    private List<String> brokerRoute; // 檔案傳輸相關
    private boolean hasTransferFile;
    private List<String> transferFileList;
    private String receiveTargetPath;
    private String sampleDirectory;
    private String fileName; // 用於 getFile 服務的檔案名稱

    // 任務特定資訊
    private Map<String, Object> taskSpecificData;

    // 檔案收集相關
    private List<String> files;

    // HTTP 檔案上傳專用欄位
    private List<Object> uploadedFiles; // List<FileUploadInfo> from HTTP multipart upload
    private String requestDate; // Request date string

    // 資源需求
    private List<String> roadType;
    private List<List<String>> recognitionCategory;
    private Map<String, Object> tree;

    // 計算資源相關
    private Long quantity;

    public TaskRequest() {
        this.brokerRoute = new ArrayList<>();
        this.transferFileList = new ArrayList<>();
        this.files = new ArrayList<>();
        this.roadType = new ArrayList<>();
        this.recognitionCategory = new ArrayList<>();
        this.requestTimeStamp = System.currentTimeMillis();
    }

    public TaskRequest(String requestName, String serviceName) {
        this();
        this.requestName = requestName;
        this.serviceName = serviceName;
    }

    // Getters and Setters
    public RequestId getRequestId() {
        return requestId;
    }

    public void setRequestId(RequestId requestId) {
        this.requestId = requestId;
    }

    public String getRequestName() {
        return requestName;
    }

    public void setRequestName(String requestName) {
        this.requestName = requestName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public int getTier() {
        return tier;
    }

    public void setTier(int tier) {
        this.tier = tier;
    }

    public long getRequestTimeStamp() {
        return requestTimeStamp;
    }

    public void setRequestTimeStamp(long requestTimeStamp) {
        this.requestTimeStamp = requestTimeStamp;
    }

    public List<String> getBrokerRoute() {
        return brokerRoute;
    }

    public void setBrokerRoute(List<String> brokerRoute) {
        this.brokerRoute = brokerRoute != null ? brokerRoute : new ArrayList<>();
    }

    public void addBrokerToRoute(String brokerName) {
        if (this.brokerRoute == null) {
            this.brokerRoute = new ArrayList<>();
        }
        this.brokerRoute.add(brokerName);
    }

    public boolean hasTransferFile() {
        return hasTransferFile;
    }

    public void setHasTransferFile(boolean hasTransferFile) {
        this.hasTransferFile = hasTransferFile;
    }

    public List<String> getTransferFileList() {
        return transferFileList;
    }

    public void setTransferFileList(List<String> transferFileList) {
        this.transferFileList = transferFileList != null ? transferFileList : new ArrayList<>();
        this.hasTransferFile = !this.transferFileList.isEmpty();
    }

    public String getReceiveTargetPath() {
        return receiveTargetPath;
    }

    public void setReceiveTargetPath(String receiveTargetPath) {
        this.receiveTargetPath = receiveTargetPath;
    }

    public String getSampleDirectory() {
        return sampleDirectory;
    }

    public void setSampleDirectory(String sampleDirectory) {
        this.sampleDirectory = sampleDirectory;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Map<String, Object> getTaskSpecificData() {
        return taskSpecificData;
    }

    public void setTaskSpecificData(Map<String, Object> taskSpecificData) {
        this.taskSpecificData = taskSpecificData;
    }

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(List<String> files) {
        this.files = files != null ? files : new ArrayList<>();
    }

    public List<String> getRoadType() {
        return roadType;
    }

    public void setRoadType(List<String> roadType) {
        this.roadType = roadType != null ? roadType : new ArrayList<>();
    }

    public List<List<String>> getRecognitionCategory() {
        return recognitionCategory;
    }

    public void setRecognitionCategory(List<List<String>> recognitionCategory) {
        this.recognitionCategory = recognitionCategory != null ? recognitionCategory : new ArrayList<>();
    }

    public Map<String, Object> getTree() {
        return tree;
    }

    public void setTree(Map<String, Object> tree) {
        this.tree = tree;
    }

    public Long getQuantity() {
        return quantity;
    }

    public void setQuantity(Long quantity) {
        this.quantity = quantity;
    }

    public List<Object> getUploadedFiles() {
        return uploadedFiles;
    }

    public void setUploadedFiles(List<Object> uploadedFiles) {
        this.uploadedFiles = uploadedFiles;
    }

    public String getRequestDate() {
        return requestDate;
    }

    public void setRequestDate(String requestDate) {
        this.requestDate = requestDate;
    }

    /**
     * 檢查是否有 broker 路由
     */
    public boolean hasBrokerRoute() {
        return brokerRoute != null && !brokerRoute.isEmpty();
    }

    /**
     * 獲取當前 broker 路由（最後一個）
     */
    public String getCurrentBrokerRoute() {
        if (brokerRoute != null && !brokerRoute.isEmpty()) {
            return brokerRoute.getLast();
        }
        return null;
    }

    /**
     * 轉換為 Map 格式（向後兼容）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.HashMap<>();

        if (requestId != null) {
            map.put("requestId", requestId);
        }
        if (requestName != null) {
            map.put("requestName", requestName);
        }
        if (serviceName != null) {
            map.put("serviceName", serviceName);
        }
        if (nodeName != null) {
            map.put("nodeName", nodeName);
        }
        map.put("tier", tier);
        map.put("requestTimeStamp", requestTimeStamp);

        if (brokerRoute != null && !brokerRoute.isEmpty()) {
            map.put("brokerRoute", new ArrayList<>(brokerRoute));
        }

        if (hasTransferFile) {
            map.put("transferFile", new ArrayList<>(transferFileList));
        }
        if (receiveTargetPath != null) {
            map.put("receiveTargetPath", receiveTargetPath);
        }
        if (sampleDirectory != null) {
            map.put("sampleDirectory", sampleDirectory);
        }
        if (fileName != null) {
            map.put("fileName", fileName);
        }

        if (roadType != null && !roadType.isEmpty()) {
            map.put("roadType", new ArrayList<>(roadType));
        }
        if (recognitionCategory != null && !recognitionCategory.isEmpty()) {
            map.put("recognitionCategory", new ArrayList<>(recognitionCategory));
        }
        if (tree != null) {
            map.put("tree", tree);
        }
        if (quantity != null) {
            map.put("quantity", quantity);
        }

        // HTTP 檔案上傳專用欄位
        if (uploadedFiles != null && !uploadedFiles.isEmpty()) {
            map.put("uploadedFiles", uploadedFiles);
        }
        if (requestDate != null) {
            map.put("requestDate", requestDate);
        }

        if (taskSpecificData != null) {
            map.putAll(taskSpecificData);
        }

        return map;
    }

    /**
     * 從 Map 創建 TaskRequest（向後兼容）
     */
    @SuppressWarnings("unchecked")
    public static TaskRequest fromMap(Map<String, Object> map) {
        TaskRequest request = new TaskRequest();

        request.setRequestId((RequestId) map.get("requestId"));
        request.setRequestName((String) map.get("requestName"));
        request.setServiceName((String) map.get("serviceName"));
        request.setNodeName((String) map.get("nodeName"));

        if (map.containsKey("tier")) {
            request.setTier((Integer) map.get("tier"));
        }
        if (map.containsKey("requestTimeStamp")) {
            request.setRequestTimeStamp((Long) map.get("requestTimeStamp"));
        }

        if (map.containsKey("brokerRoute")) {
            request.setBrokerRoute((List<String>) map.get("brokerRoute"));
        }

        if (map.containsKey("transferFile")) {
            request.setTransferFileList((List<String>) map.get("transferFile"));
        }
        request.setReceiveTargetPath((String) map.get("receiveTargetPath"));
        request.setSampleDirectory((String) map.get("sampleDirectory"));
        request.setFileName((String) map.get("fileName"));

        if (map.containsKey("roadType")) {
            request.setRoadType((List<String>) map.get("roadType"));
        }
        if (map.containsKey("recognitionCategory")) {
            request.setRecognitionCategory((List<List<String>>) map.get("recognitionCategory"));
        }
        if (map.containsKey("tree")) {
            request.setTree((Map<String, Object>) map.get("tree"));
        }
        if (map.containsKey("quantity")) {
            request.setQuantity((Long) map.get("quantity"));
        }

        // HTTP 檔案上傳專用欄位
        if (map.containsKey("uploadedFiles")) {
            request.setUploadedFiles((List<Object>) map.get("uploadedFiles"));
        }
        if (map.containsKey("requestDate")) {
            request.setRequestDate((String) map.get("requestDate"));
        }

        // Collect unknown fields into taskSpecificData to preserve dynamic parameters
        // Define known field names that should NOT be added to taskSpecificData
        java.util.Set<String> knownFields = new java.util.HashSet<>(java.util.Arrays.asList(
                "requestId",
                "requestName",
                "serviceName",
                "nodeName",
                "tier",
                "requestTimeStamp",
                "brokerRoute",
                "transferFile",
                "receiveTargetPath",
                "sampleDirectory",
                "fileName",
                "roadType",
                "recognitionCategory",
                "tree",
                "quantity",
                "uploadedFiles",
                "requestDate"));

        Map<String, Object> taskData = new java.util.HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (!knownFields.contains(key)) {
                taskData.put(key, entry.getValue());
            }
        }

        if (!taskData.isEmpty()) {
            request.setTaskSpecificData(taskData);
        }

        return request;
    }

    @Override
    public String toString() {
        return String.format(
                "TaskRequest{requestName='%s', serviceName='%s', tier=%d, brokerRoute=%s}",
                requestName, serviceName, tier, brokerRoute);
    }
}
