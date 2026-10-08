package org.gslab.message_hiba;

/**
 * 包含 TPM 簽章的訊息包裝器
 * <p>
 * 此類別將原始 Message 與 TPM 簽章資訊一起封裝，
 * 用於在網路傳輸時驗證訊息的完整性和來源。
 * </p>
 *
 * @author TPM Integration
 */
public class SignedMessage extends Message {

    private static final long serialVersionUID = 1L;

    /**
     * 原始訊息的序列化 bytes（Base64 編碼）
     */
    private String messageData;

    /**
     * 原始訊息的類別名稱，用於反序列化
     */
    private String messageClassName;

    /**
     * TPM 簽章（Base64 編碼）
     */
    private String signature;

    /**
     * TPM 公鑰（Base64 編碼）
     */
    private String publicKey;

    /**
     * 簽章演算法
     */
    private String signatureAlgorithm;

    /**
     * 時間戳記（毫秒）
     */
    private long timestamp;

    /**
     * Nonce（防止重放攻擊）
     */
    private String nonce;

    /**
     * TPM 設備 ID（EK Fingerprint）
     */
    private String deviceId;

    /**
     * TPM 版本
     */
    private String tpmVersion;

    public SignedMessage() {
        super();
    }

    // Getters and Setters

    public String getMessageData() {
        return messageData;
    }

    public SignedMessage setMessageData(String messageData) {
        this.messageData = messageData;
        return this;
    }

    public String getMessageClassName() {
        return messageClassName;
    }

    public SignedMessage setMessageClassName(String messageClassName) {
        this.messageClassName = messageClassName;
        return this;
    }

    public String getSignature() {
        return signature;
    }

    public SignedMessage setSignature(String signature) {
        this.signature = signature;
        return this;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public SignedMessage setPublicKey(String publicKey) {
        this.publicKey = publicKey;
        return this;
    }

    public String getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    public SignedMessage setSignatureAlgorithm(String signatureAlgorithm) {
        this.signatureAlgorithm = signatureAlgorithm;
        return this;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public SignedMessage setTimestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public String getNonce() {
        return nonce;
    }

    public SignedMessage setNonce(String nonce) {
        this.nonce = nonce;
        return this;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public SignedMessage setDeviceId(String deviceId) {
        this.deviceId = deviceId;
        return this;
    }

    public String getTpmVersion() {
        return tpmVersion;
    }

    public SignedMessage setTpmVersion(String tpmVersion) {
        this.tpmVersion = tpmVersion;
        return this;
    }

    @Override
    public String toString() {
        return "SignedMessage{"
                + "messageClassName='" + messageClassName + '\''
                + ", deviceId='"
                + (deviceId != null && deviceId.length() > 16 ? deviceId.substring(0, 16) + "..." : deviceId) + '\''
                + ", timestamp=" + timestamp
                + ", signatureAlgorithm='" + signatureAlgorithm + '\''
                + '}';
    }
}
