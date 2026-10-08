package org.gslab.task;

import java.util.Map;
import org.gslab.model.TaskAssignment;
import org.gslab.model.TaskRequest;
import org.gslab.model.TaskResult;

/**
 * 新舊資料結構轉換的輔助類別
 */
public class TaskDataAdapter {

    /**
     * 將 Map 格式的請求轉換為 TaskRequest 物件
     */
    public static TaskRequest mapToTaskRequest(Map<String, Object> requestMap) {
        return TaskRequest.fromMap(requestMap);
    }

    /**
     * 將 TaskRequest 物件轉換為 Map 格式（向後兼容）
     */
    public static Map<String, Object> taskRequestToMap(TaskRequest request) {
        return request.toMap();
    }

    /**
     * 將 Map 格式的任務分配轉換為 TaskAssignment 物件
     */
    public static TaskAssignment mapToTaskAssignment(Map<String, Object> taskMap) {
        return TaskAssignment.fromMap(taskMap);
    }

    /**
     * 將 TaskAssignment 物件轉換為 Map 格式（向後兼容）
     */
    public static Map<String, Object> taskAssignmentToMap(TaskAssignment assignment) {
        return assignment.toMap();
    }

    /**
     * 將 Map 格式的結果轉換為 TaskResult 物件
     * TODO: 在下一階段重構中使用，當我們重構 taskResultList 和相關邏輯時
     */
    @SuppressWarnings("unused")
    public static TaskResult mapToTaskResult(Map<String, Object> resultMap) {
        return TaskResult.fromMap(resultMap);
    }

    /**
     * 將 TaskResult 物件轉換為 Map 格式（向後兼容）
     */
    public static Map<String, Object> taskResultToMap(TaskResult result) {
        return result.toMap();
    }
}
