package org.gslab.authorization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 授權憑證：說明「哪台 Broker 要求哪台節點執行哪個任務內容」，並帶著多個 Validator 的 TPM 簽章。
 * <p>
 * 放在 TaskStartMessage 任務 Map 的 {@link #TASK_KEY} 欄位裡（只用 String／Long／List／Map，
 * 舊版節點的 TaskAssignment.fromMap 會忽略這個欄位，所以新舊版可以混用）。
 * </p>
 * <p>
 * 被簽的內容是 {@link #signingPayload()}：固定順序、以換行分隔的欄位，不含任何簽章。
 * 任務內容用 {@link #taskDigest(Map)} 算出的 SHA-256 綁定，改了任何任務欄位憑證就失效。
 * </p>
 */
public final class AuthorizationCertificate {

    public static final String TASK_KEY = "authorization";
    static final String PAYLOAD_VERSION = "HIBA-AUTH-V1";

    String certificateId;
    String requesterId; // 提出請求的 Broker 的 EK fingerprint
    String requesterNode;
    String targetNode; // 要執行任務的節點名稱
    String requestId;
    String requestName;
    String serviceName;
    String taskDigest;
    String nonce;
    long issuedAt;
    long expiresAt;
    long validatorSetVersion;
    int requiredThreshold;
    String requesterSignature = "";
    final List<ValidatorSignature> signatures = new ArrayList<>();

    public record ValidatorSignature(String validatorId, String signature) {}

    // ---------------- 被簽內容 ----------------

    /** Validator 和 requester 都簽這一段 */
    public byte[] signingPayload() {
        String s = String.join(
                "\n",
                PAYLOAD_VERSION,
                certificateId,
                requesterId,
                requesterNode,
                targetNode,
                requestId,
                requestName,
                serviceName,
                taskDigest,
                nonce,
                Long.toString(issuedAt),
                Long.toString(expiresAt),
                Long.toString(validatorSetVersion),
                Integer.toString(requiredThreshold));
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 任務內容的 SHA-256（hex）。排除 {@link #TASK_KEY}；Map 依鍵排序，值保留型別名稱，
     * 讓 Java 序列化送到對方後重算的結果相同。
     */
    public static String taskDigest(Map<String, Object> taskMap) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> copy = new TreeMap<>();
        for (Map.Entry<String, Object> e : taskMap.entrySet()) {
            if (!TASK_KEY.equals(e.getKey())) {
                copy.put(e.getKey(), e.getValue());
            }
        }
        canonical(copy, sb);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void canonical(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            Map<String, Object> sorted = new TreeMap<>();
            m.forEach((k, val) -> sorted.put(String.valueOf(k), val));
            sb.append('{');
            sorted.forEach((k, val) -> {
                sb.append(quote(k)).append(':');
                canonical(val, sb);
                sb.append(',');
            });
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            for (Object o : it) {
                canonical(o, sb);
                sb.append(',');
            }
            sb.append(']');
        } else if (v instanceof CharSequence cs) {
            sb.append(quote(cs.toString()));
        } else {
            sb.append(v.getClass().getSimpleName()).append('(').append(quote(String.valueOf(v))).append(')');
        }
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    // ---------------- Map（放進任務）／JSON（HTTP） ----------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("certificateId", certificateId);
        m.put("requesterId", requesterId);
        m.put("requesterNode", requesterNode);
        m.put("targetNode", targetNode);
        m.put("requestId", requestId);
        m.put("requestName", requestName);
        m.put("serviceName", serviceName);
        m.put("taskDigest", taskDigest);
        m.put("nonce", nonce);
        m.put("issuedAt", issuedAt);
        m.put("expiresAt", expiresAt);
        m.put("validatorSetVersion", validatorSetVersion);
        m.put("requiredThreshold", (long) requiredThreshold);
        m.put("requesterSignature", requesterSignature);
        List<Map<String, String>> sigs = new ArrayList<>();
        for (ValidatorSignature s : signatures) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("validatorId", s.validatorId());
            e.put("signature", s.signature());
            sigs.add(e);
        }
        m.put("signatures", sigs);
        return m;
    }

    public static AuthorizationCertificate fromMap(Map<?, ?> m) {
        AuthorizationCertificate c = new AuthorizationCertificate();
        c.certificateId = str(m.get("certificateId"));
        c.requesterId = str(m.get("requesterId"));
        c.requesterNode = str(m.get("requesterNode"));
        c.targetNode = str(m.get("targetNode"));
        c.requestId = str(m.get("requestId"));
        c.requestName = str(m.get("requestName"));
        c.serviceName = str(m.get("serviceName"));
        c.taskDigest = str(m.get("taskDigest"));
        c.nonce = str(m.get("nonce"));
        c.issuedAt = num(m.get("issuedAt"));
        c.expiresAt = num(m.get("expiresAt"));
        c.validatorSetVersion = num(m.get("validatorSetVersion"));
        c.requiredThreshold = (int) num(m.get("requiredThreshold"));
        c.requesterSignature = str(m.get("requesterSignature"));
        Object sigs = m.get("signatures");
        if (sigs instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o instanceof Map<?, ?> e) {
                    c.signatures.add(new ValidatorSignature(str(e.get("validatorId")), str(e.get("signature"))));
                }
            }
        }
        return c;
    }

    /** HTTP 請求用（不含 validator 簽章） */
    public JSONObject toRequestJson() {
        JSONObject j = new JSONObject();
        toMap().forEach((k, v) -> {
            if (!"signatures".equals(k)) {
                j.put(k, v);
            }
        });
        return j;
    }

    public static AuthorizationCertificate fromRequestJson(JSONObject j) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : j.keySet()) {
            Object v = j.get(k);
            m.put(k, v instanceof JSONArray a ? a.toList() : v);
        }
        m.remove("signatures");
        return fromMap(m);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0 : Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------- getters ----------------

    public String getCertificateId() {
        return certificateId;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public String getTargetNode() {
        return targetNode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getTaskDigest() {
        return taskDigest;
    }

    public String getNonce() {
        return nonce;
    }

    public long getIssuedAt() {
        return issuedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public long getValidatorSetVersion() {
        return validatorSetVersion;
    }

    public int getRequiredThreshold() {
        return requiredThreshold;
    }

    public List<ValidatorSignature> getSignatures() {
        return signatures;
    }

    public String shortId() {
        return certificateId == null || certificateId.length() < 8 ? certificateId : certificateId.substring(0, 8);
    }
}
