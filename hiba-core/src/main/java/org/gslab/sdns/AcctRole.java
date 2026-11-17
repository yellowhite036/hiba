package org.gslab.sdns;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.gslab.mService.multicast.MulticastController;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * accounting server 由「最頂層的 broker」擔任。
 * <ul>
 * <li>根網段：env.json 的 {@code accountingServer} 位址所在的 /24（和 HiBA 找上層 broker 時的比對方式相同）。</li>
 * <li>根網段選出的 broker 就是 accounting server（{@link #isActing()}）：接受各 broker 的 acct 連線
 *     （{@link #acctPort()}）、回答 broker 清單、提供節點狀態頁。它離線時，根網段重新選出的 broker 自動接手。</li>
 * <li>相容舊架構：{@code accountingServer} 位址上若還跑著專用的 accounting server（isAccountingServer=true），
 *     就由它擔任，根網段的 broker 不代理（{@link #dedicatedAcctAlive()}）。</li>
 * <li>其他網段的節點用 {@link #currentAcct()} 找出目前的 accounting server：依序問設定的位址、
 *     自己的上層 broker，都不行再同時詢問根網段每個位址的 HTTP {@code /api/acct}。</li>
 * </ul>
 */
public final class AcctRole {

    private static final Logger logger = LoggerFactory.getLogger(AcctRole.class);

    private static final long CACHE_MS = 15_000;
    private static final long RETRY_MS = 15_000; // 找不到時多久後再找（掃描根網段很花時間）
    private static final long DEDICATED_CHECK_MS = 10_000;

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build();

    private static volatile boolean acting = false;

    private static volatile String cachedAcct;
    private static volatile long cachedAt = 0;
    private static volatile long lastAttempt = 0;

    private static volatile boolean dedicatedAlive = false;
    private static volatile long dedicatedCheckedAt = 0;

    private AcctRole() {}

    /** env.json 設定的 accounting server 位址（只當作根網段與第一個詢問對象） */
    public static String seed() {
        return HiBAInitial.accountingServer;
    }

    /** acct 連線用的 port：HTTP_REQUEST_PORT + 1（預設 8093），和 broker 連線分開 */
    public static int acctPort() {
        return HiBAInitial.HTTP_REQUEST_PORT + 1;
    }

    public static String prefixOf(String ip) {
        if (ip == null || !ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return null;
        }
        return ip.substring(0, ip.lastIndexOf('.'));
    }

    /** 本機在根網段（accounting server 位址所在的 /24） */
    public static boolean isRootTier() {
        String root = prefixOf(seed());
        return root != null && root.equals(prefixOf(HiBANetwork.LOCAL_IP));
    }

    public static boolean isActing() {
        return acting;
    }

    static void setActing(boolean value) {
        acting = value;
        if (value) {
            cachedAcct = HiBANetwork.LOCAL_IP;
            cachedAt = System.currentTimeMillis();
        } else {
            cachedAcct = null;
            cachedAt = 0;
        }
    }

    /** 找不到 / 連不上目前的 accounting server 時呼叫，下次 {@link #currentAcct()} 會重新找 */
    static synchronized void invalidate() {
        if (!acting) {
            cachedAcct = null;
            cachedAt = 0;
        }
    }

    /**
     * 不連網路，只用本機已知的資訊回答「目前的 accounting server」；HTTP /api/acct 用它，避免互相詢問造成連鎖等待。
     *
     * @return IP；不知道時回傳 null
     */
    public static String knownAcct() {
        if (acting) {
            return HiBANetwork.LOCAL_IP;
        }
        if (isRootTier()) {
            if (dedicatedAlive) {
                return seed();
            }
            String mode = MulticastController.getMode();
            if (("isClient".equals(mode) || "waiting".equals(mode)) && isIp(HiBANetwork.BROKER_IP)) {
                return HiBANetwork.BROKER_IP; // 根網段的節點：自己的 broker 就是 accounting server
            }
            return null;
        }
        String c = cachedAcct;
        return c != null && System.currentTimeMillis() - cachedAt < CACHE_MS * 4 ? c : null;
    }

    /**
     * 目前的 accounting server（會連網路查詢，可能要幾秒；結果快取 15 秒，查不到時 15 秒內不再重查）。
     *
     * @return IP；找不到時回傳 null
     */
    public static synchronized String currentAcct() {
        if (acting) {
            return HiBANetwork.LOCAL_IP;
        }
        long now = System.currentTimeMillis();
        if (isRootTier()) {
            return knownAcct() != null ? knownAcct() : (dedicatedAcctAlive() ? seed() : null);
        }
        if (cachedAcct != null && now - cachedAt < CACHE_MS) {
            return cachedAcct;
        }
        if (now - lastAttempt < RETRY_MS && cachedAcct == null) {
            return null;
        }
        lastAttempt = now;
        String found = resolve();
        if (found != null && !found.equals(cachedAcct)) {
            logger.atInfo().log("Accounting server is {}", found);
        }
        cachedAcct = found;
        cachedAt = found == null ? 0 : now;
        return found;
    }

    /** 由 HiBANetwork 提供：本機目前的上層 broker IP（沒有時回傳 null） */
    private static volatile Supplier<String> upperBroker = () -> null;

    static void setUpperBrokerSupplier(Supplier<String> supplier) {
        upperBroker = supplier;
    }

    private static String resolve() {
        String seed = seed();
        String answer = ask(seed);
        if (answer != null) {
            return answer;
        }
        // 上層 broker 一定知道目前的 accounting server（它自己就是，或它已經找到了）；本機是一般節點時問自己的 broker
        for (String host : new String[] {upperBroker.get(), HiBANetwork.BROKER_IP}) {
            if (isIp(host) && !host.equals(seed) && !host.equals(HiBANetwork.LOCAL_IP)) {
                answer = ask(host);
                if (answer != null) {
                    return answer;
                }
            }
        }
        String root = prefixOf(seed);
        if (root == null) {
            return null;
        }
        logger.atInfo().log("Accounting server {} unreachable, scanning {}.0/24 for the current one", seed, root);
        return scan(root);
    }

    /**
     * 同時向根網段 x.1～x.254 的 /api/acct 詢問（不需要 nmap / sudo）；
     * 優先採用「我就是 accounting server」的回答，沒有才用其他節點轉述的。
     */
    private static String scan(String prefix) {
        List<CompletableFuture<String[]>> pending = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            String host = prefix + "." + i;
            if (host.equals(seed()) || host.equals(HiBANetwork.LOCAL_IP)) {
                continue;
            }
            pending.add(queryAsync(host).thenApply(info -> info == null ? null : new String[] {host, info.toString()}));
        }
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 逾時：用已經回來的結果
        }
        String relayed = null;
        for (CompletableFuture<String[]> f : pending) {
            String[] r = f.getNow(null);
            if (r == null) {
                continue;
            }
            JSONObject info = new JSONObject(r[1]);
            String mode = info.optString("mode");
            if ("dedicated".equals(mode) || "acting".equals(mode)) {
                return r[0];
            }
            String acct = info.optString("acct", "");
            if (relayed == null && isIp(acct) && !acct.equals(HiBANetwork.LOCAL_IP)) {
                relayed = acct;
            }
        }
        return relayed;
    }

    private static CompletableFuture<JSONObject> queryAsync(String host) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://" + host + ":" + HiBAInitial.HTTP_REQUEST_PORT + "/api/acct"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() == 200 ? new JSONObject(r.body()) : null)
                .exceptionally(e -> null);
    }

    /** 設定的位址上是否還跑著專用的 accounting server（舊架構）；結果快取 10 秒 */
    public static boolean dedicatedAcctAlive() {
        String seed = seed();
        if (seed == null || seed.isBlank() || seed.equals(HiBANetwork.LOCAL_IP)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - dedicatedCheckedAt < DEDICATED_CHECK_MS) {
            return dedicatedAlive;
        }
        dedicatedCheckedAt = now;
        JSONObject info = query(seed);
        boolean alive = info != null && "dedicated".equals(info.optString("mode"));
        if (alive != dedicatedAlive) {
            logger.atInfo().log("Dedicated accounting server {} is {}", seed, alive ? "online" : "offline");
        }
        dedicatedAlive = alive;
        return alive;
    }

    /** 向 host 的 /api/acct 詢問目前的 accounting server */
    private static String ask(String host) {
        JSONObject info = query(host);
        if (info == null) {
            return null;
        }
        String mode = info.optString("mode");
        if ("dedicated".equals(mode) || "acting".equals(mode)) {
            return host;
        }
        String acct = info.optString("acct", "");
        return isIp(acct) && !acct.equals(HiBANetwork.LOCAL_IP) ? acct : null;
    }

    private static JSONObject query(String host) {
        if (!isIp(host)) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + host + ":" + HiBAInitial.HTTP_REQUEST_PORT + "/api/acct"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? new JSONObject(response.body()) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isIp(String s) {
        return s != null && s.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    /** HTTP GET /api/acct 的內容 */
    public static JSONObject describe(boolean dedicated) {
        JSONObject o = new JSONObject();
        if (dedicated) {
            return o.put("mode", "dedicated").put("acct", JSONObject.NULL);
        }
        if (acting) {
            return o.put("mode", "acting").put("acct", HiBANetwork.LOCAL_IP).put("name", HiBAInitial.nodeName);
        }
        String known = knownAcct();
        return o.put("mode", "node")
                .put("acct", known == null ? JSONObject.NULL : known)
                .put("rootTier", isRootTier());
    }
}
