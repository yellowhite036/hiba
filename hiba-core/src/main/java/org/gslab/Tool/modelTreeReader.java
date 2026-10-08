package org.gslab.Tool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONTokener;

public class modelTreeReader {

    @SuppressWarnings("unchecked")
    public static ArrayList<String> getBranchNodeNeedSamples(Map<String, Object> branchNode) {
        ArrayList<String> allNeedSamples = new ArrayList<>();
        ArrayList<Map<String, Object>> AllNextTierNodes = (ArrayList<Map<String, Object>>) branchNode.get("children");
        System.out.println("AllNextTierNodes: " + AllNextTierNodes);
        if (AllNextTierNodes.isEmpty()) {
            String sampleName = (String) branchNode.get("name");
            allNeedSamples.add(sampleName);
        }

        AllNextTierNodes.forEach(childTree -> allNeedSamples.addAll(getBranchNodeNeedSamples(childTree)));

        return allNeedSamples;
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

    public static JSONObject ReadFile(File JSONFile) {
        try {
            FileInputStream FileIn = new FileInputStream(JSONFile);
            JSONTokener tokener = new JSONTokener(FileIn);
            JSONObject JSONData = new JSONObject(tokener);
            FileIn.close();
            return JSONData;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void main(String[] args) {
        String modelTreePath = "./t437k901/setting/437_901.json";
        File trackTreeFile = new File(modelTreePath);
        Map<String, Object> trackTree = ReadFile(trackTreeFile).toMap();
        Map<String, Object> treeStructure = getModelCategory(trackTree);
        System.out.println("treeStructure -> " + treeStructure);
    }
}
