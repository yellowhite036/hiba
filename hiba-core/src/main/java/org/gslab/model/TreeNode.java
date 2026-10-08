package org.gslab.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 樹狀結構節點的資料模型 用於替代 Map<String, Object> 表示樹狀分類器
 */
public class TreeNode {

    private String name;
    private List<String> value;
    private List<TreeNode> children;
    private Map<String, Object> additionalData;

    public TreeNode() {
        this.children = new ArrayList<>();
        this.value = new ArrayList<>();
        this.additionalData = new HashMap<>();
    }

    public TreeNode(String name) {
        this();
        this.name = name;
    }

    public TreeNode(String name, List<String> value) {
        this(name);
        this.value = value != null ? new ArrayList<>(value) : new ArrayList<>();
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getValue() {
        return value;
    }

    public void setValue(List<String> value) {
        this.value = value != null ? new ArrayList<>(value) : new ArrayList<>();
    }

    public List<TreeNode> getChildren() {
        return children;
    }

    public void setChildren(List<TreeNode> children) {
        this.children = children != null ? new ArrayList<>(children) : new ArrayList<>();
    }

    public Map<String, Object> getAdditionalData() {
        return additionalData;
    }

    public void setAdditionalData(Map<String, Object> additionalData) {
        this.additionalData = additionalData != null ? new HashMap<>(additionalData) : new HashMap<>();
    }

    // Utility methods
    public void addChild(TreeNode child) {
        if (child != null) {
            this.children.add(child);
        }
    }

    public void addValue(String value) {
        if (value != null) {
            this.value.add(value);
        }
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    /**
     * 獲取所有需要的樣本名稱（遞迴）
     */
    public List<String> getAllRequiredSamples() {
        List<String> samples = new ArrayList<>();

        if (isLeaf()) {
            if (name != null) {
                samples.add(name);
            }
        } else {
            for (TreeNode child : children) {
                samples.addAll(child.getAllRequiredSamples());
            }
        }

        return samples;
    }

    /**
     * 獲取模型分類（扁平化結構）
     */
    public Map<String, List<String>> getModelCategories() {
        Map<String, List<String>> categories = new HashMap<>();

        for (TreeNode child : children) {
            String categoryName = name + child.getName();
            categories.put(categoryName, new ArrayList<>(child.getValue()));
        }

        return categories;
    }

    /**
     * 轉換為 Map 格式（向後兼容）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        if (name != null) {
            map.put("name", name);
        }

        if (value != null && !value.isEmpty()) {
            map.put("value", new ArrayList<>(value));
        }

        if (children != null && !children.isEmpty()) {
            List<Map<String, Object>> childMaps = new ArrayList<>();
            for (TreeNode child : children) {
                childMaps.add(child.toMap());
            }
            map.put("children", childMaps);
        }

        if (additionalData != null) {
            map.putAll(additionalData);
        }

        return map;
    }

    /**
     * 從 Map 創建 TreeNode（向後兼容）
     */
    @SuppressWarnings("unchecked")
    public static TreeNode fromMap(Map<String, Object> map) {
        TreeNode node = new TreeNode();

        if (map.containsKey("name")) {
            node.setName((String) map.get("name"));
        }

        if (map.containsKey("value")) {
            Object valueObj = map.get("value");
            if (valueObj instanceof List) {
                node.setValue((List<String>) valueObj);
            }
        }

        if (map.containsKey("children")) {
            Object childrenObj = map.get("children");
            if (childrenObj instanceof List) {
                List<Map<String, Object>> childMaps = (List<Map<String, Object>>) childrenObj;
                List<TreeNode> children = new ArrayList<>();
                for (Map<String, Object> childMap : childMaps) {
                    children.add(fromMap(childMap));
                }
                node.setChildren(children);
            }
        }

        // 添加其他資料
        Map<String, Object> additionalData = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!entry.getKey().equals("name")
                    && !entry.getKey().equals("value")
                    && !entry.getKey().equals("children")) {
                additionalData.put(entry.getKey(), entry.getValue());
            }
        }
        node.setAdditionalData(additionalData);

        return node;
    }

    /**
     * 驗證樹節點的有效性
     */
    public boolean isValid() {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }

        // 檢查子節點
        for (TreeNode child : children) {
            if (!child.isValid()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public String toString() {
        return String.format("TreeNode{name='%s', value=%s, children=%d}", name, value, children.size());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        TreeNode treeNode = (TreeNode) obj;

        if (!Objects.equals(name, treeNode.name)) {
            return false;
        }
        if (!Objects.equals(value, treeNode.value)) {
            return false;
        }
        return Objects.equals(children, treeNode.children);
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (value != null ? value.hashCode() : 0);
        result = 31 * result + (children != null ? children.hashCode() : 0);
        return result;
    }
}
