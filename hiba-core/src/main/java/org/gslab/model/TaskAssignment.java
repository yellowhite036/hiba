package org.gslab.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 任務分配的資料結構 替代原本使用 Map<String, Object> 傳遞任務分配資訊的方式
 */
public class TaskAssignment {

    // 基本資訊
    private RequestId requestId; // UUID for distributed tracing
    private String requestName;
    private String serviceName;
    private String nodeName;
    private int tier;

    // Broker 路由資訊
    private java.util.List<String> brokerRoute;

    // 分配資訊
    private Long quantity;
    private boolean distributeFinish; // 檔案傳輸相關
    private boolean hasTransferFile;
    private java.util.List<String> transferFileList;
    private String receiveTargetPath;
    private String sampleDirectory;
    private String fileName; // 新增 fileName 欄位

    // 檔案收集相關（用於 CollectFileDistributor）
    private java.util.List<String> files;
    private String targetPath;

    // 任務特定資料
    private java.util.List<String> roadType;
    private java.util.List<java.util.List<String>> recognitionCategory;
    private Map<String, Object> tree;
    private Map<String, Object> taskSpecificData;

    public TaskAssignment() {
        this.brokerRoute = new java.util.ArrayList<>();
        this.transferFileList = new java.util.ArrayList<>();
        this.files = new java.util.ArrayList<>();
        this.roadType = new java.util.ArrayList<>();
        this.recognitionCategory = new java.util.ArrayList<>();
        this.taskSpecificData = new HashMap<>();
    }

    public TaskAssignment(String requestName, String serviceName, String nodeName) {
        this();
        this.requestName = requestName;
        this.serviceName = serviceName;
        this.nodeName = nodeName;
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

    public java.util.List<String> getBrokerRoute() {
        return brokerRoute;
    }

    public void setBrokerRoute(java.util.List<String> brokerRoute) {
        this.brokerRoute = brokerRoute != null ? brokerRoute : new java.util.ArrayList<>();
    }

    public Long getQuantity() {
        return quantity;
    }

    public void setQuantity(Long quantity) {
        this.quantity = quantity;
    }

    public boolean isDistributeFinish() {
        return distributeFinish;
    }

    public void setDistributeFinish(boolean distributeFinish) {
        this.distributeFinish = distributeFinish;
    }

    public boolean hasTransferFile() {
        return hasTransferFile;
    }

    public void setHasTransferFile(boolean hasTransferFile) {
        this.hasTransferFile = hasTransferFile;
    }

    public java.util.List<String> getTransferFileList() {
        return transferFileList;
    }

    public void setTransferFileList(java.util.List<String> transferFileList) {
        this.transferFileList = transferFileList != null ? transferFileList : new java.util.ArrayList<>();
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

    public java.util.List<String> getFiles() {
        return files;
    }

    public void setFiles(java.util.List<String> files) {
        this.files = files != null ? files : new java.util.ArrayList<>();
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public java.util.List<String> getRoadType() {
        return roadType;
    }

    public void setRoadType(java.util.List<String> roadType) {
        this.roadType = roadType != null ? roadType : new java.util.ArrayList<>();
    }

    public java.util.List<java.util.List<String>> getRecognitionCategory() {
        return recognitionCategory;
    }

    public void setRecognitionCategory(java.util.List<java.util.List<String>> recognitionCategory) {
        this.recognitionCategory = recognitionCategory != null ? recognitionCategory : new java.util.ArrayList<>();
    }

    public Map<String, Object> getTree() {
        return tree;
    }

    public void setTree(Map<String, Object> tree) {
        this.tree = tree;
    }

    public Map<String, Object> getTaskSpecificData() {
        return taskSpecificData;
    }

    public void setTaskSpecificData(Map<String, Object> taskSpecificData) {
        this.taskSpecificData = taskSpecificData != null ? taskSpecificData : new HashMap<>();
    }

    /**
     * 添加任務特定資料
     */
    public void addTaskData(String key, Object value) {
        if (this.taskSpecificData == null) {
            this.taskSpecificData = new HashMap<>();
        }
        this.taskSpecificData.put(key, value);
    }

    /**
     * 檢查是否為完成分配
     */
    public boolean isComplete() {
        return distributeFinish;
    }

    /**
     * 轉換為 Map 格式（向後兼容）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

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

        if (brokerRoute != null && !brokerRoute.isEmpty()) {
            map.put("brokerRoute", new java.util.ArrayList<>(brokerRoute));
        }

        if (quantity != null) {
            map.put("quantity", quantity);
        }
        if (distributeFinish) {
            map.put("distributeFinish", distributeFinish);
        }

        if (hasTransferFile) {
            map.put("transferFile", new java.util.ArrayList<>(transferFileList));
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
            map.put("roadType", new java.util.ArrayList<>(roadType));
        }
        if (recognitionCategory != null && !recognitionCategory.isEmpty()) {
            map.put("recognitionCategory", new java.util.ArrayList<>(recognitionCategory));
        }
        if (tree != null) {
            map.put("tree", tree);
        }

        if (taskSpecificData != null) {
            map.putAll(taskSpecificData);
        }

        return map;
    }

    /**
     * 從 Map 創建 TaskAssignment（向後兼容）
     */
    @SuppressWarnings("unchecked")
    public static TaskAssignment fromMap(Map<String, Object> map) {
        TaskAssignment assignment = new TaskAssignment();

        assignment.setRequestId((RequestId) map.get("requestId"));
        assignment.setRequestName((String) map.get("requestName"));
        assignment.setServiceName((String) map.get("serviceName"));
        assignment.setNodeName((String) map.get("nodeName"));

        if (map.containsKey("tier")) {
            assignment.setTier((Integer) map.get("tier"));
        }

        if (map.containsKey("brokerRoute")) {
            assignment.setBrokerRoute((java.util.List<String>) map.get("brokerRoute"));
        }

        if (map.containsKey("quantity")) {
            assignment.setQuantity((Long) map.get("quantity"));
        }
        if (map.containsKey("distributeFinish")) {
            assignment.setDistributeFinish((Boolean) map.get("distributeFinish"));
        }

        if (map.containsKey("transferFile")) {
            assignment.setTransferFileList((java.util.List<String>) map.get("transferFile"));
        }
        assignment.setReceiveTargetPath((String) map.get("receiveTargetPath"));
        assignment.setSampleDirectory((String) map.get("sampleDirectory"));
        assignment.setFileName((String) map.get("fileName"));

        if (map.containsKey("roadType")) {
            assignment.setRoadType((java.util.List<String>) map.get("roadType"));
        }
        if (map.containsKey("recognitionCategory")) {
            assignment.setRecognitionCategory((java.util.List<java.util.List<String>>) map.get("recognitionCategory"));
        }
        if (map.containsKey("tree")) {
            assignment.setTree((Map<String, Object>) map.get("tree"));
        }

        return assignment;
    }

    @Override
    public String toString() {
        return String.format(
                "TaskAssignment{requestName='%s', serviceName='%s', nodeName='%s', tier=%d, quantity=%d}",
                requestName, serviceName, nodeName, tier, quantity);
    }
}
