package org.gslab.handlers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.security.PublicKey;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.gslab.message_hiba.Message;
import org.gslab.message_hiba.SignedMessage;
import org.gslab.tpm.TPMService;

/**
 * Message TPM 簽章 Outbound Handler
 * <p>
 * 負責在 Message 傳送前自動使用 TPM 進行簽章。
 * 此 Handler 適配現有的 Message 體系，將 Message 包裝成 SignedMessage。
 * </p>
 *
 * <h2>處理流程</h2>
 * <ol>
 * <li>接收要發送的 Message 物件</li>
 * <li>將 Message 序列化為 bytes</li>
 * <li>使用 TPM 對 bytes 進行簽章</li>
 * <li>建立 SignedMessage 包裝原始資料和簽章</li>
 * <li>將 SignedMessage 傳遞給下一個 Handler（MessageCodec）</li>
 * </ol>
 *
 * <h2>Pipeline 位置</h2>
 *
 * <pre>
 * [業務Handler] → [MessageTPMSignHandler] → [MessageCodec] → [FrameEncoder]
 * </pre>
 *
 * @author TPM Integration
 * @see MessageTPMVerifyHandler
 * @see SignedMessage
 */
@Slf4j
public class MessageTPMSignHandler extends ChannelOutboundHandlerAdapter {

    private final TPMService tpmService;
    private final boolean enabled;

    /**
     * 建構 TPM 簽章 Handler
     *
     * @param tpmService TPM 服務實例
     */
    public MessageTPMSignHandler(TPMService tpmService) {
        this(tpmService, true);
    }

    /**
     * 建構 TPM 簽章 Handler
     *
     * @param tpmService TPM 服務實例
     * @param enabled    是否啟用簽章功能
     */
    public MessageTPMSignHandler(TPMService tpmService, boolean enabled) {
        this.tpmService = tpmService;
        this.enabled = enabled;
        if (enabled) {
            log.debug("Message TPM Sign Handler initialized");
        } else {
            log.debug("Message TPM Sign Handler is disabled");
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // 如果停用或不是 Message 類型，直接傳遞
        if (!enabled || !(msg instanceof Message)) {
            ctx.write(msg, promise);
            return;
        }

        // 如果已經是 SignedMessage，直接傳遞（避免重複簽章）
        if (msg instanceof SignedMessage) {
            ctx.write(msg, promise);
            return;
        }

        Message originalMessage = (Message) msg;
        log.debug("Preparing to sign Message: {}", originalMessage.getClass().getSimpleName());

        try {
            // 1. 將 Message 序列化為 bytes
            byte[] messageBytes = serializeMessage(originalMessage);

            // 2. 使用 TPM 簽章
            byte[] signature = tpmService.sign(messageBytes);
            log.debug("TPM signing completed, signature length: {} bytes", signature.length);

            // 3. 取得公鑰和設備資訊
            PublicKey publicKey = tpmService.getSigningPublicKey();
            String ekFingerprint = tpmService.getEKFingerprint();
            String tpmVersion = tpmService.getTpmVersion();

            // 4. 建立 SignedMessage
            SignedMessage signedMessage = new SignedMessage()
                    .setMessageData(Base64.getEncoder().encodeToString(messageBytes))
                    .setMessageClassName(originalMessage.getClass().getName())
                    .setSignature(Base64.getEncoder().encodeToString(signature))
                    .setPublicKey(Base64.getEncoder().encodeToString(publicKey.getEncoded()))
                    .setSignatureAlgorithm(TPMService.SIGNATURE_ALGORITHM)
                    .setTimestamp(System.currentTimeMillis())
                    .setNonce(NonceValidator.generateNonce())
                    .setDeviceId(ekFingerprint)
                    .setTpmVersion(tpmVersion);

            log.trace(
                    "SignedMessage assembled, device ID: {}...",
                    ekFingerprint.length() > 16 ? ekFingerprint.substring(0, 16) : ekFingerprint);

            // 5. 傳遞 SignedMessage 給下一個 Handler
            ctx.write(signedMessage, promise);

        } catch (Exception e) {
            log.error("TPM signing failed: {}", e.getMessage(), e);
            promise.setFailure(e);
        }
    }

    /**
     * 序列化 Message 為 bytes
     */
    private byte[] serializeMessage(Message message) throws Exception {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos); ) {
            oos.writeObject(message);
            return bos.toByteArray();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Handler error occurred: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
