package org.gslab.status;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.http.HttpService;

/**
 * 區塊鏈動作紀錄（GET /api/chain，網頁 /chain）：accounting server 每 10 秒向鏈查詢
 * TPMDeviceRegistry 與 FileProtectionContract 的事件（eth_getLogs），整理成可讀的紀錄。
 * <p>
 * 只讀鏈，不需要私鑰。RPC 與合約位址讀自 env.json（Blockchain.rpcURL、BlockchainTPMRegistry.contractAddress、
 * BlockchainFileProtect.contractAddress），也接受和 SystemConfigurationLoader 相同的環境變數覆蓋。
 * 只在本機擔任 accounting server（{@link NodeStatusRegistry#isEnabled()}）時掃描。
 * </p>
 */
public final class ChainActivity {

    private static final Logger logger = LoggerFactory.getLogger(ChainActivity.class);
    private static final ChainActivity INSTANCE = new ChainActivity();

    private static final long SCAN_SEC = 10;
    private static final long BLOCK_CHUNK = 5000; // 一次 eth_getLogs 查詢的區塊數
    private static final int MAX_EVENTS = 1000; // 記憶體裡保留的最新事件數
    private static final int MAX_CACHE = 5000;

    /** 一種事件：名稱、所屬合約、ABI 定義 */
    private record Def(String name, String contract, Event event) {}

    private static final Map<String, Def> DEFS = new HashMap<>();

    static {
        TypeReference<Utf8String> indexedString = new TypeReference<>(true) {};
        TypeReference<Address> indexedAddress = new TypeReference<>(true) {};
        TypeReference<Utf8String> string = new TypeReference<>() {};
        TypeReference<Uint256> uint = new TypeReference<>() {};
        for (String name : List.of("DeviceRegistered", "DeviceRevoked", "DeviceReactivated", "DevicePublicKeyUpdated")) {
            add(name, "registry", new Event(name, Arrays.asList(indexedString, string, uint)));
        }
        add("AdminAdded", "registry", new Event("AdminAdded", List.of(indexedAddress)));
        add("AdminRemoved", "registry", new Event("AdminRemoved", List.of(indexedAddress)));
        add("FileStored", "file", new Event("FileStored", Arrays.asList(string, indexedAddress, string, uint)));
        add("FileUpdated", "file", new Event("FileUpdated", Arrays.asList(indexedString, indexedAddress, uint)));
        add("FileDeleted", "file", new Event("FileDeleted", Arrays.asList(indexedString, indexedAddress, uint)));
        add("FilesCleared", "file", new Event("FilesCleared", Arrays.asList(indexedAddress, uint, uint)));
        add("BatchFileDeleted", "file", new Event("BatchFileDeleted", Arrays.asList(indexedAddress, uint, uint)));
        add("BatchFileStored", "file", new Event("BatchFileStored", Arrays.asList(indexedAddress, uint, uint)));
        // 設備錢包（FileProtectionContract 新版）：每筆檔案紀錄是哪台設備（EK）送出的
        TypeReference<Bool> bool = new TypeReference<>() {};
        add("FileStoredByDevice", "file", new Event("FileStoredByDevice", Arrays.asList(string, string, indexedAddress, bool, uint)));
        add("DeviceRegistrySet", "file", new Event("DeviceRegistrySet", Arrays.asList(indexedAddress, uint)));
        for (String name : List.of("DeviceWalletBound", "DeviceWalletUnbound")) {
            add(name, "file", new Event(name, Arrays.asList(indexedString, string, indexedAddress, uint)));
        }
    }

    private static void add(String name, String contract, Event event) {
        DEFS.put(EventEncoder.encode(event), new Def(name, contract, event));
    }

    private volatile boolean started = false;
    private volatile Web3j web3j;
    private volatile String rpcUrl = "";
    private volatile String registryAddress = "";
    private volatile String fileAddress = "";

    private BigInteger nextBlock = BigInteger.ZERO;
    private final Deque<JSONObject> events = new ArrayDeque<>(); // 新的在前，存取時要 synchronized
    private final Map<BigInteger, Long> blockTimes = lru();
    private final Map<String, String> txSenders = lru();

    private volatile long chainId = -1;
    private volatile long latestBlock = -1;
    private volatile long lastScanAt = 0;
    private volatile String error = "尚未讀取";

    private ChainActivity() {}

    public static ChainActivity get() {
        return INSTANCE;
    }

    private static <K, V> Map<K, V> lru() {
        return new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_CACHE;
            }
        };
    }

    /** accounting server 啟用節點狀態頁時呼叫；重複呼叫沒有作用 */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            JSONObject env = new JSONObject(Files.readString(new File("./env.json").toPath()));
            JSONObject chain = env.optJSONObject("Blockchain");
            JSONObject registry = env.optJSONObject("BlockchainTPMRegistry");
            JSONObject file = env.optJSONObject("BlockchainFileProtect");
            rpcUrl = override("BLOCKCHAIN_RPC_URL", chain == null ? "" : chain.optString("rpcURL", ""));
            registryAddress = override(
                    "TPM_DEVICE_REGISTRY_CONTRACT_ADDRESS", registry == null ? "" : registry.optString("contractAddress", ""));
            fileAddress = override(
                    "BLOCKCHAIN_CONTRACT_ADDRESS", file == null ? "" : file.optString("contractAddress", ""));
        } catch (Exception e) {
            error = "讀取 env.json 失敗：" + e.getMessage();
        }
        if (!rpcUrl.isBlank()) {
            web3j = Web3j.build(new HttpService(rpcUrl));
        } else if (error.equals("尚未讀取")) {
            error = "env.json 沒有設定 Blockchain.rpcURL";
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chain-activity");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::scan, 0, SCAN_SEC, TimeUnit.SECONDS);
    }

    private static String override(String envName, String value) {
        String env = System.getenv(envName);
        return env != null && !env.isBlank() ? env.trim() : (value == null ? "" : value.trim());
    }

    private List<String> addresses() {
        List<String> list = new ArrayList<>();
        if (!registryAddress.isBlank()) {
            list.add(registryAddress);
        }
        if (!fileAddress.isBlank()) {
            list.add(fileAddress);
        }
        return list;
    }

    private void scan() {
        if (!NodeStatusRegistry.get().isEnabled() || web3j == null) {
            return;
        }
        List<String> addresses = addresses();
        if (addresses.isEmpty()) {
            error = "env.json 沒有設定任何合約位址";
            return;
        }
        try {
            if (chainId < 0) {
                chainId = web3j.ethChainId().send().getChainId().longValue();
            }
            BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
            synchronized (this) {
                if (latest.compareTo(nextBlock) < 0 && nextBlock.signum() > 0) {
                    // 鏈的高度比上次掃到的還低：鏈被重置（例如 Anvil 重啟、沒載入狀態）→ 從頭重讀
                    logger.atWarn().log("Chain height dropped to {} (was scanning from {}), rescanning from 0", latest, nextBlock);
                    nextBlock = BigInteger.ZERO;
                    synchronized (events) {
                        events.clear();
                    }
                    blockTimes.clear();
                    txSenders.clear();
                }
                while (nextBlock.compareTo(latest) <= 0) {
                    BigInteger to = nextBlock.add(BigInteger.valueOf(BLOCK_CHUNK - 1)).min(latest);
                    EthFilter filter = new EthFilter(
                            DefaultBlockParameter.valueOf(nextBlock), DefaultBlockParameter.valueOf(to), addresses);
                    EthLog result = web3j.ethGetLogs(filter).send();
                    if (result.hasError()) {
                        throw new IllegalStateException(result.getError().getMessage());
                    }
                    for (EthLog.LogResult<?> r : result.getLogs()) {
                        if (r.get() instanceof Log log && !log.isRemoved()) {
                            record(log);
                        }
                    }
                    nextBlock = to.add(BigInteger.ONE);
                }
            }
            latestBlock = latest.longValue();
            lastScanAt = System.currentTimeMillis();
            error = null;
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            logger.atDebug().log("Chain activity scan failed: {}", e.getMessage());
        }
    }

    private void record(Log log) throws Exception {
        List<String> topics = log.getTopics();
        if (topics == null || topics.isEmpty()) {
            return;
        }
        Def def = DEFS.get(topics.get(0));
        String contract = log.getAddress() != null && log.getAddress().equalsIgnoreCase(registryAddress) ? "registry" : "file";
        JSONObject e = new JSONObject()
                .put("block", log.getBlockNumber().longValue())
                .put("logIndex", log.getLogIndex() == null ? 0 : log.getLogIndex().longValue())
                .put("tx", log.getTransactionHash())
                .put("contract", def != null ? def.contract() : contract)
                .put("event", def != null ? def.name() : "Unknown")
                .put("time", blockTime(log.getBlockNumber()))
                .put("from", JSONObject.wrap(txSender(log.getTransactionHash())));
        if (def != null) {
            List<Type> data = FunctionReturnDecoder.decode(log.getData(), def.event().getNonIndexedParameters());
            switch (def.name()) {
                case "DeviceRegistered", "DeviceRevoked", "DeviceReactivated", "DevicePublicKeyUpdated" ->
                        e.put("ek", value(data, 0));
                case "AdminAdded", "AdminRemoved" -> e.put("account", topicAddress(topics, 1));
                case "FileStored" -> e.put("fileHash", value(data, 0))
                        .put("fileName", value(data, 1))
                        .put("account", topicAddress(topics, 1));
                case "FileUpdated", "FileDeleted" -> e.put("fileHashKeccak", topics.size() > 1 ? topics.get(1) : JSONObject.NULL)
                        .put("account", topicAddress(topics, 2));
                case "FilesCleared", "BatchFileDeleted", "BatchFileStored" -> e.put("count", value(data, 0))
                        .put("account", topicAddress(topics, 1));
                case "FileStoredByDevice" -> e.put("fileHash", value(data, 0))
                        .put("ek", value(data, 1))
                        .put("isUpdate", data != null && data.size() > 2 && Boolean.TRUE.equals(data.get(2).getValue()))
                        .put("account", topicAddress(topics, 1));
                case "DeviceRegistrySet" -> e.put("account", topicAddress(topics, 1));
                case "DeviceWalletBound", "DeviceWalletUnbound" -> e.put("ek", value(data, 0))
                        .put("account", topicAddress(topics, 2));
                default -> {}
            }
        }
        synchronized (events) {
            events.addFirst(e);
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }
    }

    private static Object value(List<Type> data, int i) {
        if (data == null || data.size() <= i) {
            return JSONObject.NULL;
        }
        Object v = data.get(i).getValue();
        return v instanceof BigInteger b ? b.toString() : String.valueOf(v);
    }

    private static Object topicAddress(List<String> topics, int i) {
        if (topics.size() <= i || topics.get(i) == null || topics.get(i).length() < 42) {
            return JSONObject.NULL;
        }
        return "0x" + topics.get(i).substring(topics.get(i).length() - 40);
    }

    private long blockTime(BigInteger block) {
        Long cached = blockTimes.get(block);
        if (cached != null) {
            return cached;
        }
        try {
            EthBlock.Block b = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(block), false)
                    .send()
                    .getBlock();
            long ms = b == null ? 0 : b.getTimestamp().longValue() * 1000;
            blockTimes.put(block, ms);
            return ms;
        } catch (Exception e) {
            return 0;
        }
    }

    private String txSender(String txHash) {
        if (txHash == null) {
            return null;
        }
        String cached = txSenders.get(txHash);
        if (cached != null) {
            return cached;
        }
        try {
            String from = web3j.ethGetTransactionByHash(txHash)
                    .send()
                    .getTransaction()
                    .map(t -> t.getFrom())
                    .orElse(null);
            if (from != null) {
                txSenders.put(txHash, from);
            }
            return from;
        } catch (Exception e) {
            return null;
        }
    }

    /** GET /api/chain 的內容 */
    public JSONObject toJson() {
        JSONArray list;
        synchronized (events) {
            list = new JSONArray(new ArrayList<>(events));
        }
        return new JSONObject()
                .put("generatedAt", System.currentTimeMillis())
                .put("rpcUrl", rpcUrl)
                .put("chainId", chainId)
                .put("latestBlock", latestBlock)
                .put("lastScanAt", lastScanAt)
                .put("scanIntervalSec", SCAN_SEC)
                .put("error", error == null ? JSONObject.NULL : error)
                .put("contracts", new JSONObject()
                        .put("registry", registryAddress.isBlank() ? JSONObject.NULL : registryAddress)
                        .put("file", fileAddress.isBlank() ? JSONObject.NULL : fileAddress))
                .put("events", list);
    }
}
