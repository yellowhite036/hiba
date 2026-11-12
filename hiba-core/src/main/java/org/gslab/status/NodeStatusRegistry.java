package org.gslab.status;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.gslab.message_hiba.BrokerStepDownNotice;
import org.gslab.message_hiba.NodeStatusReport;
import org.gslab.tpm.blockchain.Web3BlockchainService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * accounting server 上的節點狀態表。
 * <p>
 * 資料來源：
 * <ul>
 * <li>各 broker 每 5 秒送來的 {@link NodeStatusReport}（broker 自己與底下的節點）</li>
 * <li>broker 讓位時送來的 {@link BrokerStepDownNotice}</li>
 * <li>每 60 秒從 TPMDeviceRegistry 合約讀一次已註冊的 EK 清單</li>
 * </ul>
 * 只在 accounting server 上啟用（{@link #enable}）；其他節點呼叫任何方法都不會有作用。
 * </p>
 */
public final class NodeStatusRegistry {

    private static final Logger logger = LoggerFactory.getLogger(NodeStatusRegistry.class);

    /** 超過這段時間沒出現在任何報告裡就算離線（broker 每 5 秒報告一次，等於連續漏掉 3 次） */
    public static final long ONLINE_TIMEOUT_MS = 15_000;

    private static final long CHAIN_REFRESH_SEC = 60;
    private static final int MAX_STEP_DOWN_RECORDS = 100;
    private static final NodeStatusRegistry INSTANCE = new NodeStatusRegistry();

    private volatile boolean enabled = false;
    private volatile String accountingServerName = "";

    private final Map<String, NodeRecord> nodes = new ConcurrentHashMap<>(); // nodeName → 最後一次看到的資料
    private final Deque<JSONObject> stepDowns = new ArrayDeque<>(); // 新的在前，存取時要 synchronized

    private volatile Web3BlockchainService chain;
    private volatile Map<String, Boolean> chainDevices = Map.of(); // EK → 是否啟用（未撤銷）
    private volatile boolean chainOk = false;
    private volatile String chainError = "尚未讀取";
    private volatile long chainFetchedAt = 0;

    private NodeStatusRegistry() {}

    public static NodeStatusRegistry get() {
        return INSTANCE;
    }

    /** accounting server 啟動時呼叫一次：開始接受報告，並定期讀取鏈上註冊清單。 */
    public synchronized void enable(String accountingServerName, Web3BlockchainService chain) {
        if (enabled) {
            return;
        }
        this.accountingServerName = accountingServerName == null ? "" : accountingServerName;
        this.chain = chain;
        this.enabled = true;

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "node-status-chain");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::refreshChain, 0, CHAIN_REFRESH_SEC, TimeUnit.SECONDS);
        logger.atInfo().log("Node status page enabled (GET /nodes, /api/nodes).");
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ============ 資料進來 ============

    /**
     * @param channelEk 這條連線經 TPM 驗證得到的 EK（broker 自己的），報告裡沒帶時用它
     * @param remoteIp 連線來源 IP，報告裡沒帶 broker IP 時用它
     */
    public void onReport(NodeStatusReport report, String channelEk, String remoteIp) {
        if (!enabled || report == null || report.getBroker() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        NodeStatusReport.NodeEntry broker = report.getBroker();
        update(broker, null, coalesce(broker.getEkFingerprint(), channelEk), coalesce(broker.getIp(), remoteIp), now);
        for (NodeStatusReport.NodeEntry node : report.getNodes()) {
            update(node, broker.getName(), node.getEkFingerprint(), node.getIp(), now);
        }
    }

    public void onStepDown(BrokerStepDownNotice notice) {
        if (!enabled || notice == null) {
            return;
        }
        JSONObject record = new JSONObject()
                .put("time", notice.getStepDownTime())
                .put("broker", notice.getBrokerName())
                .put("brokerIp", nullToJson(notice.getBrokerIp()))
                .put("winner", notice.getWinnerName())
                .put("winnerIp", nullToJson(notice.getWinnerIp()));
        synchronized (stepDowns) {
            stepDowns.addFirst(record);
            while (stepDowns.size() > MAX_STEP_DOWN_RECORDS) {
                stepDowns.removeLast();
            }
        }
        logger.atInfo().log(
                "Broker {} stepped down in favour of {} ({}).",
                notice.getBrokerName(),
                notice.getWinnerName(),
                notice.getWinnerIp());
    }

    /**
     * @param listedBy 列出這台的 broker；null 表示這是 broker 自己的報告。
     *     多層架構裡下層 broker 同時會「自己報告」又被上層 broker「列為下層」，
     *     所以兩種時間分開記，輸出時才決定角色與上層（見 {@link NodeRecord}）。
     */
    private void update(NodeStatusReport.NodeEntry entry, String listedBy, String ek, String ip, long now) {
        if (entry.getName() == null || entry.getName().isEmpty()) {
            return;
        }
        nodes.compute(entry.getName(), (name, old) -> {
            NodeRecord r = old == null ? new NodeRecord(name) : old;
            if (listedBy == null) {
                r.lastBrokerReport = now;
            } else {
                r.parent = listedBy;
                r.lastListed = now;
            }
            if (ip != null && !ip.isEmpty()) {
                r.ip = ip;
            }
            if (ek != null && !ek.isEmpty()) {
                r.ek = ek;
            }
            Map<String, String> resources = new TreeMap<>();
            entry.getResources().forEach((k, v) -> {
                if (k != null && v != null) {
                    resources.put(k, v);
                }
            });
            r.resources = resources;
            return r;
        });
    }

    private void refreshChain() {
        Web3BlockchainService service = chain;
        if (service == null) {
            chainOk = false;
            chainError = "accounting server 沒有設定 TPMDeviceRegistry";
            return;
        }
        try {
            chainDevices = Map.copyOf(service.listRegisteredDevices());
            chainOk = true;
            chainError = null;
            chainFetchedAt = System.currentTimeMillis();
        } catch (Exception e) {
            // 保留上一次讀到的清單，但標記為不可用，讓頁面與安裝腳本知道資料可能過期
            chainOk = false;
            chainError = String.valueOf(e.getMessage());
            logger.atWarn().log("Failed to read registered devices from chain: {}", e.getMessage());
        }
    }

    // ============ 輸出 /api/nodes ============

    public JSONObject toJson() {
        long now = System.currentTimeMillis();
        Map<String, Boolean> devices = chainDevices;
        boolean ok = chainOk;

        List<NodeRecord> list = new ArrayList<>(nodes.values());
        list.sort(Comparator.comparing((NodeRecord r) -> !r.isBroker(now)).thenComparing(r -> r.name));

        JSONArray nodeArray = new JSONArray();
        Set<String> seenEks = new HashSet<>();
        int online = 0;
        int brokers = 0;
        for (NodeRecord r : list) {
            boolean isOnline = now - r.lastSeen() <= ONLINE_TIMEOUT_MS;
            boolean isBroker = r.isBroker(now);
            if (isOnline) {
                online++;
                if (isBroker) {
                    brokers++;
                }
            }
            JSONObject chainInfo = new JSONObject().put("checked", ok && r.ek != null);
            if (r.ek != null) {
                seenEks.add(r.ek);
                if (ok) {
                    chainInfo.put("registered", devices.containsKey(r.ek));
                    chainInfo.put("active", Boolean.TRUE.equals(devices.get(r.ek)));
                }
            }
            nodeArray.put(new JSONObject()
                    .put("name", r.name)
                    .put("ip", nullToJson(r.ip))
                    .put("role", isBroker ? "broker" : "node")
                    .put("status", isOnline ? "online" : "offline")
                    .put("broker", nullToJson(r.parent(now)))
                    .put("lastSeen", r.lastSeen())
                    .put("ek", nullToJson(r.ek))
                    .put("chain", chainInfo)
                    .put("resources", new JSONObject(r.resources)));
        }

        JSONArray neverSeen = new JSONArray();
        devices.forEach((ek, active) -> {
            if (!seenEks.contains(ek)) {
                neverSeen.put(new JSONObject().put("ek", ek).put("active", active));
            }
        });

        JSONArray stepDownArray;
        synchronized (stepDowns) {
            stepDownArray = new JSONArray(new ArrayList<>(stepDowns));
        }

        return new JSONObject()
                .put("generatedAt", now)
                .put("accountingServer", new JSONObject().put("name", accountingServerName))
                .put("onlineTimeoutMs", ONLINE_TIMEOUT_MS)
                .put(
                        "summary",
                        new JSONObject()
                                .put("total", list.size())
                                .put("online", online)
                                .put("offline", list.size() - online)
                                .put("brokers", brokers)
                                .put("registeredNeverSeen", neverSeen.length()))
                .put("nodes", nodeArray)
                .put("registeredNeverSeen", neverSeen)
                .put("stepDowns", stepDownArray)
                .put(
                        "chainStatus",
                        new JSONObject()
                                .put("ok", ok)
                                .put("fetchedAt", chainFetchedAt)
                                .put("deviceCount", devices.size())
                                .put("error", nullToJson(chainError)));
    }

    private static String coalesce(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private static Object nullToJson(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static final class NodeRecord {
        final String name;
        String ip;
        String parent; // 最後一次把這台列為下層的 broker
        String ek;
        long lastBrokerReport; // 最後一次以 broker 身分自己報告
        long lastListed; // 最後一次被某個 broker 列為下層
        Map<String, String> resources = Map.of();

        NodeRecord(String name) {
            this.name = name;
        }

        long lastSeen() {
            return Math.max(lastBrokerReport, lastListed);
        }

        /** 最近還在以 broker 身分報告就是 broker；都過期了就看最後一次是哪種 */
        boolean isBroker(long now) {
            if (now - lastBrokerReport <= ONLINE_TIMEOUT_MS) {
                return true;
            }
            if (now - lastListed <= ONLINE_TIMEOUT_MS) {
                return false;
            }
            return lastBrokerReport > lastListed;
        }

        /** 上層 broker；最上層的 broker（直接掛在 accounting server）為 null */
        String parent(long now) {
            if (now - lastListed <= ONLINE_TIMEOUT_MS) {
                return parent;
            }
            return isBroker(now) ? null : parent;
        }
    }
}
