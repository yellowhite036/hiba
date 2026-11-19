package org.gslab.status;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
import org.gslab.tpm.blockchain.contract.TPMDeviceRegistry;
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
 * <li>節點自動更新程式檢查新版時回報的 jar 版本（{@link #onVersionCheck}）</li>
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
    private static final int MAX_VERSION_RECORDS = 1000; // 防止亂打 /dist/...md5?node= 把記憶體塞滿
    private static final NodeStatusRegistry INSTANCE = new NodeStatusRegistry();

    /** {@link #forget} 的結果 */
    public enum ForgetResult {
        REMOVED,
        NOT_FOUND,
        STILL_ONLINE
    }

    private volatile boolean enabled = false;
    private volatile String accountingServerName = "";
    private volatile String runningMd5; // accounting server 自己執行中的 jar；從 IDE 執行時為 null
    private volatile long startedAt;
    private volatile String actingAddress; // 由根網段的 broker 擔任時是它的 IP；專用的 accounting server 為 null

    private final Map<String, NodeRecord> nodes = new ConcurrentHashMap<>(); // nodeName → 最後一次看到的資料
    /**
     * 專用的 accounting server 自己（{@link #onSelfReport}）。另外存、不放進 {@link #nodes}：
     * 不會和同名的 broker 互相覆蓋、不算進 summary、也不會在節點樹裡多畫一張卡片。
     */
    private volatile SelfRecord self;
    private final Deque<JSONObject> stepDowns = new ArrayDeque<>(); // 新的在前，存取時要 synchronized
    private final Map<String, VersionRecord> versions = new ConcurrentHashMap<>(); // nodeName → 最後一次回報的版本

    private volatile Web3BlockchainService chain;
    private volatile Map<String, TPMDeviceRegistry.DeviceInfo> chainDevices = Map.of(); // EK → 鏈上註冊資訊
    private volatile boolean chainOk = false;
    private volatile String chainError = "尚未讀取";
    /** EK → 檔案合約上綁定的錢包（空字串＝沒有綁定）；null＝還沒讀到或讀取失敗 */
    private volatile Map<String, String> chainWallets = null;
    /** 檔案合約是否支援設備錢包（null＝還不知道） */
    private volatile Boolean walletsSupported = null;
    private volatile long chainFetchedAt = 0;

    private NodeStatusRegistry() {}

    public static NodeStatusRegistry get() {
        return INSTANCE;
    }

    /** 專用的 accounting server 啟動時呼叫一次：開始接受報告，並定期讀取鏈上註冊清單。 */
    public synchronized void enable(String accountingServerName, Web3BlockchainService chain) {
        if (enabled) {
            return;
        }
        start(accountingServerName, null, chain, ManagementFactory.getRuntimeMXBean().getStartTime());
        logger.atInfo().log("Node status page enabled (GET /nodes, /api/nodes).");
    }

    /**
     * 根網段的 broker 開始擔任 accounting server（{@link org.gslab.sdns.AcctRole}）時呼叫。
     *
     * @param address 本機 IP，網頁顯示 accounting server 位址用
     */
    /** 讀鏈用的 TPMDeviceRegistry 服務（未擔任 accounting server 或沒設定時為 null） */
    Web3BlockchainService chainService() {
        return chain;
    }

    public synchronized void enableActing(String name, String address, Web3BlockchainService chain) {
        if (enabled) {
            return;
        }
        start(name, address, chain, System.currentTimeMillis());
        logger.atInfo().log("Acting as accounting server: node status page enabled.");
    }

    /** 不再擔任 accounting server：停止接受報告並清掉資料（之後重新擔任時從頭記錄） */
    public synchronized void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        self = null;
        nodes.clear();
        versions.clear();
        synchronized (stepDowns) {
            stepDowns.clear();
        }
        logger.atInfo().log("Node status page disabled (no longer the accounting server).");
    }

    private ScheduledExecutorService scheduler;

    private void start(String name, String address, Web3BlockchainService chain, long startedAt) {
        this.accountingServerName = name == null ? "" : name;
        this.actingAddress = address;
        this.chain = chain;
        // 啟動時就記下執行中 jar 的 md5：之後 jar 檔被換掉但還沒重啟，網頁才分得出來
        this.runningMd5 = DistJar.md5Of(DistJar.runningJar());
        this.startedAt = startedAt;
        this.enabled = true;
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "node-status-chain");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::refreshChain, 0, CHAIN_REFRESH_SEC, TimeUnit.SECONDS);
        } else {
            scheduler.execute(this::refreshChain);
        }
        ChainActivity.get().start(); // 區塊鏈動作紀錄（/chain）
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 由根網段的 broker 代理時為 true（專用的 accounting server 為 false） */
    public boolean isActing() {
        return enabled && actingAddress != null;
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

    /**
     * 專用的 accounting server 每 5 秒記一次自己的狀態（沒有上層可以報告）。
     * 由 broker 兼任時不用：那台 broker 照常以 {@link #onReport} 報告自己與底下的節點。
     *
     * @param ip 本機 IP；專用 server 通常不知道（null），網頁改用它的網址主機
     * @param ek 本機 EK 指紋；沒有 TPM 時為 null
     */
    public void onSelfReport(String ip, String ek, Map<String, String> resources) {
        if (!enabled || isActing()) {
            return;
        }
        long now = System.currentTimeMillis();
        SelfRecord old = self;
        Map<String, String> res = new TreeMap<>();
        if (resources != null) {
            resources.forEach((k, v) -> {
                if (k != null && v != null) {
                    res.put(k, v);
                }
            });
        }
        self = new SelfRecord(
                old == null ? now : old.firstSeen(),
                now,
                ip != null && !ip.isEmpty() ? ip : (old == null ? null : old.ip()),
                ek != null && !ek.isEmpty() ? ek : (old == null ? null : old.ek()),
                Map.copyOf(res));
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
     * 節點自動更新程式（hiba_autoupdate.sh）檢查新版時回報自己的 jar 版本。
     *
     * @param md5 節點目前的 jar md5（沒有 jar 時為空）
     * @param running 節點上的 HiBA 是否在執行
     */
    public void onVersionCheck(String name, String md5, boolean running, String remoteIp) {
        if (!enabled || name == null || name.isBlank() || name.length() > 128) {
            return;
        }
        if (!versions.containsKey(name) && versions.size() >= MAX_VERSION_RECORDS) {
            return;
        }
        String cleanMd5 = md5 != null && md5.matches("[0-9a-f]{32}") ? md5 : null;
        versions.put(name, new VersionRecord(cleanMd5, running, System.currentTimeMillis(), remoteIp));
    }

    /** 從節點表移除一台離線的節點（頁面上的「從節點表移除」）。在線的節點不能移除。 */
    public ForgetResult forget(String name) {
        long now = System.currentTimeMillis();
        NodeRecord r = nodes.get(name);
        if (r == null) {
            return ForgetResult.NOT_FOUND;
        }
        if (now - r.lastSeen() <= ONLINE_TIMEOUT_MS) {
            return ForgetResult.STILL_ONLINE;
        }
        nodes.remove(name, r);
        versions.remove(name);
        logger.atInfo().log("Node {} removed from the node status table.", name);
        return ForgetResult.REMOVED;
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
            NodeRecord r = old == null ? new NodeRecord(name, now) : old;
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
        if (!enabled) {
            return; // 不再擔任 accounting server 時不讀鏈
        }
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
            refreshWallets();
        } catch (Exception e) {
            // 保留上一次讀到的清單，但標記為不可用，讓頁面與安裝腳本知道資料可能過期
            chainOk = false;
            chainError = String.valueOf(e.getMessage());
            logger.atWarn().log("Failed to read registered devices from chain: {}", e.getMessage());
        }
    }

    /** 讀各設備在檔案合約上綁定的錢包；失敗時保留上一次的結果 */
    private void refreshWallets() {
        try {
            Map<String, String> wallets = ChainActivity.get().deviceWallets(chainDevices.keySet());
            walletsSupported = wallets != null;
            chainWallets = wallets == null ? null : Map.copyOf(wallets);
        } catch (Exception e) {
            logger.atDebug().log("Failed to read device wallets from chain: {}", e.getMessage());
        }
    }

    /**
     * 設備錢包狀態：bound 已綁定、pending 節點已回報但還沒綁定、mismatch 綁定的錢包和節點目前用的不同、
     * unbound 沒有綁定也還沒回報、unsupported 檔案合約是舊版、unknown 還沒讀到。
     */
    private JSONObject walletJson(String ek) {
        Boolean supported = walletsSupported;
        Map<String, String> wallets = chainWallets;
        String reported = WalletAttestation.reportedWallet(ek);
        String bound = wallets == null ? null : wallets.get(ek);
        String status;
        if (Boolean.FALSE.equals(supported)) {
            status = "unsupported";
        } else if (bound == null) {
            status = "unknown";
        } else if (!bound.isEmpty()) {
            status = reported != null && !reported.equalsIgnoreCase(bound) ? "mismatch" : "bound";
        } else {
            status = reported != null ? "pending" : "unbound";
        }
        return new JSONObject()
                .put("status", status)
                .put("bound", bound == null || bound.isEmpty() ? JSONObject.NULL : bound)
                .put("reported", nullToJson(reported));
    }

    // ============ 輸出 /api/nodes ============

    public JSONObject toJson() {
        long now = System.currentTimeMillis();
        Map<String, TPMDeviceRegistry.DeviceInfo> devices = chainDevices;
        boolean ok = chainOk;
        String distMd5 = DistJar.md5();

        List<NodeRecord> list = new ArrayList<>(nodes.values());
        list.sort(Comparator.comparing((NodeRecord r) -> !r.isBroker(now)).thenComparing(r -> r.name));

        // 每個 broker 目前在線的直屬下層數
        Map<String, Integer> lowerCount = new HashMap<>();
        for (NodeRecord r : list) {
            String parent = r.parent(now);
            if (parent != null && now - r.lastSeen() <= ONLINE_TIMEOUT_MS) {
                lowerCount.merge(parent, 1, Integer::sum);
            }
        }

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
            if (r.ek != null) {
                seenEks.add(r.ek);
            }
            JSONObject chainInfo = chainJson(r.ek, ok, devices);
            nodeArray.put(new JSONObject()
                    .put("name", r.name)
                    .put("ip", nullToJson(r.ip))
                    .put("role", isBroker ? "broker" : "node")
                    .put("status", isOnline ? "online" : "offline")
                    .put("broker", nullToJson(r.parent(now)))
                    .put("lowerNodeCount", lowerCount.getOrDefault(r.name, 0))
                    .put("firstSeen", r.firstSeen)
                    .put("lastSeen", r.lastSeen())
                    .put("ek", nullToJson(r.ek))
                    .put("chain", chainInfo)
                    .put("version", versionJson(versions.get(r.name), distMd5))
                    .put("resources", new JSONObject(r.resources)));
        }

        // 專用 accounting server 自己（兼任時它已經在 nodes 裡）
        SelfRecord me = isActing() ? null : self;
        Object selfJson = JSONObject.NULL;
        if (me != null) {
            if (me.ek() != null) {
                seenEks.add(me.ek());
            }
            selfJson = new JSONObject()
                    .put("ip", nullToJson(me.ip()))
                    .put("status", now - me.lastSeen() <= ONLINE_TIMEOUT_MS ? "online" : "offline")
                    .put("firstSeen", me.firstSeen())
                    .put("lastSeen", me.lastSeen())
                    .put("ek", nullToJson(me.ek()))
                    .put("chain", chainJson(me.ek(), ok, devices))
                    .put("resources", new JSONObject(me.resources()));
        }

        JSONArray neverSeen = new JSONArray();
        devices.forEach((ek, info) -> {
            if (!seenEks.contains(ek)) {
                neverSeen.put(new JSONObject()
                        .put("ek", ek)
                        .put("active", info.active())
                        .put("registeredAt", info.registeredAt().longValue()));
            }
        });

        JSONArray stepDownArray;
        synchronized (stepDowns) {
            stepDownArray = new JSONArray(new ArrayList<>(stepDowns));
        }

        return new JSONObject()
                .put("generatedAt", now)
                .put(
                        "accountingServer",
                        new JSONObject()
                                .put("name", accountingServerName)
                                .put("startedAt", startedAt)
                                .put("acting", actingAddress != null)
                                .put("address", nullToJson(actingAddress))
                                .put("self", selfJson)
                                .put(
                                        "version",
                                        new JSONObject()
                                                .put("md5", nullToJson(runningMd5))
                                                .put("current", runningMd5 != null && runningMd5.equals(distMd5))))
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
                .put("dist", new JSONObject().put("md5", nullToJson(distMd5)))
                .put(
                        "chainStatus",
                        new JSONObject()
                                .put("ok", ok)
                                .put("fetchedAt", chainFetchedAt)
                                .put("registeredTotal", devices.size())
                                .put("error", nullToJson(chainError)));
    }

    /** 一台設備在 TPMDeviceRegistry 上的註冊狀態與綁定的錢包 */
    private JSONObject chainJson(String ek, boolean ok, Map<String, TPMDeviceRegistry.DeviceInfo> devices) {
        JSONObject chainInfo = new JSONObject().put("checked", ok && ek != null);
        if (ek != null && ok) {
            TPMDeviceRegistry.DeviceInfo info = devices.get(ek);
            chainInfo.put("registered", info != null);
            chainInfo.put("active", info != null && info.active());
            if (info != null) {
                chainInfo.put("registeredAt", info.registeredAt().longValue());
                chainInfo.put("wallet", walletJson(ek));
            }
        }
        return chainInfo;
    }

    /** 節點沒裝自動更新（從沒回報過）時為 JSON null */
    private static Object versionJson(VersionRecord v, String distMd5) {
        if (v == null) {
            return JSONObject.NULL;
        }
        return new JSONObject()
                .put("md5", nullToJson(v.md5()))
                .put("running", v.running())
                .put("checkedAt", v.checkedAt())
                .put("current", v.md5() != null && v.md5().equals(distMd5));
    }

    private static String coalesce(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private static Object nullToJson(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private record SelfRecord(long firstSeen, long lastSeen, String ip, String ek, Map<String, String> resources) {}

    private record VersionRecord(String md5, boolean running, long checkedAt, String remoteIp) {}

    private static final class NodeRecord {
        final String name;
        final long firstSeen;
        String ip;
        String parent; // 最後一次把這台列為下層的 broker
        String ek;
        long lastBrokerReport; // 最後一次以 broker 身分自己報告
        long lastListed; // 最後一次被某個 broker 列為下層
        Map<String, String> resources = Map.of();

        NodeRecord(String name, long firstSeen) {
            this.name = name;
            this.firstSeen = firstSeen;
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
