package org.gslab.handlers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.gslab.configuration.MessageVerificationConfig;
import org.gslab.message_hiba.Message;
import org.gslab.message_hiba.RequestFinishMessage;
import org.gslab.message_hiba.SignedMessage;
import org.gslab.message_hiba.TaskFinishMessage;
import org.gslab.model.MessageVerificationResult;
import org.gslab.tpm.TPMService;
import org.gslab.tpm.blockchain.Web3BlockchainService;
import org.json.JSONObject;

/**
 * Message TPM 驗證 Inbound Handler
 * <p>
 * 負責驗證接收到的 SignedMessage 的 TPM 簽章。
 * 驗證通過後，將原始 Message 傳遞給下一個 Handler。
 * </p>
 *
 * <h2>驗證模式</h2>
 * <ul>
 * <li><b>TRUST_PACKAGE</b>：信任封包內的公鑰</li>
 * <li><b>BLOCKCHAIN_STRICT</b>：必須使用區塊鏈上已註冊的公鑰</li>
 * <li><b>BLOCKCHAIN_PREFERRED</b>：優先使用區塊鏈公鑰，否則使用封包公鑰</li>
 * </ul>
 *
 * <h2>Pipeline 位置</h2>
 *
 * <pre>
 * [FrameDecoder] → [MessageCodec] → [MessageTPMVerifyHandler] → [業務Handler]
 * </pre>
 *
 * @author TPM Integration
 * @see MessageTPMSignHandler
 * @see SignedMessage
 */
@Slf4j
public class MessageTPMVerifyHandler extends SimpleChannelInboundHandler<Message> {

    /**
     * 驗證模式
     */
    public enum VerificationMode {
        /**
         * 信任封包模式：使用封包內的公鑰驗證
         */
        TRUST_PACKAGE,

        /**
         * 區塊鏈模式：必須使用區塊鏈上已註冊的公鑰驗證
         */
        BLOCKCHAIN_STRICT,

        /**
         * 區塊鏈優先模式：優先使用區塊鏈公鑰，否則使用封包公鑰
         */
        BLOCKCHAIN_PREFERRED
    }

    private final VerificationMode mode;
    private final Web3BlockchainService blockchainService;
    private final MessageVerificationConfig config;
    private final TimestampValidator timestampValidator;
    private final NonceValidator nonceValidator;
    private final boolean enabled;

    /**
     * 建構 Handler（信任封包模式）
     */
    public MessageTPMVerifyHandler() {
        this(
                VerificationMode.TRUST_PACKAGE,
                null,
                MessageVerificationConfig.defaultConfig(),
                NonceValidator.getInstance(),
                true);
    }

    /**
     * 建構 Handler（可選擇是否啟用）
     *
     * @param enabled 是否啟用驗證
     */
    public MessageTPMVerifyHandler(boolean enabled) {
        this(
                VerificationMode.TRUST_PACKAGE,
                null,
                MessageVerificationConfig.defaultConfig(),
                NonceValidator.getInstance(),
                enabled);
    }

    /**
     * 建構 Handler（區塊鏈模式）
     *
     * @param mode              驗證模式
     * @param blockchainService 區塊鏈服務
     */
    public MessageTPMVerifyHandler(VerificationMode mode, Web3BlockchainService blockchainService) {
        this(mode, blockchainService, MessageVerificationConfig.defaultConfig(), NonceValidator.getInstance(), true);
    }

    /**
     * 建構 Handler（完整配置）
     *
     * @param mode              驗證模式
     * @param blockchainService 區塊鏈服務
     * @param config            驗證配置
     * @param nonceValidator    Nonce 驗證器
     * @param enabled           是否啟用
     */
    public MessageTPMVerifyHandler(
            VerificationMode mode,
            Web3BlockchainService blockchainService,
            MessageVerificationConfig config,
            NonceValidator nonceValidator,
            boolean enabled) {
        this.mode = mode;
        this.blockchainService = blockchainService;
        this.config = config;
        this.timestampValidator = new TimestampValidator(config);
        this.nonceValidator = nonceValidator;
        this.enabled = enabled;

        if (enabled) {
            log.debug("Message TPM Verify Handler initialized, mode: {}", mode);
            log.debug("   Timestamp validation: {}", config.isTimestampValidationEnabled() ? "enabled" : "disabled");
            log.debug("   Nonce validation: {}", config.isNonceValidationEnabled() ? "enabled" : "disabled");
        } else {
            log.debug("Message TPM Verify Handler is disabled");
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
        // 如果停用，直接傳遞
        if (!enabled) {
            ctx.fireChannelRead(msg);
            return;
        }

        // 如果不是 SignedMessage，直接傳遞（可能是未啟用簽章的舊版本）
        if (!(msg instanceof SignedMessage)) {
            log.debug(
                    "Received unsigned Message: {}, passing through",
                    msg.getClass().getSimpleName());
            ctx.fireChannelRead(msg);
            return;
        }

        SignedMessage signedMessage = (SignedMessage) msg;
        log.debug("Received SignedMessage, starting verification...");
        log.debug("   Original message type: {}", signedMessage.getMessageClassName());
        log.debug("   Device ID: {}...", truncate(signedMessage.getDeviceId(), 16));

        try {
            // 1. 驗證時間戳
            MessageVerificationResult timestampResult = timestampValidator.validate(signedMessage.getTimestamp());
            if (!timestampResult.isSuccess()) {
                log.warn("Timestamp validation failed: {}", timestampResult.getMessage());
                handleVerificationFailure(ctx, timestampResult);
                return;
            }
            log.debug("Timestamp validation passed");

            // 2. 驗證 Nonce
            if (config.isNonceValidationEnabled()) {
                MessageVerificationResult nonceResult = nonceValidator.validateAndRecord(signedMessage.getNonce());
                if (!nonceResult.isSuccess()) {
                    log.warn("Nonce validation failed: {}", nonceResult.getMessage());
                    handleVerificationFailure(ctx, nonceResult);
                    return;
                }
                log.debug("Nonce validation passed");
            }

            // 3. 取得用於驗證的公鑰
            PublicKey verificationKey = getVerificationKey(signedMessage);
            if (verificationKey == null) {
                log.error("Failed to obtain verification public key");
                handleVerificationFailure(
                        ctx,
                        MessageVerificationResult.failure(MessageVerificationResult.ErrorCode.DEVICE_NOT_REGISTERED));
                return;
            }

            // 4. 還原簽章和原始資料
            byte[] signature = Base64.getDecoder().decode(signedMessage.getSignature());
            byte[] messageData = Base64.getDecoder().decode(signedMessage.getMessageData());

            // 5. 驗證簽章
            boolean verified = TPMService.verifyWithJavaCrypto(verificationKey, messageData, signature);

            if (verified) {
                log.debug(
                        "Signature verification successful! Device: {}...", truncate(signedMessage.getDeviceId(), 16));

                // 6. 反序列化原始 Message
                Message originalMessage = deserializeMessage(messageData);
                log.debug(
                        "Restored original Message: {}",
                        originalMessage.getClass().getSimpleName());

                // 7. 檢查是否為 getFile 服務的回應，若是則儲存 TPM 驗證報告
                checkAndSaveTPMVerificationReport(signedMessage, originalMessage);

                // 8. 傳遞原始 Message 給下一個 Handler
                ctx.fireChannelRead(originalMessage);
            } else {
                log.warn("Signature verification failed! Data may have been tampered with");
                handleVerificationFailure(
                        ctx, MessageVerificationResult.failure(MessageVerificationResult.ErrorCode.SIGNATURE_INVALID));
            }

        } catch (Exception e) {
            log.error("Error occurred during verification: {}", e.getMessage(), e);
            handleVerificationFailure(
                    ctx,
                    MessageVerificationResult.failure(MessageVerificationResult.ErrorCode.PARSE_ERROR, e.getMessage()));
        }
    }

    /**
     * 處理驗證失敗
     */
    private void handleVerificationFailure(ChannelHandlerContext ctx, MessageVerificationResult result) {
        if (config.isSendErrorResponse()) {
            log.debug("Sending error response and closing connection");
            // 這裡可以發送錯誤訊息，但需要定義對應的 ErrorMessage 類別
            // 目前先直接關閉連線
        }
        ctx.close();
    }

    /**
     * 根據驗證模式取得公鑰
     */
    private PublicKey getVerificationKey(SignedMessage signedMessage) throws Exception {
        switch (mode) {
            case TRUST_PACKAGE:
                return extractPublicKeyFromMessage(signedMessage);

            case BLOCKCHAIN_STRICT:
                return getBlockchainPublicKey(signedMessage);

            case BLOCKCHAIN_PREFERRED:
            default:
                return getBlockchainPublicKeyWithFallback(signedMessage);
        }
    }

    /**
     * 從訊息中提取公鑰
     */
    private PublicKey extractPublicKeyFromMessage(SignedMessage signedMessage) throws Exception {
        byte[] publicKeyBytes = Base64.getDecoder().decode(signedMessage.getPublicKey());
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(TPMService.KEY_ALGORITHM);
        return keyFactory.generatePublic(keySpec);
    }

    /**
     * 從區塊鏈取得公鑰
     */
    private PublicKey getBlockchainPublicKey(SignedMessage signedMessage) {
        if (blockchainService == null) {
            log.error("Blockchain service not configured, cannot use BLOCKCHAIN_STRICT mode");
            return null;
        }
        log.debug("Querying public key from blockchain...");
        return blockchainService
                .getPublicKeyWithCache(signedMessage.getDeviceId())
                .orElse(null);
    }

    /**
     * 優先使用區塊鏈公鑰，否則使用訊息內公鑰
     */
    private PublicKey getBlockchainPublicKeyWithFallback(SignedMessage signedMessage) throws Exception {
        if (blockchainService != null) {
            Optional<PublicKey> blockchainKey = blockchainService.getPublicKeyWithCache(signedMessage.getDeviceId());
            if (blockchainKey.isPresent()) {
                log.debug("Using blockchain public key for verification");
                return blockchainKey.get();
            }
            log.warn("Device not registered on blockchain, using package public key (lower security)");
        }
        return extractPublicKeyFromMessage(signedMessage);
    }

    /**
     * 反序列化 Message
     */
    private Message deserializeMessage(byte[] data) throws Exception {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
                ObjectInputStream ois = new ObjectInputStream(bis); ) {
            return (Message) ois.readObject();
        }
    }

    /**
     * 截斷字串
     */
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "null";
        }
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Handler error occurred: {}", cause.getMessage(), cause);
        ctx.close();
    }

    /**
     * 檢查訊息是否為 getFile 服務的回應，若是則儲存 TPM 驗證報告
     *
     * @param signedMessage   已驗證的簽章訊息
     * @param originalMessage 原始訊息
     */
    private void checkAndSaveTPMVerificationReport(SignedMessage signedMessage, Message originalMessage) {
        try {
            Map<String, Object> requestResult = null;

            if (originalMessage instanceof RequestFinishMessage) {
                requestResult = ((RequestFinishMessage) originalMessage).getRequestResult();
            } else if (originalMessage instanceof TaskFinishMessage) {
                requestResult = ((TaskFinishMessage) originalMessage).getAttachment();
            }

            if (requestResult == null) {
                log.debug("No request result found in message");
                return;
            }

            // serviceName 可能在 requestResult 根層級，或在嵌套的 result 物件內
            String serviceName = null;
            Map<String, Object> dataToSave = requestResult;

            if (requestResult.containsKey("serviceName")) {
                serviceName = (String) requestResult.get("serviceName");
            } else if (requestResult.containsKey("result")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> innerResult = (Map<String, Object>) requestResult.get("result");
                if (innerResult != null && innerResult.containsKey("serviceName")) {
                    serviceName = (String) innerResult.get("serviceName");
                    dataToSave = innerResult;
                }
            }

            log.info("Detected serviceName: {}", serviceName);

            if ("getFile".equals(serviceName)) {
                saveTPMVerificationReport(signedMessage, dataToSave);
            }
        } catch (Exception e) {
            log.warn("Failed to check/save TPM verification report: {}", e.getMessage(), e);
        }
    }

    /**
     * 儲存 TPM 驗證報告到檔案（用於攻擊演示）
     *
     * @param signedMessage 已驗證的簽章訊息
     * @param result        服務執行結果
     */
    private void saveTPMVerificationReport(SignedMessage signedMessage, Map<String, Object> result) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS");
            String timestamp = dateFormat.format(new Date());

            JSONObject report = new JSONObject();

            // TPM 簽章資訊
            JSONObject tpmInfo = new JSONObject();
            tpmInfo.put("deviceId", signedMessage.getDeviceId());
            tpmInfo.put("tpmVersion", signedMessage.getTpmVersion());
            tpmInfo.put("signature", signedMessage.getSignature());
            tpmInfo.put("publicKey", signedMessage.getPublicKey());
            tpmInfo.put("signatureAlgorithm", signedMessage.getSignatureAlgorithm());
            tpmInfo.put("signatureTimestamp", signedMessage.getTimestamp());
            tpmInfo.put("nonce", signedMessage.getNonce());
            tpmInfo.put("messageData", signedMessage.getMessageData());
            report.put("tpmSignatureInfo", tpmInfo);

            // 驗證資訊
            JSONObject verificationInfo = new JSONObject();
            verificationInfo.put("verificationMode", mode.name());
            verificationInfo.put("verificationTimestamp", System.currentTimeMillis());
            verificationInfo.put("verificationPassed", true);
            report.put("verificationInfo", verificationInfo);

            // 檔案保護資訊（從 result 中提取）
            JSONObject fileProtectionInfo = new JSONObject();
            fileProtectionInfo.put("serviceName", result.get("serviceName"));
            if (result.containsKey("fileHash")) {
                fileProtectionInfo.put("fileHash", result.get("fileHash"));
            }
            if (result.containsKey("transfers")) {
                fileProtectionInfo.put("transfers", result.get("transfers"));
            }
            if (result.containsKey("requestId")) {
                fileProtectionInfo.put("requestId", result.get("requestId"));
            }
            report.put("fileProtectionInfo", fileProtectionInfo);

            // 儲存報告到檔案
            Path reportDir = Path.of("./verification_reports");
            Files.createDirectories(reportDir);
            String fileName = "tpm_verification_" + timestamp + ".json";
            Path reportPath = reportDir.resolve(fileName);
            Files.writeString(reportPath, report.toString(2));

            log.info("TPM verification report saved for attack demonstration: {}", reportPath);

        } catch (Exception e) {
            log.error("Failed to save TPM verification report: {}", e.getMessage(), e);
        }
    }
}
