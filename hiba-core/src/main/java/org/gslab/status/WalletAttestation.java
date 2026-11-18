package org.gslab.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.gslab.sdns.AcctRole;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;
import org.gslab.tpm.TPMService;
import org.gslab.tpm.blockchain.Web3BlockchainService;
import org.gslab.util.DeviceWallet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 設備錢包聲明：每台節點用 TPM 簽章金鑰簽「我的設備錢包是 0x…」，定期送給 accounting server，
 * 讓管理員在 Pi 上用 bind_wallets.sh 一次把所有節點的錢包綁定到它們的 EK（不必登入每台節點）。
 * <p>
 * 被簽的內容（UTF-8）：{@code HiBA-device-wallet:v1:<EK指紋>:<錢包位址小寫>:<時間毫秒>}，
 * 演算法與 HiBA 訊息簽章相同（RSASSA-PKCS1-v1.5 / SHA-256），可用鏈上登記的公鑰以 openssl 驗證。
 * </p>
 * <ul>
 * <li>節點：{@link #startReporting()} 啟動後 30 秒送一次，之後每 10 分鐘一次（POST /api/wallets）。</li>
 * <li>accounting server：{@link #accept} 用鏈上的公鑰驗章，通過才保存；GET /api/wallets 列出。</li>
 * </ul>
 * 錢包聲明本身不給任何權限：真正的綁定要管理員私鑰（bind_wallets.sh 會再自行驗章一次）。
 */
public final class WalletAttestation {

    private static final Logger logger = LoggerFactory.getLogger(WalletAttestation.class);

    public static final String PREFIX = "HiBA-device-wallet:v1:";
    private static final long REPORT_EVERY_SEC = 600;
    private static final long MAX_CLOCK_SKEW_MS = 15 * 60 * 1000L;
    private static final int MAX_ENTRIES = 1000;

    private static final Map<String, JSONObject> BY_EK = new ConcurrentHashMap<>();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static volatile boolean reporting = false;

    private WalletAttestation() {}

    public static String message(String ek, String wallet, long timestamp) {
        return PREFIX + ek + ":" + wallet.toLowerCase(Locale.ROOT) + ":" + timestamp;
    }

    // ============ 節點：簽章並送出 ============

    public static synchronized void startReporting() {
        if (reporting) {
            return;
        }
        reporting = true;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wallet-attestation");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(WalletAttestation::reportOnce, 30, REPORT_EVERY_SEC, TimeUnit.SECONDS);
    }

    private static void reportOnce() {
        try {
            TPMService tpm = HiBAInitial.tpmService;
            if (tpm == null) {
                return; // 沒有 TPM：無法證明錢包屬於這台設備
            }
            String ek = tpm.getEKFingerprint();
            String wallet = DeviceWallet.loadOrCreate(Path.of(".")).getAddress();
            long now = System.currentTimeMillis();
            String signature = Base64.getEncoder()
                    .encodeToString(tpm.sign(message(ek, wallet, now).getBytes(StandardCharsets.UTF_8)));
            JSONObject body = new JSONObject()
                    .put("name", HiBAInitial.nodeName == null ? "" : HiBAInitial.nodeName)
                    .put("ek", ek)
                    .put("wallet", wallet)
                    .put("timestamp", now)
                    .put("signature", signature);

            NodeStatusRegistry registry = NodeStatusRegistry.get();
            if (registry.isEnabled()) {
                String err = accept(body, HiBANetwork.LOCAL_IP, registry.chainService());
                logDelivery(err == null ? 200 : 400, err == null ? "" : err);
                return;
            }
            String acct = AcctRole.knownAcct();
            if (acct == null) {
                acct = AcctRole.currentAcct();
            }
            if (acct == null || acct.isBlank()) {
                acct = HiBAInitial.accountingServer;
            }
            if (acct == null || acct.isBlank()) {
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + acct + ":" + HiBAInitial.HTTP_REQUEST_PORT + "/api/wallets"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            logDelivery(response.statusCode(), response.body());
        } catch (Exception e) {
            logger.atDebug().log("Wallet attestation not delivered: {}", e.getMessage());
        }
    }

    private static volatile String lastDelivery = "";

    private static void logDelivery(int status, String detail) {
        String summary = status + " " + detail;
        if (!summary.equals(lastDelivery)) { // 結果有變才記錄，避免每 10 分鐘洗版
            lastDelivery = summary;
            if (status == 200) {
                logger.atInfo().log("Device wallet attestation accepted by accounting server");
            } else {
                logger.atWarn().log("Device wallet attestation rejected ({}): {}", status, detail);
            }
        }
    }

    // ============ accounting server：驗章並保存 ============

    /**
     * 驗證並保存一份錢包聲明。
     *
     * @return null 表示接受；否則為拒絕原因
     */
    public static String accept(JSONObject body, String remoteIp, Web3BlockchainService chain) {
        String ek = body.optString("ek", "");
        String wallet = body.optString("wallet", "");
        long timestamp = body.optLong("timestamp", 0);
        String signature = body.optString("signature", "");
        String name = body.optString("name", "");
        if (!ek.matches("[0-9A-F]{64}")) {
            return "EK 指紋格式不對";
        }
        if (!wallet.matches("0x[0-9a-fA-F]{40}")) {
            return "錢包位址格式不對";
        }
        if (Math.abs(System.currentTimeMillis() - timestamp) > MAX_CLOCK_SKEW_MS) {
            return "時間戳與 accounting server 相差超過 15 分鐘（請檢查對時）";
        }
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return "簽章不是 base64";
        }
        if (chain == null) {
            return "accounting server 沒有設定 TPMDeviceRegistry，無法驗章";
        }
        Optional<PublicKey> key = chain.getPublicKeyWithCache(ek);
        if (key.isEmpty()) {
            return "這個 EK 沒有在鏈上註冊，或已被撤銷";
        }
        byte[] data = message(ek, wallet, timestamp).getBytes(StandardCharsets.UTF_8);
        if (!TPMService.verifyWithJavaCrypto(key.get(), data, sig)) {
            return "TPM 簽章驗證失敗";
        }
        JSONObject prev = BY_EK.get(ek);
        if (prev != null && prev.optLong("timestamp") > timestamp) {
            return null; // 已有較新的聲明
        }
        if (prev == null && BY_EK.size() >= MAX_ENTRIES) {
            return "accounting server 保存的聲明已達上限";
        }
        BY_EK.put(ek, new JSONObject()
                .put("name", name)
                .put("ek", ek)
                .put("wallet", wallet.toLowerCase(Locale.ROOT))
                .put("timestamp", timestamp)
                .put("signature", signature)
                .put("ip", remoteIp == null ? JSONObject.NULL : remoteIp)
                .put("receivedAt", System.currentTimeMillis()));
        return null;
    }

    /** GET /api/wallets */
    public static JSONObject toJson() {
        List<JSONObject> list = new ArrayList<>(BY_EK.values());
        list.sort(Comparator.comparing(o -> o.optString("name")));
        return new JSONObject()
                .put("generatedAt", System.currentTimeMillis())
                .put("messageFormat", PREFIX + "<ek>:<wallet lowercase>:<timestamp>")
                .put("wallets", new JSONArray(list));
    }
}
