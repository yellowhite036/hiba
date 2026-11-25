package org.gslab.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.gslab.authorization.AuthorizationCertificate.ValidatorSignature;
import org.gslab.authorization.ValidatorRegistryClient.Validator;
import org.gslab.authorization.ValidatorRegistryClient.ValidatorSet;
import org.gslab.model.RequestId;
import org.gslab.sdns.HiBAInitial;
import org.gslab.tpm.TPMService;
import org.gslab.tpm.blockchain.Web3BlockchainService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M1 多 Validator 授權的離線測試：不需要 TPM、區塊鏈或網路。
 * <p>
 * 以軟體 RSA 金鑰模擬 3 台 Validator（V1–V3）、提出請求的 Broker（R）和一台已登記但不是 Validator 的設備（X），
 * 鏈上查詢用 mock 回傳。驗證演算法與實機相同（SHA256withRSA，TPMService.verifyWithJavaCrypto）。
 * 涵蓋實機難以測試的重放、竄改、送錯節點等情境（docs/M1_AUTHORIZATION_TEST.md 第 5 節）。
 * </p>
 */
class AuthorizationServiceTest {

    private static final String TARGET = "NewNode-141";
    private static final Map<String, KeyPair> KEYS = new HashMap<>();

    @TempDir
    Path tmp;

    private Web3BlockchainService chain;
    private ValidatorRegistryClient registry;
    private AuthorizationService service;
    private String savedNodeName;
    private TPMService savedTpm;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        for (String id : List.of("V1", "V2", "V3", "V4", "V5", "R", "X")) {
            KEYS.put(id, g.generateKeyPair());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        savedNodeName = HiBAInitial.nodeName;
        savedTpm = HiBAInitial.tpmService;
        HiBAInitial.nodeName = TARGET;

        chain = mock(Web3BlockchainService.class);
        // 只有登記過的設備查得到公鑰（V1–V3、R、X）
        when(chain.getPublicKeyWithCache(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(KEYS.get(inv.getArgument(0, String.class)))
                        .map(KeyPair::getPublic));

        registry = mock(ValidatorRegistryClient.class);
        setValidatorSet(4, 2, "V1", "V2", "V3");

        service = new AuthorizationService(config("enforce", List.of()), chain, registry);
        service.upperBrokerEk = () -> null; // 預設沒有上層 broker
    }

    @AfterEach
    void tearDown() {
        HiBAInitial.nodeName = savedNodeName;
        HiBAInitial.tpmService = savedTpm;
    }

    // =====================================================================
    // 執行節點端（verify）
    // =====================================================================

    @Test
    @DisplayName("正常：2/3 個 Validator 簽章 → 通過")
    void validCertificatePasses() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "V2");

        AuthorizationService.Verdict v = service.verify(task);

        assertThat(v.ok()).as(v.reason()).isTrue();
        assertThat(v.validSignatures()).isEqualTo(2);
        assertThat(v.required()).isEqualTo(2);
    }

    @Test
    @DisplayName("經過 Java 序列化（模擬網路傳送）後任務雜湊不變 → 通過")
    void survivesJavaSerialization() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "V2", "V3");

        AuthorizationService.Verdict v = service.verify(roundTrip(task));

        assertThat(v.ok()).as(v.reason()).isTrue();
        assertThat(v.validSignatures()).isEqualTo(3);
    }

    @Test
    @DisplayName("竄改任務內容（換檔名）→ 拒絕")
    void tamperedTaskIsRejected() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "V2", "V3");
        task.put("fileName", "secret.txt");

        assertRejected(service.verify(task), "task content does not match");
    }

    @Test
    @DisplayName("簽完章後竄改憑證欄位（服務名稱）→ 拒絕")
    void tamperedCertificateIsRejected() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "V2", "V3");
        certMap(task).put("serviceName", "BlockchainFileProtect");

        assertRejected(service.verify(task), "requester signature invalid");
    }

    @Test
    @DisplayName("竄改 Validator 簽章 → 不計入，低於門檻 → 拒絕")
    void tamperedValidatorSignatureIsNotCounted() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "V2");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> sigs = (List<Map<String, String>>) certMap(task).get("signatures");
        byte[] raw = Base64.getDecoder().decode(sigs.get(1).get("signature"));
        raw[10] ^= 0x01;
        sigs.get(1).put("signature", Base64.getEncoder().encodeToString(raw));

        AuthorizationService.Verdict v = service.verify(task);
        assertRejected(v, "only 1 valid validator signatures, need 2");
    }

    @Test
    @DisplayName("重放：同一張憑證送第二次 → 拒絕")
    void replayIsRejected() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "V2");

        assertThat(service.verify(roundTrip(task)).ok()).isTrue();
        assertRejected(service.verify(roundTrip(task)), "nonce already used");
    }

    @Test
    @DisplayName("送錯節點：給 141 的憑證送到 205 → 拒絕")
    void wrongTargetIsRejected() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "V2", "V3");
        HiBAInitial.nodeName = "NewNode-205";

        assertRejected(service.verify(task), "certificate is for NewNode-141, not NewNode-205");
    }

    @Test
    @DisplayName("不在名單的設備（已登記）簽章 → 不計入")
    void nonValidatorSignatureIsNotCounted() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "X");

        assertRejected(service.verify(task), "only 1 valid validator signatures, need 2");
    }

    @Test
    @DisplayName("同一個 Validator 的簽章放兩份 → 只算 1 個")
    void duplicateSignatureCountedOnce() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "V1");

        assertRejected(service.verify(task), "only 1 valid validator signatures, need 2");
    }

    @Test
    @DisplayName("名單已更新（v4 → v5）後使用舊憑證 → 拒絕")
    void staleValidatorSetIsRejected() throws Exception {
        Map<String, Object> task = task();
        attach(task, cert(task), "V1", "V2", "V3");
        setValidatorSet(5, 2, "V1", "V2");

        assertRejected(service.verify(task), "validator set version mismatch: certificate v4, current v5");
    }

    @Test
    @DisplayName("已過期的憑證 → 拒絕")
    void expiredIsRejected() throws Exception {
        Map<String, Object> task = task();
        AuthorizationCertificate c = cert(task);
        long now = System.currentTimeMillis();
        c.issuedAt = now - 600_000;
        c.expiresAt = now - 300_000;
        attach(task, c, "V1", "V2", "V3");

        assertRejected(service.verify(task), "certificate expired");
    }

    @Test
    @DisplayName("請求者未登記（鏈上查不到公鑰）→ 拒絕")
    void unregisteredRequesterIsRejected() throws Exception {
        Map<String, Object> task = task();
        AuthorizationCertificate c = cert(task);
        c.requesterId = "UNKNOWN";
        attach(task, c, "V1", "V2", "V3");

        assertRejected(service.verify(task), "requester signature invalid or requester not registered");
    }

    @Test
    @DisplayName("沒有附授權憑證 → 拒絕")
    void missingCertificateIsRejected() {
        assertRejected(service.verify(task()), "no authorization certificate");
    }

    @Test
    @DisplayName("mode=off → 一律通過（不檢查）")
    void offModeAlwaysPasses() throws Exception {
        AuthorizationService off = new AuthorizationService(AuthorizationConfig.off(), chain, null);
        assertThat(off.verify(task()).ok()).isTrue();
    }

    // =====================================================================
    // 以網域計算門檻（網域 A：V1–V3，網域 B：V4–V5，全網門檻 4）
    // =====================================================================

    @Test
    @DisplayName("網域：A 的節點只需 A 過半數（2/3），網域 B 斷線也通過")
    void domainMajorityPassesWhenOtherDomainOffline() throws Exception {
        twoDomains();
        actAsValidator("V1");
        Map<String, Object> task = task();
        attach(task, cert(task, 9), "V1", "V2");

        AuthorizationService.Verdict v = service.verify(task);

        assertThat(v.ok()).as(v.reason()).isTrue();
        assertThat(v.required()).isEqualTo(2);
        assertThat(v.reason()).contains("domainA");
    }

    @Test
    @DisplayName("網域：別的網域的簽章不算進自己網域的門檻")
    void otherDomainSignaturesDoNotCount() throws Exception {
        twoDomains();
        actAsValidator("V1");
        Map<String, Object> task = task();
        attach(task, cert(task, 9), "V4", "V5");

        assertRejected(service.verify(task), "only 0 valid validator signatures in domainA, need 2");
    }

    @Test
    @DisplayName("網域：B 只有 2 台時需要 2 台；只有 1 台簽 → 拒絕")
    void smallDomainNeedsBoth() throws Exception {
        twoDomains();
        actAsValidator("V4");
        Map<String, Object> task = task();
        attach(task, cert(task, 9), "V4", "V1", "V2", "V3");

        assertRejected(service.verify(task), "only 1 valid validator signatures in domainB, need 2");

        Map<String, Object> task2 = task();
        attach(task2, cert(task2, 9), "V4", "V5");
        assertThat(service.verify(task2).ok()).isTrue();
    }

    @Test
    @DisplayName("網域：斷線的 Validator 仍算在名單內（A 只有 1 台簽 → 拒絕，不會因為其他台沒回應而降低門檻）")
    void offlineValidatorsStillCount() throws Exception {
        twoDomains();
        actAsValidator("V1");
        Map<String, Object> task = task();
        attach(task, cert(task, 9), "V1");

        assertRejected(service.verify(task), "only 1 valid validator signatures in domainA, need 2");
    }

    @Test
    @DisplayName("網域：不是 Validator 的節點用 env.json 的 domainId")
    void nonValidatorUsesConfiguredDomain() throws Exception {
        twoDomains();
        AuthorizationService inB = new AuthorizationService(config("enforce", List.of(), "domainB"), chain, registry);
        Map<String, Object> task = task();
        attach(task, cert(task, 9), "V4", "V5");
        assertThat(inB.verify(task).ok()).isTrue();

        Map<String, Object> task2 = task();
        attach(task2, cert(task2, 9), "V1", "V2", "V3");
        assertRejected(inB.verify(task2), "in domainB, need 2");
    }

    @Test
    @DisplayName("網域：不知道自己屬於哪個網域時，退回全網門檻")
    void unknownDomainFallsBackToGlobalThreshold() throws Exception {
        twoDomains();
        Map<String, Object> task = task();
        attach(task, cert(task, 9), "V1", "V2", "V3");

        assertRejected(service.verify(task), "only 3 valid validator signatures, need 4");
    }

    @Test
    @DisplayName("網域：不是 Validator 的節點自動沿用上層 broker 的網域（上層是 V4 → domainB）")
    void nonValidatorInheritsUpperBrokerDomain() throws Exception {
        twoDomains();
        service.upperBrokerEk = () -> "V4";
        Map<String, Object> task = task();
        attach(task, cert(task, 9), "V4", "V5");
        AuthorizationService.Verdict v = service.verify(task);
        assertThat(v.ok()).as(v.reason()).isTrue();
        assertThat(v.reason()).contains("domainB");

        Map<String, Object> task2 = task();
        attach(task2, cert(task2, 9), "V1", "V2", "V3");
        assertRejected(service.verify(task2), "in domainB, need 2");
    }

    @Test
    @DisplayName("網域：上層 broker 的網域優先於 env.json；上層不是 Validator 時才用 env.json")
    void upperBrokerDomainOverridesConfig() throws Exception {
        twoDomains();
        AuthorizationService s = new AuthorizationService(config("enforce", List.of(), "domainB"), chain, registry);
        s.upperBrokerEk = () -> "V1";
        Map<String, Object> task = task();
        attach(task, cert(task, 9), "V1", "V2");
        AuthorizationService.Verdict v = s.verify(task);
        assertThat(v.ok()).as(v.reason()).isTrue();
        assertThat(v.reason()).contains("domainA");

        s.upperBrokerEk = () -> "X"; // 上層是登記過的設備，但不是 Validator
        Map<String, Object> task2 = task();
        attach(task2, cert(task2, 9), "V4", "V5");
        AuthorizationService.Verdict v2 = s.verify(task2);
        assertThat(v2.ok()).as(v2.reason()).isTrue();
        assertThat(v2.reason()).contains("domainB");
    }

    @Test
    @DisplayName("網域：4 台的網域（173、141、205、11）需要 3 台；2 台簽 → 拒絕")
    void fourValidatorDomainNeedsThree() throws Exception {
        setValidatorSet(10, 0, "V1", "V2", "V3", "V4");
        actAsValidator("V1");
        Map<String, Object> task = task();
        attach(task, cert(task, 10), "V1", "V2");
        assertRejected(service.verify(task), "only 2 valid validator signatures in domainA, need 3");

        Map<String, Object> task2 = task();
        attach(task2, cert(task2, 10), "V1", "V2", "V4");
        AuthorizationService.Verdict v = service.verify(task2);
        assertThat(v.ok()).as(v.reason()).isTrue();
        assertThat(v.required()).isEqualTo(3);
    }

    // =====================================================================
    // Validator 端（evaluateAndSign）
    // =====================================================================

    @Test
    @DisplayName("Validator：合法請求 → 簽章，且可用鏈上公鑰驗證")
    void validatorSignsValidRequest() throws Exception {
        actAsValidator("V1");
        AuthorizationCertificate c = cert(task());

        String sig = service.evaluateAndSign(c);

        assertThat(TPMService.verifyWithJavaCrypto(
                        KEYS.get("V1").getPublic(), c.signingPayload(), Base64.getDecoder().decode(sig)))
                .isTrue();
    }

    @Test
    @DisplayName("Validator：同一個請求要求簽第二次 → 拒簽")
    void validatorRefusesReplay() throws Exception {
        actAsValidator("V1");
        AuthorizationCertificate c = cert(task());
        service.evaluateAndSign(c);

        assertThatThrownBy(() -> service.evaluateAndSign(c)).hasMessageContaining("nonce already signed");
    }

    @Test
    @DisplayName("Validator：請求者簽章是偽造的 → 拒簽")
    void validatorRefusesForgedRequester() throws Exception {
        actAsValidator("V1");
        AuthorizationCertificate c = cert(task());
        c.requesterSignature = sign("X", c.signingPayload()); // 用別台設備的金鑰冒充 R

        assertThatThrownBy(() -> service.evaluateAndSign(c))
                .hasMessageContaining("requester signature invalid");
    }

    @Test
    @DisplayName("Validator：自己不在名單 → 拒簽")
    void nonValidatorRefusesToSign() throws Exception {
        actAsValidator("X");

        assertThatThrownBy(() -> service.evaluateAndSign(cert(task())))
                .hasMessageContaining("not an active validator");
    }

    @Test
    @DisplayName("Validator：服務不在 allowedServices → 拒簽")
    void validatorRefusesDisallowedService() throws Exception {
        actAsValidator("V1");
        AuthorizationService restricted = new AuthorizationService(
                config("enforce", List.of("BlockchainFileProtect")), chain, registry);

        assertThatThrownBy(() -> restricted.evaluateAndSign(cert(task())))
                .hasMessageContaining("service 'getFile' not allowed");
    }

    @Test
    @DisplayName("Validator：有效期過長 → 拒簽")
    void validatorRefusesLongValidity() throws Exception {
        actAsValidator("V1");
        AuthorizationCertificate c = cert(task());
        c.expiresAt = c.issuedAt + 3_600_000;
        c.requesterSignature = sign("R", c.signingPayload());

        assertThatThrownBy(() -> service.evaluateAndSign(c)).hasMessageContaining("validity period too long");
    }

    // =====================================================================
    // 任務雜湊
    // =====================================================================

    @Test
    @DisplayName("任務雜湊：與欄位順序無關，且不包含 authorization 欄位")
    void taskDigestIsCanonical() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("fileName", "f.txt");
        a.put("tier", 0);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("tier", 0);
        b.put("fileName", "f.txt");
        b.put(AuthorizationCertificate.TASK_KEY, Map.of("x", "y"));

        assertThat(AuthorizationCertificate.taskDigest(a)).isEqualTo(AuthorizationCertificate.taskDigest(b));
        a.put("tier", 1);
        assertThat(AuthorizationCertificate.taskDigest(a)).isNotEqualTo(AuthorizationCertificate.taskDigest(b));
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private AuthorizationConfig config(String mode, List<String> allowed) throws Exception {
        return config(mode, allowed, "");
    }

    private AuthorizationConfig config(String mode, List<String> allowed, String domainId) throws Exception {
        Path env = tmp.resolve("env-" + UUID.randomUUID() + ".json");
        String list = allowed.isEmpty() ? "" : "\"" + String.join("\",\"", allowed) + "\"";
        Files.writeString(
                env,
                "{\"Authorization\":{\"mode\":\"" + mode
                        + "\",\"validatorRegistry\":\"0x0000000000000000000000000000000000000001\","
                        + "\"certTtlSeconds\":300,\"domainId\":\"" + domainId + "\",\"allowedServices\":[" + list + "]}}");
        return AuthorizationConfig.load(env.toString());
    }

    private void setValidatorSet(long version, int threshold, String... ids) {
        Map<String, Validator> active = new LinkedHashMap<>();
        for (String id : ids) {
            active.put(id, new Validator(id, "domainA", "127.0.0.1:8095"));
        }
        when(registry.current())
                .thenReturn(Optional.of(new ValidatorSet(version, threshold, active, System.currentTimeMillis())));
    }

    /** 名單 v9：網域 A = V1–V3、網域 B = V4–V5，全網門檻 4 */
    private void twoDomains() {
        Map<String, Validator> active = new LinkedHashMap<>();
        for (String id : List.of("V1", "V2", "V3")) {
            active.put(id, new Validator(id, "domainA", ""));
        }
        for (String id : List.of("V4", "V5")) {
            active.put(id, new Validator(id, "domainB", ""));
        }
        when(registry.current()).thenReturn(Optional.of(new ValidatorSet(9, 4, active, System.currentTimeMillis())));
    }

    private static Map<String, Object> task() {
        Map<String, Object> t = new HashMap<>();
        t.put("requestId", RequestId.of(UUID.randomUUID().toString()));
        t.put("requestName", "getFile");
        t.put("serviceName", "getFile");
        t.put("nodeName", TARGET);
        t.put("tier", 0);
        t.put("fileName", "wallet_test.txt");
        t.put("files", new ArrayList<>(List.of("wallet_test.txt")));
        return t;
    }

    /** Broker（R）為任務建立的憑證，還沒有 Validator 簽章 */
    private static AuthorizationCertificate cert(Map<String, Object> task) throws Exception {
        return cert(task, 4);
    }

    private static AuthorizationCertificate cert(Map<String, Object> task, long version) throws Exception {
        long now = System.currentTimeMillis();
        AuthorizationCertificate c = new AuthorizationCertificate();
        c.certificateId = UUID.randomUUID().toString();
        c.requesterId = "R";
        c.requesterNode = "NewNode-173";
        c.targetNode = TARGET;
        c.requestId = String.valueOf(task.get("requestId"));
        c.requestName = "getFile";
        c.serviceName = "getFile";
        c.taskDigest = AuthorizationCertificate.taskDigest(task);
        c.nonce = UUID.randomUUID().toString();
        c.issuedAt = now;
        c.expiresAt = now + 300_000;
        c.validatorSetVersion = version;
        c.requiredThreshold = 2;
        c.requesterSignature = sign("R", c.signingPayload());
        return c;
    }

    /** 以目前欄位重新簽（請求者與指定的 Validator），放進任務 Map */
    private static void attach(Map<String, Object> task, AuthorizationCertificate c, String... validators)
            throws Exception {
        byte[] payload = c.signingPayload();
        c.requesterSignature = sign("R", payload);
        c.signatures.clear();
        for (String v : validators) {
            c.signatures.add(new ValidatorSignature(v, sign(v, payload)));
        }
        task.put(AuthorizationCertificate.TASK_KEY, c.toMap());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> certMap(Map<String, Object> task) {
        return (Map<String, Object>) task.get(AuthorizationCertificate.TASK_KEY);
    }

    private static String sign(String id, byte[] data) throws Exception {
        PrivateKey k = KEYS.get(id).getPrivate();
        Signature s = Signature.getInstance(TPMService.SIGNATURE_ALGORITHM);
        s.initSign(k);
        s.update(data);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    /** 讓 HiBAInitial.tpmService 以指定的軟體金鑰扮演這台的 TPM */
    private static void actAsValidator(String id) throws Exception {
        TPMService tpm = mock(TPMService.class);
        when(tpm.getEKFingerprint()).thenReturn(id);
        when(tpm.sign(any(byte[].class))).thenAnswer(inv -> {
            Signature s = Signature.getInstance(TPMService.SIGNATURE_ALGORITHM);
            s.initSign(KEYS.get(id).getPrivate());
            s.update(inv.getArgument(0, byte[].class));
            return s.sign();
        });
        HiBAInitial.tpmService = tpm;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> roundTrip(Map<String, Object> m) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(new HashMap<>(m));
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            return (Map<String, Object>) ois.readObject();
        }
    }

    private static void assertRejected(AuthorizationService.Verdict v, String reasonPart) {
        assertThat(v.ok()).as("should be rejected").isFalse();
        assertThat(v.reason()).contains(reasonPart);
    }
}
