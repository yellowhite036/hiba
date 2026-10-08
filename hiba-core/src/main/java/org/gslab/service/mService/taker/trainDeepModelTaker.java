package org.gslab.service.mService.taker;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.gslab.Taker;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class trainDeepModelTaker extends Taker {

    private static final Logger logger = LoggerFactory.getLogger(trainDeepModelTaker.class);

    @Override
    @SuppressWarnings("unchecked")
    public boolean checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier) {
        Map<String, Object> trainTask = new HashMap<>();
        boolean response = false;

        // broker管理的節點
        ArrayList<ArrayList<Float>> nodeComputility =
                (ArrayList<ArrayList<Float>>) resourceInThisTier.get("computility");

        // 樹狀分支
        Map<String, Object> tree = (Map<String, Object>) getRequestInformation().get("tree");
        ArrayList<Map<String, Object>> allChildrenTree = (ArrayList<Map<String, Object>>) tree.get("children");

        // 接收檔案的位置
        String receiveTargetPath = (String) getRequestInformation().get("receiveTargetPath"); // receiveFile path
        String sampleDirectory = ((String) getRequestInformation().get("sampleDirectory")).replaceAll("\\.", "");

        allChildrenTree.forEach(childTree -> {
            ArrayList<String> content = (ArrayList<String>) childTree.get("children");
            if (!content.isEmpty()) {
                Map<String, Object> childCategory = new HashMap<>();
                ArrayList<String> value = (ArrayList<String>) childTree.get("value");
                String name = (String) childTree.get("name");
                childCategory.put("name", name);
                childCategory.put("children", content);
                childCategory.put("value", value);
                String taskFilePath = receiveTargetPath + "/" + sampleDirectory + "/" + name;
                File taskFileDir = new File(taskFilePath);
                if (!taskFileDir.exists()) {
                    taskFileDir.mkdirs();
                }
                createJson(childCategory, taskFilePath + "/" + name + ".json");
                trainTask.put(name, taskFilePath);
            }
        });
        trainTask.put("all", sampleDirectory);

        // 用computility(節點分數) 來判斷節點數量
        if (trainTask.size() <= nodeComputility.size()) {
            logger.atInfo().log("Broker can handle this task, return true.");
            response = true;
        } else {
            if (hasBroker) {
                logger.atInfo().log("Insufficient node at this tier, forward to upper tier.");
            } else {
                logger.atInfo().log("Broker can't handle, return false.");
            }
            response = false;
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getModelCategory(Map<String, Object> branchNode) { // 樹狀分類器
        Map<String, Object> childCategory = new HashMap<>();
        ArrayList<Map<String, Object>> allTreeCategory = (ArrayList<Map<String, Object>>) branchNode.get("children");
        String modelName = (String) branchNode.get("name");
        allTreeCategory.forEach(childTree -> {
            String name = modelName + childTree.get("name");
            childCategory.put(name, childTree.get("value"));
        });
        return childCategory;
    }

    public static void createJson(Map<String, Object> info, String fileName) {
        JSONObject json = new JSONObject();
        FileWriter content;
        try {
            for (String context : info.keySet()) {
                json.put(context, info.get(context));
                json.put(context, info.get(context));
            }
            content = new FileWriter(fileName);
            BufferedWriter bw = new BufferedWriter(content);
            bw.write(json.toString());
            bw.flush();
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
