package org.gslab.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 任務執行結果的資料結構 替代原本使用 Map<String, Object> 傳遞任務結果的方式
 */
public class TaskResult implements Serializable {

    private static final long serialVersionUID = 1L;

    // 基本資訊
    private String requestName;
    private RequestId requestId;
    private String serviceName;
    private String nodeName;
    private boolean state;
    private boolean success;

    // 時間資訊
    private long startTime;
    private long endTime;
    private long spendTime;
    private long requestTimeStamp;

    // Broker 路由資訊
    private java.util.List<String> brokerRoute;

    // 結果資料
    private Map<String, Object> result;
    private Map<String, Object> allResult;
    private Map<String, Object> taskDetails;

    // 錯誤資訊
    private String errorMessage;
    private java.util.List<String> lostFiles;

    // 檔案相關
    private java.util.List<String> resultFiles;
    private String resultPath;

    public TaskResult() {
        this.brokerRoute = new java.util.ArrayList<>();
        this.lostFiles = new java.util.ArrayList<>();
        this.resultFiles = new java.util.ArrayList<>();
        this.result = new HashMap<>();
        this.allResult = new HashMap<>();
        this.taskDetails = new HashMap<>();
        this.state = true;
        this.success = true;
        this.endTime = System.currentTimeMillis();
    }

    public TaskResult(String requestName, String serviceName, String nodeName) {
        this();
        this.requestName = requestName;
        this.serviceName = serviceName;
        this.nodeName = nodeName;
    }

    public TaskResult(String requestName, RequestId requestId, String serviceName, String nodeName) {
        this();
        this.requestName = requestName;
        this.requestId = requestId;
        this.serviceName = serviceName;
        this.nodeName = nodeName;
    }

    // Getters and Setters
    public String getRequestName() {
        return requestName;
    }

    public void setRequestName(String requestName) {
        this.requestName = requestName;
    }

    public RequestId getRequestId() {
        return requestId;
    }

    public void setRequestId(RequestId requestId) {
        this.requestId = requestId;
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

    public boolean isState() {
        return state;
    }

    public void setState(boolean state) {
        this.state = state;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
        this.state = success; // state 通常與 success 一致
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
        if (startTime > 0) {
            this.spendTime = endTime - startTime;
        }
    }

    public long getSpendTime() {
        return spendTime;
    }

    public void setSpendTime(long spendTime) {
        this.spendTime = spendTime;
    }

    public long getRequestTimeStamp() {
        return requestTimeStamp;
    }

    public void setRequestTimeStamp(long requestTimeStamp) {
        this.requestTimeStamp = requestTimeStamp;
    }

    public java.util.List<String> getBrokerRoute() {
        return brokerRoute;
    }

    public void setBrokerRoute(java.util.List<String> brokerRoute) {
        this.brokerRoute = brokerRoute != null ? brokerRoute : new java.util.ArrayList<>();
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
        this.result = result != null ? result : new HashMap<>();
    }

    public Map<String, Object> getAllResult() {
        return allResult;
    }

    public void setAllResult(Map<String, Object> allResult) {
        this.allResult = allResult != null ? allResult : new HashMap<>();
    }

    public Map<String, Object> getTaskDetails() {
        return taskDetails;
    }

    public void setTaskDetails(Map<String, Object> taskDetails) {
        this.taskDetails = taskDetails != null ? taskDetails : new HashMap<>();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        if (errorMessage != null && !errorMessage.isEmpty()) {
            this.success = false;
            this.state = false;
        }
    }

    public java.util.List<String> getLostFiles() {
        return lostFiles;
    }

    public void setLostFiles(java.util.List<String> lostFiles) {
        this.lostFiles = lostFiles != null ? lostFiles : new java.util.ArrayList<>();
    }

    public void addLostFile(String fileName) {
        if (this.lostFiles == null) {
            this.lostFiles = new java.util.ArrayList<>();
        }
        this.lostFiles.add(fileName);
    }

    public java.util.List<String> getResultFiles() {
        return resultFiles;
    }

    public void setResultFiles(java.util.List<String> resultFiles) {
        this.resultFiles = resultFiles != null ? resultFiles : new java.util.ArrayList<>();
    }

    public void addResultFile(String filePath) {
        if (this.resultFiles == null) {
            this.resultFiles = new java.util.ArrayList<>();
        }
        this.resultFiles.add(filePath);
    }

    public String getResultPath() {
        return resultPath;
    }

    public void setResultPath(String resultPath) {
        this.resultPath = resultPath;
    }

    /**
     * 添加結果資料
     */
    public void addResultData(String key, Object value) {
        if (this.result == null) {
            this.result = new HashMap<>();
        }
        this.result.put(key, value);
    }

    /**
     * 計算執行時間
     */
    public void calculateSpendTime() {
        if (startTime > 0 && endTime > 0) {
            this.spendTime = endTime - startTime;
        }
    }

    /**
     * 檢查是否有錯誤
     */
    public boolean hasError() {
        return !success || (errorMessage != null && !errorMessage.isEmpty());
    }

    /**
     * 檢查是否有遺失檔案
     */
    public boolean hasLostFiles() {
        return lostFiles != null && !lostFiles.isEmpty();
    }

    /**
     * 轉換為 Map 格式（向後兼容）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        if (requestName != null) {
            map.put("requestName", requestName);
        }
        if (requestId != null) {
            map.put("requestId", requestId);
        }
        if (serviceName != null) {
            map.put("serviceName", serviceName);
        }
        if (nodeName != null) {
            map.put("nodeName", nodeName);
        }
        map.put("state", state);
        map.put("success", success);

        if (startTime > 0) {
            map.put("startTime", startTime);
        }
        if (endTime > 0) {
            map.put("endTime", endTime);
        }
        if (spendTime > 0) {
            map.put("spendTime", spendTime);
        }
        if (requestTimeStamp > 0) {
            map.put("requestTimeStamp", requestTimeStamp);
        }

        if (brokerRoute != null && !brokerRoute.isEmpty()) {
            map.put("brokerRoute", new java.util.ArrayList<>(brokerRoute));
        }

        if (result != null && !result.isEmpty()) {
            map.put("result", new HashMap<>(result));
        }
        if (allResult != null && !allResult.isEmpty()) {
            map.put("allResult", new HashMap<>(allResult));
        }
        if (taskDetails != null && !taskDetails.isEmpty()) {
            map.put("taskDetails", new HashMap<>(taskDetails));
        }

        if (errorMessage != null) {
            map.put("errorMessage", errorMessage);
        }
        if (lostFiles != null && !lostFiles.isEmpty()) {
            map.put("lostFiles", new java.util.ArrayList<>(lostFiles));
        }
        if (resultFiles != null && !resultFiles.isEmpty()) {
            map.put("resultFiles", new java.util.ArrayList<>(resultFiles));
        }
        if (resultPath != null) {
            map.put("resultPath", resultPath);
        }

        return map;
    }

    /**
     * 從 Map 創建 TaskResult（向後兼容）
     */
    @SuppressWarnings("unchecked")
    public static TaskResult fromMap(Map<String, Object> map) {
        TaskResult result = new TaskResult();

        result.setRequestName((String) map.get("requestName"));
        String requestIdStr = (String) map.get("requestId");
        if (requestIdStr != null) {
            result.setRequestId(RequestId.of(requestIdStr));
        }
        result.setServiceName((String) map.get("serviceName"));
        result.setNodeName((String) map.get("nodeName"));

        if (map.containsKey("state")) {
            result.setState((Boolean) map.get("state"));
        }
        if (map.containsKey("success")) {
            result.setSuccess((Boolean) map.get("success"));
        }

        if (map.containsKey("startTime")) {
            result.setStartTime((Long) map.get("startTime"));
        }
        if (map.containsKey("endTime")) {
            result.setEndTime((Long) map.get("endTime"));
        }
        if (map.containsKey("spendTime")) {
            result.setSpendTime((Long) map.get("spendTime"));
        }
        if (map.containsKey("requestTimeStamp")) {
            result.setRequestTimeStamp((Long) map.get("requestTimeStamp"));
        }

        if (map.containsKey("brokerRoute")) {
            result.setBrokerRoute((java.util.List<String>) map.get("brokerRoute"));
        }

        if (map.containsKey("result")) {
            result.setResult((Map<String, Object>) map.get("result"));
        }
        if (map.containsKey("allResult")) {
            result.setAllResult((Map<String, Object>) map.get("allResult"));
        }
        if (map.containsKey("taskDetails")) {
            result.setTaskDetails((Map<String, Object>) map.get("taskDetails"));
        }

        result.setErrorMessage((String) map.get("errorMessage"));
        if (map.containsKey("lostFiles")) {
            result.setLostFiles((java.util.List<String>) map.get("lostFiles"));
        }
        if (map.containsKey("resultFiles")) {
            result.setResultFiles((java.util.List<String>) map.get("resultFiles"));
        }
        result.setResultPath((String) map.get("resultPath"));

        return result;
    }

    @Override
    public String toString() {
        return String.format(
                "TaskResult{requestName='%s', requestId='%s', serviceName='%s', nodeName='%s', success=%s, spendTime=%d ms}",
                requestName, requestId, serviceName, nodeName, success, spendTime);
    }
}
