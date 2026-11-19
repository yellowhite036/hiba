package org.gslab.status;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.gslab.tpm.blockchain.RpcHttp;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.BatchResponse;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/**
 * 區塊鏈動作紀錄（GET /api/chain，網頁 /chain）：accounting server 每 10 秒向鏈查詢
 * TPMDeviceRegistry 與 FileProtectionContract 的事件（eth_getLogs），整理成可讀的紀錄。
 * <p>
 * 只讀鏈，不需要私鑰。RPC 與合約位址讀自 env.json（Blockchain.rpcURL、BlockchainTPMRegistry.contractAddress、
 * BlockchainFileProtect.contractAddress），也接受和 SystemConfigurationLoader 相同的環境變數覆蓋。
 * 只在本機擔任 accounting server（{@link NodeStatusRegistry#isEnabled()}）時掃描。
 * </p>
 * <p>
 * 被合約拒絕（revert）的交易不會留下事件，所以另外逐塊檢查送到這兩份合約、但執行失敗的交易，
 * 記為 event = "Rejected"（含呼叫的函式、送出錢包、拒絕原因）。例如未綁定或已撤銷設備的錢包嘗試登記檔案。
 * 只回溯最近 {@value #REJECT_LOOKBACK_DAYS} 天（accounting server 重啟後重新掃描這段），之後逐塊跟上。
 * </p>
 */
public final class ChainActivity {

    private static final Logger logger = LoggerFactory.getLogger(ChainActivity.class);
    private static final ChainActivity INSTANCE = new ChainActivity();

    private static final long SCAN_SEC = 10;
    private static final long BLOCK_CHUNK = 5000; // 一次 eth_getLogs 查詢的區塊數
    private static final int MAX_EVENTS = 1000; // 記憶體裡保留的最新事件數
    private static final int MAX_CACHE = 5000;

    // 被拒絕的交易：逐塊掃描（QBFT 每 2 秒一塊）
    private static final int REJECT_LOOKBACK_DAYS = 7;
    private static final long REJECT_LOOKBACK_BLOCKS = REJECT_LOOKBACK_DAYS * 24L * 3600 / 2;
    private static final int HEADER_BATCH = 200; // 一次批次 RPC 讀幾個區塊
    private static final long REJECT_BLOCKS_PER_SCAN = 4_000; // 每輪最多掃幾塊：第一次回溯分多輪做，事件紀錄仍每 10 秒左右更新
    private static final int MAX_REJECTIONS = 500; // 和一般事件分開保存，大量失敗嘗試不會把正常紀錄擠掉

    /** 函式 selector → 函式名稱（解讀被拒絕的交易在呼叫什麼） */
    private static final Map<String, String> METHODS = new HashMap<>();

    static {
        for (String sig : List.of(
                "storeFile(string,string,string,string,uint256,string,string)",
                "storeFileWithStruct((string,string,string,string,uint256,string,string))",
                "batchStoreFile(string[],string[],string[],string[],uint256[],string[],string[])",
                "batchStoreFileWithStruct((string,string,string,string,uint256,string,string)[])",
                "deleteFile(string)", "batchDeleteFile(string[])", "clearAllFiles()", "destroyContract()",
                "setDeviceRegistry(address)", "bindDeviceWallet(string,address)", "unbindDeviceWallet(string)",
                "registerDevice(string,string)", "registerDeviceWithMetadata(string,string,string)",
                "batchRegisterDevices(string[],string[])", "batchRevokeDevices(string[])",
                "updatePublicKey(string,string)", "updateMetadata(string,string)",
                "revokeDevice(string)", "reactivateDevice(string)",
                "addAdmin(address)", "removeAdmin(address)", "transferOwnership(address)")) {
            METHODS.put(Hash.sha3String(sig).substring(0, 10), sig.substring(0, sig.indexOf('(')));
        }
    }

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
    private final Deque<JSONObject> rejections = new ArrayDeque<>(); // 存取時要 synchronized
    private BigInteger nextRejectBlock = null; // null：還沒開始（第一次從最新區塊往前回溯）
    private volatile long rejectScanFrom = -1;
    private volatile long rejectScanNext = -1;

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
            web3j = Web3j.build(RpcHttp.service(rpcUrl));
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
                    synchronized (rejections) {
                        rejections.clear();
                    }
                    nextRejectBlock = null;
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
                scanRejections(latest);
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

    // ============ 被合約拒絕的交易 ============

    private void scanRejections(BigInteger latest) throws Exception {
        if (nextRejectBlock == null) {
            nextRejectBlock = latest.subtract(BigInteger.valueOf(REJECT_LOOKBACK_BLOCKS)).max(BigInteger.ZERO);
            rejectScanFrom = nextRejectBlock.longValue();
        }
        BigInteger end = nextRejectBlock.add(BigInteger.valueOf(REJECT_BLOCKS_PER_SCAN - 1)).min(latest);
        while (nextRejectBlock.compareTo(end) <= 0) {
            BigInteger to = nextRejectBlock.add(BigInteger.valueOf(HEADER_BATCH - 1)).min(end);
            BatchRequest batch = web3j.newBatch();
            for (BigInteger b = nextRejectBlock; b.compareTo(to) <= 0; b = b.add(BigInteger.ONE)) {
                batch.add(web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(b), true));
            }
            BatchResponse response = batch.send();
            for (Response<?> r : response.getResponses()) {
                if (r.hasError()) {
                    throw new IllegalStateException(r.getError().getMessage());
                }
                if (r instanceof EthBlock eb && eb.getBlock() != null) {
                    checkBlock(eb.getBlock());
                }
            }
            nextRejectBlock = to.add(BigInteger.ONE);
            rejectScanNext = nextRejectBlock.longValue();
        }
    }

    private void checkBlock(EthBlock.Block block) throws Exception {
        List<EthBlock.TransactionResult> txs = block.getTransactions();
        if (txs == null || txs.isEmpty()) {
            return;
        }
        for (EthBlock.TransactionResult result : txs) {
            if (!(result.get() instanceof EthBlock.TransactionObject tx) || tx.getTo() == null) {
                continue;
            }
            String contract;
            if (tx.getTo().equalsIgnoreCase(fileAddress)) {
                contract = "file";
            } else if (tx.getTo().equalsIgnoreCase(registryAddress)) {
                contract = "registry";
            } else {
                continue;
            }
            TransactionReceipt receipt =
                    web3j.ethGetTransactionReceipt(tx.getHash()).send().getTransactionReceipt().orElse(null);
            if (receipt == null || receipt.isStatusOK()) {
                continue;
            }
            long time = block.getTimestamp() == null ? 0 : block.getTimestamp().longValue() * 1000;
            blockTimes.put(block.getNumber(), time);
            String input = tx.getInput() == null ? "" : tx.getInput();
            String selector = input.length() >= 10 ? input.substring(0, 10).toLowerCase() : "";
            String method = METHODS.getOrDefault(selector, "unknown");
            String reason = receipt.getRevertReason();
            if (reason == null || reason.isBlank()) {
                reason = replayRevertReason(tx, block.getNumber());
            }
            JSONObject e = new JSONObject()
                    .put("block", block.getNumber().longValue())
                    .put("logIndex", tx.getTransactionIndex() == null ? 0 : tx.getTransactionIndex().longValue())
                    .put("tx", tx.getHash())
                    .put("contract", contract)
                    .put("event", "Rejected")
                    .put("time", time)
                    .put("from", tx.getFrom())
                    .put("account", tx.getFrom())
                    .put("method", method)
                    .put("reason", reason == null ? JSONObject.NULL : cleanReason(reason));
            describeArgs(e, method, input);
            synchronized (rejections) {
                rejections.addFirst(e);
                while (rejections.size() > MAX_REJECTIONS) {
                    rejections.removeLast();
                }
            }
        }
    }

    /** 收據沒有 revertReason（Besu 預設不記）時，在前一個區塊的狀態上重跑一次呼叫取得原因 */
    private String replayRevertReason(EthBlock.TransactionObject tx, BigInteger block) {
        try {
            EthCall call = web3j.ethCall(
                            Transaction.createEthCallTransaction(tx.getFrom(), tx.getTo(), tx.getInput()),
                            DefaultBlockParameter.valueOf(block.subtract(BigInteger.ONE).max(BigInteger.ZERO)))
                    .send();
            if (call.hasError()) {
                String decoded = decodeError(call.getError().getData());
                return decoded != null ? decoded : call.getError().getMessage();
            }
            String decoded = decodeError(call.getValue());
            return decoded != null ? decoded : call.getRevertReason();
        } catch (Exception ex) {
            return null;
        }
    }

    /** 解出 Error(string) 的訊息（revert 資料以 0x08c379a0 開頭） */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String decodeError(String data) {
        if (data == null) {
            return null;
        }
        String hex = data.trim().replace("\"", "");
        if (!hex.toLowerCase().startsWith("0x08c379a0") || hex.length() < 10 + 128) {
            return null;
        }
        try {
            List<Type> out = FunctionReturnDecoder.decode(
                    "0x" + hex.substring(10), (List) List.of(new TypeReference<Utf8String>() {}));
            return out.isEmpty() ? null : String.valueOf(out.get(0).getValue());
        } catch (Exception ex) {
            return null;
        }
    }

    private static final java.util.regex.Pattern EXECUTION_REVERTED =
            java.util.regex.Pattern.compile("(?i)^execution reverted[:(\\s]*(.*?)\\)?$");

    /** "Execution reverted (Wallet is not bound…)" → "Wallet is not bound…" */
    private static String cleanReason(String reason) {
        String r = reason.trim();
        java.util.regex.Matcher m = EXECUTION_REVERTED.matcher(r);
        if (m.matches() && !m.group(1).isBlank()) {
            return m.group(1).trim();
        }
        return r;
    }

    /** 從呼叫參數取出可讀的資訊：檔名、EK */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void describeArgs(JSONObject e, String method, String input) {
        if (input.length() <= 10) {
            return;
        }
        String args = "0x" + input.substring(10);
        try {
            switch (method) {
                case "storeFile" -> {
                    List<Type> v = FunctionReturnDecoder.decode(args, (List) List.of(
                            new TypeReference<Utf8String>() {}, new TypeReference<Utf8String>() {}));
                    if (v.size() > 1) {
                        e.put("fileName", String.valueOf(v.get(1).getValue()));
                    }
                }
                case "batchStoreFile" -> {
                    List<Type> v = FunctionReturnDecoder.decode(args, (List) List.of(
                            new TypeReference<DynamicArray<Utf8String>>() {},
                            new TypeReference<DynamicArray<Utf8String>>() {}));
                    if (v.size() > 1 && v.get(1) instanceof DynamicArray<?> names) {
                        JSONArray list = new JSONArray();
                        for (Object n : names.getValue()) {
                            list.put(String.valueOf(((Type) n).getValue()));
                        }
                        e.put("fileNames", list);
                    }
                }
                case "bindDeviceWallet", "unbindDeviceWallet", "registerDevice", "registerDeviceWithMetadata",
                        "updatePublicKey", "updateMetadata", "revokeDevice", "reactivateDevice" -> {
                    List<Type> v = FunctionReturnDecoder.decode(args, (List) List.of(new TypeReference<Utf8String>() {}));
                    if (!v.isEmpty()) {
                        e.put("ek", String.valueOf(v.get(0).getValue()));
                    }
                }
                default -> {}
            }
        } catch (Exception ignored) {
            // 參數格式不符（例如亂送的資料）：只顯示函式與原因
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

    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    /**
     * 檔案合約上各設備綁定的錢包（節點狀態頁用）。
     *
     * @return EK → 錢包位址（小寫；沒有綁定為空字串）；檔案合約是舊版（沒有 getWalletOfDevice）時回傳 null
     * @throws Exception 連不到鏈等錯誤（呼叫端保留上一次的結果）
     */
    public Map<String, String> deviceWallets(Collection<String> eks) throws Exception {
        if (web3j == null || fileAddress.isBlank()) {
            return null;
        }
        List<String> list = new ArrayList<>(eks);
        Map<String, String> out = new HashMap<>();
        if (list.isEmpty()) {
            return out;
        }
        BatchRequest batch = web3j.newBatch();
        for (String ek : list) {
            String data = FunctionEncoder.encode(new Function(
                    "getWalletOfDevice", List.<Type>of(new Utf8String(ek)), List.<TypeReference<?>>of(new TypeReference<Address>() {})));
            batch.add(web3j.ethCall(
                    Transaction.createEthCallTransaction(null, fileAddress, data), DefaultBlockParameterName.LATEST));
        }
        List<? extends Response<?>> responses = batch.send().getResponses();
        for (int i = 0; i < list.size() && i < responses.size(); i++) {
            Response<?> r = responses.get(i);
            if (r.hasError() || !(r instanceof EthCall call) || call.isReverted()
                    || call.getValue() == null || call.getValue().length() < 66) {
                return null; // 舊版檔案合約：沒有這個函式
            }
            String addr = ("0x" + call.getValue().substring(call.getValue().length() - 40)).toLowerCase();
            out.put(list.get(i), ZERO_ADDRESS.equals(addr) ? "" : addr);
        }
        return out;
    }

    /** GET /api/chain 的內容 */
    public JSONObject toJson() {
        JSONArray list;
        synchronized (events) {
            list = new JSONArray(new ArrayList<>(events));
        }
        synchronized (rejections) {
            rejections.forEach(list::put);
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
                .put("rejectionScan", new JSONObject()
                        .put("lookbackDays", REJECT_LOOKBACK_DAYS)
                        .put("fromBlock", rejectScanFrom)
                        .put("nextBlock", rejectScanNext))
                .put("events", list);
    }
}
