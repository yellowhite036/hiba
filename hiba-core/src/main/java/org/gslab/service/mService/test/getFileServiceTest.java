package org.gslab.service.mService.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gslab.model.TaskRequest;
import org.gslab.service.mService.distributor.getFileDistributor;
import org.gslab.service.mService.executor.getFileExecutor;
import org.gslab.service.mService.taker.getFileTaker;

/**
 * 簡單的 getFile 微服務測試類別 用於驗證 getFile 服務的基本功能
 */
public class getFileServiceTest {

    public static void main(String[] args) {
        System.out.println("=== getFile 微服務基本功能測試 ===\n");
        // 測試 1: getFileTaker
        testGetFileTaker();

        // 測試 2: getFileDistributor
        testGetFileDistributor();

        // 測試 3: getFileExecutor
        testGetFileExecutor();

        System.out.println("=== 測試完成 ===");
    }

    private static void testGetFileTaker() {
        System.out.println("1. 測試 getFileTaker");
        try {
            getFileTaker taker = new getFileTaker();

            // 模擬資源清單
            Map<String, Object> resourceInThisTier = new HashMap<>();
            List<String> availableFiles = new ArrayList<>();
            availableFiles.add("/home/user/file1.txt");
            availableFiles.add("~/file2.txt");
            resourceInThisTier.put("getFile", availableFiles);

            // 模擬請求資訊
            Map<String, Object> requestInfo = new HashMap<>();
            List<String> requestedFiles = new ArrayList<>();
            requestedFiles.add("file1.txt");
            requestInfo.put("files", requestedFiles);
            requestInfo.put("receiveTargetPath", "./test_receive/");

            taker.setRequestInformation(requestInfo);

            boolean canHandle = taker.checkCanHandleRequest(false, resourceInThisTier);
            System.out.println("   - 能處理 file1.txt 請求: " + canHandle);

            // 測試不存在的檔案
            requestedFiles.clear();
            requestedFiles.add("nonexistent.txt");
            requestInfo.put("files", requestedFiles);
            taker.setRequestInformation(requestInfo);

            boolean canHandle2 = taker.checkCanHandleRequest(false, resourceInThisTier);
            System.out.println("   - 能處理 nonexistent.txt 請求: " + canHandle2);

        } catch (Exception e) {
            System.out.println("   - 錯誤: " + e.getMessage());
        }
        System.out.println();
    }

    private static void testGetFileDistributor() {
        System.out.println("2. 測試 getFileDistributor");
        try {
            getFileDistributor distributor = new getFileDistributor();

            // 建立 TaskRequest
            TaskRequest taskRequest = new TaskRequest();
            taskRequest.setRequestName("test-request");
            taskRequest.setServiceName("getFile");
            taskRequest.setNodeName("client-node");

            List<String> files = new ArrayList<>();
            files.add("file1.txt");
            taskRequest.setFiles(files);
            taskRequest.setReceiveTargetPath("./test_receive/");

            distributor.setTaskRequest(taskRequest);

            // 模擬節點資源
            Map<String, Object> nodeResource = new HashMap<>();
            List<String> nodeFiles = new ArrayList<>();
            nodeFiles.add("/home/user/file1.txt");
            nodeFiles.add("~/file2.txt");
            nodeResource.put("getFile", nodeFiles);

            // 測試任務分配
            var assignment = distributor.mappingTask("test-node", nodeResource, null, new HashMap<>());

            if (assignment != null) {
                System.out.println("   - 成功分配任務到節點: " + assignment.getNodeName());
                System.out.println("   - 任務檔案: " + assignment.getFiles());
            } else {
                System.out.println("   - 無法分配任務");
            }

        } catch (Exception e) {
            System.out.println("   - 錯誤: " + e.getMessage());
        }
        System.out.println();
    }

    private static void testGetFileExecutor() {
        System.out.println("3. 測試 getFileExecutor");
        try {
            @SuppressWarnings("unused")
            getFileExecutor executor = new getFileExecutor();

            // 模擬執行資訊
            Map<String, Object> information = new HashMap<>();
            information.put("fileName", "file1.txt");
            information.put("receiveTargetPath", "./test_receive/");

            // 由於 getFileExecutor 需要實際的檔案和 Channel，這裡只測試基本初始化
            System.out.println("   - getFileExecutor 初始化成功");
            System.out.println("   - 注意: 完整測試需要實際檔案和網路通道");

        } catch (Exception e) {
            System.out.println("   - 錯誤: " + e.getMessage());
        }
        System.out.println();
    }
}
