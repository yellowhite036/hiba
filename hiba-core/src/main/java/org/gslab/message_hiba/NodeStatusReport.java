package org.gslab.message_hiba;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * broker 定期送給 accounting server 的狀態報告：broker 自己和它底下的節點。
 * accounting server 用它來畫節點狀態頁（/nodes、/api/nodes）。
 */
public class NodeStatusReport extends Message {

    private static final long serialVersionUID = 1L;

    private final NodeEntry broker;
    private final ArrayList<NodeEntry> nodes;
    private final long reportTime;

    public NodeStatusReport(NodeEntry broker, List<NodeEntry> nodes) {
        this.broker = broker;
        this.nodes = new ArrayList<>(nodes);
        this.reportTime = System.currentTimeMillis();
    }

    public NodeEntry getBroker() {
        return broker;
    }

    public List<NodeEntry> getNodes() {
        return nodes;
    }

    public long getReportTime() {
        return reportTime;
    }

    /** 一台節點的資料。ekFingerprint 在沒開 TPM 簽章或還不知道時為 null。 */
    public static class NodeEntry implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String name;
        private final String ip;
        private final String ekFingerprint;
        private final HashMap<String, String> resources;

        public NodeEntry(String name, String ip, String ekFingerprint, Map<String, String> resources) {
            this.name = name;
            this.ip = ip;
            this.ekFingerprint = ekFingerprint;
            this.resources = resources == null ? new HashMap<>() : new HashMap<>(resources);
        }

        public String getName() {
            return name;
        }

        public String getIp() {
            return ip;
        }

        public String getEkFingerprint() {
            return ekFingerprint;
        }

        public Map<String, String> getResources() {
            return resources;
        }
    }
}
