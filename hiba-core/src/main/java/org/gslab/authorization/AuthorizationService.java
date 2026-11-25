package org.gslab.authorization;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.gslab.authorization.AuthorizationCertificate.ValidatorSignature;
import org.gslab.authorization.ValidatorRegistryClient.Validator;
import org.gslab.authorization.ValidatorRegistryClient.ValidatorSet;
import org.gslab.sdns.HiBAInitial;
import org.gslab.tpm.TPMService;
import org.gslab.tpm.blockchain.Web3BlockchainService;
import org.json.JSONObject;

/**
 * M1 多 Validator 授權。
 *
 * <pre>
 * Broker（分派任務）  ──AuthSign HTTP──▶  各 Validator：獨立檢查後用 TPM 簽章
 *        │  收集簽章，把憑證放進 TaskStartMessage 任務 Map 的 "authorization"
 *        ▼
 * Worker（執行任務）：自己向鏈上確認 Validator 名單、門檻、每個簽章、任務內容、期限、nonce
 * </pre>
 *
 * Validator 名單在鏈上的 ValidatorRegistry，公鑰沿用 TPMDeviceRegistry；這裡不信任任何中央伺服器。
 * mode=log 時只記錄結果，mode=enforce 時驗證不過就不執行任務。
 */
@Slf4j
public final class AuthorizationService {

    private static final long CLOCK_SKEW_MS = 60_000;
    private static volatile AuthorizationService instance;

    private final AuthorizationConfig config;
    private final Web3BlockchainService chain;
    private final ValidatorRegistryClient registry;
    private final HttpClient http;
    /** worker 端用過的憑證 nonce → 到期時間 */
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();
    /** validator 端簽過的憑證 nonce → 到期時間 */
    private final Map<String, Long> signedNonces = new ConcurrentHashMap<>();
    private ValidatorSignServer server;
    /** 上層 broker 的 EK（非 Validator 節點用來自動決定網域）；測試可替換 */
    Supplier<String> upperBrokerEk = () -> SignRelay.get().upperBrokerEk();
    /** 上次決定的網域與來源（變動時記 log 一次） */
    private volatile String lastDomainNote = "";

    public record Verdict(boolean ok, String reason, int validSignatures, int required) {}

    private AuthorizationService(AuthorizationConfig config, Web3BlockchainService chain) {
        this(config, chain, config.isEnabled() ? new ValidatorRegistryClient(chain, config.getValidatorRegistry()) : null);
    }

    /** 測試用：可換上假的鏈與名單（不啟動簽章服務、不設成全域實例） */
    AuthorizationService(AuthorizationConfig config, Web3BlockchainService chain, ValidatorRegistryClient registry) {
        this.config = config;
        this.chain = chain;
        this.registry = registry;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getSignTimeoutMs()))
                .build();
    }

    /** 啟動時呼叫一次（HiBANetwork 建好 TPMDeviceRegistry 服務之後） */
    public static synchronized void init(Web3BlockchainService chain) {
        if (instance != null) {
            return;
        }
        AuthorizationConfig cfg = AuthorizationConfig.load("./env.json");
        AuthorizationService s = new AuthorizationService(cfg, chain);
        instance = s;
        if (!cfg.isEnabled()) {
            log.debug("[Auth] Multi-validator authorization is off");
            return;
        }
        log.info("[Auth] Multi-validator authorization enabled: {}", cfg);
        s.server = new ValidatorSignServer(s, cfg.getValidatorPort());
        s.server.start();
    }

    /** 尚未 init 時回傳關閉狀態的實例（不會做任何事） */
    public static AuthorizationService get() {
        AuthorizationService s = instance;
        return s != null ? s : Disabled.INSTANCE;
    }

    private static final class Disabled {
        static final AuthorizationService INSTANCE = new AuthorizationService(AuthorizationConfig.off(), null);
    }

    public AuthorizationConfig.Mode mode() {
        return config.getMode();
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    // =====================================================================
    // Broker 端：分派任務前收集簽章
    // =====================================================================

    /**
     * 為送往 targetNode 的任務收集 Validator 簽章，把憑證放進 taskMap 的 "authorization"。
     * 要在 taskMap 最後一次修改之後、送出之前呼叫。失敗不會丟例外，只是不附憑證或簽章不足。
     */
    public void attach(
            Map<String, Object> taskMap, String targetNode, Object requestId, String requestName, String serviceName) {
        if (!isEnabled() || taskMap == null) {
            return;
        }
        try {
            Optional<ValidatorSet> setOpt = registry.current();
            if (setOpt.isEmpty()) {
                log.warn("[Auth] Validator set unavailable, task for {} sent without authorization", targetNode);
                return;
            }
            ValidatorSet set = setOpt.get();
            TPMService tpm = HiBAInitial.tpmService;
            if (tpm == null) {
                log.warn("[Auth] No TPM service, cannot sign authorization request");
                return;
            }
            long now = System.currentTimeMillis();
            AuthorizationCertificate cert = new AuthorizationCertificate();
            cert.certificateId = UUID.randomUUID().toString();
            cert.requesterId = tpm.getEKFingerprint();
            cert.requesterNode = String.valueOf(HiBAInitial.nodeName);
            cert.targetNode = String.valueOf(targetNode);
            cert.requestId = String.valueOf(requestId);
            cert.requestName = String.valueOf(requestName);
            cert.serviceName = String.valueOf(serviceName);
            cert.taskDigest = AuthorizationCertificate.taskDigest(taskMap);
            cert.nonce = UUID.randomUUID().toString();
            cert.issuedAt = now;
            cert.expiresAt = now + config.getCertTtlSeconds() * 1000L;
            cert.validatorSetVersion = set.version();
            cert.requiredThreshold = set.threshold();
            byte[] payload = cert.signingPayload();
            cert.requesterSignature = Base64.getEncoder().encodeToString(tpm.sign(payload));

            String body = cert.toRequestJson().toString();
            List<Validator> vs = new ArrayList<>(set.active().values());
            Set<String> seen = new HashSet<>();
            List<String> notes = new ArrayList<>();
            AuthorizationConfig.Transport transport = config.getSignTransport();

            // 1. 沿 HiBA 既有連線轉送（預設）。每個簽章都要是名單上的 Validator，且能用鏈上公鑰驗證
            if (transport != AuthorizationConfig.Transport.HTTP) {
                SignRelay.Collected c = SignRelay.get().collect(cert.certificateId, body, config.getSignTimeoutMs());
                for (ValidatorSignature sig : c.signatures()) {
                    if (set.contains(sig.validatorId())
                            && !seen.contains(sig.validatorId())
                            && verifySignature(sig.validatorId(), payload, sig.signature())) {
                        seen.add(sig.validatorId());
                        cert.signatures.add(sig);
                    } else if (!set.contains(sig.validatorId()) || !seen.contains(sig.validatorId())) {
                        log.warn("[Auth] Dropped invalid relayed signature claiming validator {}", sig.validatorId());
                    }
                }
                notes.addAll(c.notes());
            }

            // 2. 直接 HTTP（transport=http，或 both 時補上還缺的 Validator）
            if (transport != AuthorizationConfig.Transport.NETTY) {
                List<CompletableFuture<Optional<ValidatorSignature>>> futures = new ArrayList<>();
                for (Validator v : vs) {
                    if (!seen.contains(v.ekFingerprint())) {
                        futures.add(requestSignature(v, body, payload));
                    }
                }
                if (!futures.isEmpty()) {
                    try {
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                .get(config.getSignTimeoutMs(), TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        log.debug("[Auth] Some validators did not answer within {} ms", config.getSignTimeoutMs());
                    }
                    for (CompletableFuture<Optional<ValidatorSignature>> f : futures) {
                        if (f.isDone() && !f.isCompletedExceptionally()) {
                            f.getNow(Optional.empty()).ifPresent(sig -> {
                                if (seen.add(sig.validatorId())) {
                                    cert.signatures.add(sig);
                                }
                            });
                        }
                    }
                }
            }
            taskMap.put(AuthorizationCertificate.TASK_KEY, cert.toMap());
            // 各網域收到的有效簽章（執行節點以「自己所屬網域」的過半數判斷）
            Map<String, Integer> byDomain = new java.util.TreeMap<>();
            for (Validator v : vs) {
                byDomain.putIfAbsent(v.domainId(), 0);
            }
            for (ValidatorSignature sig : cert.signatures) {
                byDomain.merge(set.domainOf(sig.validatorId()), 1, Integer::sum);
            }
            StringBuilder summary = new StringBuilder();
            boolean anyMajority = false;
            for (Map.Entry<String, Integer> e : byDomain.entrySet()) {
                int need = set.domainMajority(e.getKey());
                anyMajority |= e.getValue() >= need && need > 0;
                if (summary.length() > 0) {
                    summary.append(", ");
                }
                summary.append(e.getKey())
                        .append(' ')
                        .append(e.getValue())
                        .append('/')
                        .append(set.domainSize(e.getKey()))
                        .append(" (need ")
                        .append(need)
                        .append(')');
            }
            if (anyMajority) {
                log.info(
                        "[Auth] Certificate {} for {} ({}): {} (set v{})",
                        cert.shortId(), targetNode, serviceName, summary, set.version());
            } else {
                log.warn(
                        "[Auth] Certificate {} for {} ({}) has no domain majority: {} (set v{}){}",
                        cert.shortId(), targetNode, serviceName, summary, set.version(),
                        notes.isEmpty() ? "" : " — " + String.join("; ", notes));
            }
        } catch (Exception e) {
            log.warn("[Auth] Failed to build authorization for {}: {}", targetNode, e.getMessage());
        }
    }

    private CompletableFuture<Optional<ValidatorSignature>> requestSignature(Validator v, String body, byte[] payload) {
        if (v.endpoint() == null || v.endpoint().isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create("http://" + v.endpoint() + ValidatorSignServer.PATH))
                    .timeout(Duration.ofMillis(config.getSignTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (Exception e) {
            log.warn("[Auth] Bad validator endpoint '{}': {}", v.endpoint(), e.getMessage());
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> {
                    if (err != null) {
                        log.info("[Auth] Validator {} unreachable: {}", v.endpoint(), rootMessage(err));
                        return Optional.<ValidatorSignature>empty();
                    }
                    try {
                        JSONObject j = new JSONObject(resp.body());
                        if (resp.statusCode() != 200) {
                            log.info("[Auth] Validator {} refused: {}", v.endpoint(), j.optString("error", resp.body()));
                            return Optional.<ValidatorSignature>empty();
                        }
                        String id = j.optString("validatorId");
                        String sig = j.optString("signature");
                        // 回應的身分必須是名單上這個 endpoint 對應的 EK，而且簽章要能用鏈上公鑰驗過
                        if (!v.ekFingerprint().equals(id) || !verifySignature(id, payload, sig)) {
                            log.warn("[Auth] Validator {} returned an invalid signature", v.endpoint());
                            return Optional.<ValidatorSignature>empty();
                        }
                        return Optional.of(new ValidatorSignature(id, sig));
                    } catch (Exception e) {
                        log.info("[Auth] Bad response from validator {}: {}", v.endpoint(), e.getMessage());
                        return Optional.<ValidatorSignature>empty();
                    }
                });
    }

    // =====================================================================
    // Validator 端：獨立檢查後簽章（ValidatorSignServer 呼叫）
    // =====================================================================

    /**
     * @return 簽章（Base64）
     * @throws Rejection 不簽的理由
     */
    String evaluateAndSign(AuthorizationCertificate cert) throws Rejection {
        TPMService tpm = HiBAInitial.tpmService;
        if (tpm == null) {
            throw new Rejection("validator has no TPM");
        }
        String self;
        try {
            self = tpm.getEKFingerprint();
        } catch (Exception e) {
            throw new Rejection("cannot read own EK: " + e.getMessage());
        }
        ValidatorSet set = registry.current().orElseThrow(() -> new Rejection("validator set unavailable"));
        if (!set.contains(self)) {
            throw new Rejection("this node is not an active validator (set v" + set.version() + ")");
        }
        if (cert.validatorSetVersion != set.version()) {
            throw new Rejection("validator set version mismatch: request v" + cert.validatorSetVersion + ", current v"
                    + set.version());
        }
        long now = System.currentTimeMillis();
        if (cert.expiresAt <= now) {
            throw new Rejection("request already expired");
        }
        if (cert.issuedAt > now + CLOCK_SKEW_MS) {
            throw new Rejection("issuedAt is in the future");
        }
        if (cert.expiresAt - cert.issuedAt > config.getCertTtlSeconds() * 2000L) {
            throw new Rejection("validity period too long");
        }
        if (cert.taskDigest == null || cert.taskDigest.length() != 64) {
            throw new Rejection("missing task digest");
        }
        if (!config.getAllowedServices().isEmpty() && !config.getAllowedServices().contains(cert.serviceName)) {
            throw new Rejection("service '" + cert.serviceName + "' not allowed by this validator");
        }
        // 提出請求的 Broker 必須是鏈上登記、未撤銷的設備，且請求確實是它用 TPM 簽的
        byte[] payload = cert.signingPayload();
        if (!verifySignature(cert.requesterId, payload, cert.requesterSignature)) {
            throw new Rejection("requester signature invalid or requester not registered");
        }
        purge(signedNonces);
        if (signedNonces.putIfAbsent(cert.nonce, cert.expiresAt + CLOCK_SKEW_MS) != null) {
            throw new Rejection("nonce already signed (replay)");
        }
        try {
            String sig = Base64.getEncoder().encodeToString(tpm.sign(payload));
            log.info(
                    "[Auth] Signed certificate {} from {} for {} ({})",
                    cert.shortId(), cert.requesterNode, cert.targetNode, cert.serviceName);
            return sig;
        } catch (Exception e) {
            signedNonces.remove(cert.nonce);
            throw new Rejection("TPM signing failed: " + e.getMessage());
        }
    }

    String selfIdOrEmpty() {
        try {
            return HiBAInitial.tpmService != null ? HiBAInitial.tpmService.getEKFingerprint() : "";
        } catch (Exception e) {
            return "";
        }
    }

    static final class Rejection extends Exception {
        Rejection(String reason) {
            super(reason);
        }
    }

    // =====================================================================
    // Worker 端：執行前自行驗證
    // =====================================================================

    /** 驗證任務 Map 裡的授權憑證（mode=off 時一律通過） */
    public Verdict verify(Map<String, Object> taskMap) {
        if (!isEnabled()) {
            return new Verdict(true, "authorization off", 0, 0);
        }
        Object raw = taskMap == null ? null : taskMap.get(AuthorizationCertificate.TASK_KEY);
        if (!(raw instanceof Map<?, ?> m)) {
            return new Verdict(false, "no authorization certificate", 0, 0);
        }
        AuthorizationCertificate cert = AuthorizationCertificate.fromMap(m);
        Optional<ValidatorSet> setOpt = registry.current();
        if (setOpt.isEmpty()) {
            return new Verdict(false, "validator set unavailable", 0, 0);
        }
        ValidatorSet set = setOpt.get();
        // 門檻以「這個節點所屬網域」計算：該網域 Active Validator 的過半數。
        // 斷線的 Validator 仍算在名單內（不能因為收不到回應就降低門檻）；計畫性離線請用 suspend。
        String domain = targetDomain(set);
        int required = domain != null ? set.domainMajority(domain) : set.threshold();
        String scope = domain != null ? " in " + domain : "";
        if (required <= 0) {
            return new Verdict(false, "no active validators" + scope + " on chain", 0, 0);
        }
        String self = String.valueOf(HiBAInitial.nodeName);
        if (!self.equals(cert.targetNode)) {
            return new Verdict(false, "certificate is for " + cert.targetNode + ", not " + self, 0, required);
        }
        long now = System.currentTimeMillis();
        if (now > cert.expiresAt + CLOCK_SKEW_MS) {
            return new Verdict(false, "certificate expired", 0, required);
        }
        if (cert.issuedAt > now + CLOCK_SKEW_MS) {
            return new Verdict(false, "certificate issuedAt is in the future", 0, required);
        }
        if (cert.validatorSetVersion != set.version()) {
            return new Verdict(
                    false,
                    "validator set version mismatch: certificate v" + cert.validatorSetVersion + ", current v"
                            + set.version(),
                    0,
                    required);
        }
        if (!AuthorizationCertificate.taskDigest(taskMap).equals(cert.taskDigest)) {
            return new Verdict(false, "task content does not match certificate", 0, required);
        }
        byte[] payload = cert.signingPayload();
        if (!verifySignature(cert.requesterId, payload, cert.requesterSignature)) {
            return new Verdict(false, "requester signature invalid or requester not registered", 0, required);
        }
        Set<String> counted = new HashSet<>();
        for (ValidatorSignature s : cert.signatures) {
            if (set.contains(s.validatorId())
                    && (domain == null || domain.equals(set.domainOf(s.validatorId())))
                    && !counted.contains(s.validatorId())
                    && verifySignature(s.validatorId(), payload, s.signature())) {
                counted.add(s.validatorId());
            }
        }
        int valid = counted.size();
        if (valid < required) {
            return new Verdict(
                    false,
                    "only " + valid + " valid validator signatures" + scope + ", need " + required,
                    valid,
                    required);
        }
        purge(usedNonces);
        if (usedNonces.putIfAbsent(cert.nonce, cert.expiresAt + CLOCK_SKEW_MS) != null) {
            return new Verdict(false, "certificate nonce already used (replay)", valid, required);
        }
        return new Verdict(true, "ok (certificate " + cert.shortId() + scope + ")", valid, required);
    }

    /**
     * 這個節點所屬的網域，依序：
     * <ol>
     * <li>自己是 Validator：鏈上名單登記的網域</li>
     * <li>不是 Validator：上層 broker 的網域（自動；上層的 EK 由連線的 TPM 簽章得知，網域查鏈上名單）</li>
     * <li>env.json 的 Authorization.domainId（手動指定，只在上面兩種都查不到時使用）</li>
     * </ol>
     * 都沒有時回傳 null（退回全網門檻）。
     */
    String targetDomain(ValidatorSet set) {
        String domain = set.domainOf(selfIdOrEmpty());
        String source = "own validator entry";
        if (isEmpty(domain)) {
            String upper = null;
            try {
                upper = upperBrokerEk.get();
            } catch (RuntimeException e) {
                log.debug("[Auth] Cannot read upper broker EK: {}", e.getMessage());
            }
            domain = set.domainOf(upper);
            source = "upper broker " + (upper == null ? "" : upper.substring(0, Math.min(16, upper.length())) + "…");
        }
        if (isEmpty(domain)) {
            domain = config.getDomainId();
            source = "env.json Authorization.domainId";
        }
        if (isEmpty(domain)) {
            domain = null;
            source = "none, using global threshold";
        }
        String note = domain + " (" + source + ")";
        if (!note.equals(lastDomainNote)) {
            lastDomainNote = note;
            log.info("[Auth] This node's domain: {}", note);
        }
        return domain;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    // =====================================================================

    private boolean verifySignature(String ekFingerprint, byte[] payload, String signatureB64) {
        if (ekFingerprint == null || ekFingerprint.isEmpty() || signatureB64 == null || signatureB64.isEmpty()) {
            return false;
        }
        try {
            Optional<PublicKey> pk = chain.getPublicKeyWithCache(ekFingerprint);
            return pk.isPresent()
                    && TPMService.verifyWithJavaCrypto(pk.get(), payload, Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            log.debug("[Auth] Signature check failed for {}: {}", ekFingerprint, e.getMessage());
            return false;
        }
    }

    private static void purge(Map<String, Long> nonces) {
        if (nonces.size() > 1000) {
            long now = System.currentTimeMillis();
            nonces.values().removeIf(exp -> exp < now);
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }
}
