package org.gslab.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.gslab.message_hiba.FileTransferFormat;
import org.gslab.sdns.HiBAInitial;
import org.gslab.tpm.TPMService;
import org.gslab.tpm.blockchain.Web3BlockchainService;

/**
 * 檔案傳輸（DATA_PORT 8088）的傳送端身分驗證。
 * <p>
 * 傳送端在第一個區塊帶上「傳輸標頭」並用 TPM 簽章：傳輸 ID、傳送端 EK、時間、檔名、存檔路徑、
 * 檔案大小與 SHA-256。接收端用鏈上公鑰驗證簽章（未登記或已撤銷的設備查不到公鑰）、
 * 檢查時間與傳輸 ID 是否重複（防重放），收完後再比對檔案雜湊。
 * </p>
 * 內容在傳輸途中仍是明文（TLS 之後處理）；雜湊比對可以發現內容被竄改。
 */
@Slf4j
public final class FileTransferAuth {

    public static final String PREFIX = "HIBA-FT-V1";
    static final long MAX_SKEW_MS = 5 * 60_000L;

    /** 用過的傳輸 ID → 過期時間 */
    private static final Map<String, Long> SEEN = new ConcurrentHashMap<>();

    /** EK → 鏈上公鑰（未登記或已撤銷時為 empty）；測試可替換 */
    static volatile Function<String, Optional<PublicKey>> keyLookup = ek -> Optional.empty();
    /** 傳送端的 TPM；測試可替換 */
    static volatile Supplier<TPMService> tpm = () -> HiBAInitial.tpmService;

    public record Result(boolean ok, boolean signed, String reason, String senderEk) {}

    private FileTransferAuth() {}

    /** HiBANetwork 建好 TPMDeviceRegistry 服務後呼叫 */
    public static void init(Web3BlockchainService chain) {
        if (chain != null) {
            keyLookup = chain::getPublicKeyWithCache;
        }
    }

    /** 傳送端：填入傳輸標頭並用 TPM 簽章（沒有 TPM 時不簽，接收端依模式決定是否接受） */
    public static void sign(FileTransferFormat f, File file) {
        f.setTransferId(UUID.randomUUID().toString());
        f.setTimestamp(System.currentTimeMillis());
        try {
            f.setFileSize(file.length());
            f.setFileSha256(sha256Hex(file));
        } catch (IOException e) {
            log.warn("[FileTransfer] Cannot hash {}: {}", file, e.getMessage());
        }
        TPMService t = tpm.get();
        if (t == null) {
            log.warn("[FileTransfer] No TPM: sending {} unsigned", file.getName());
            return;
        }
        try {
            f.setSenderEk(t.getEKFingerprint());
            f.setSignature(Base64.getEncoder().encodeToString(t.sign(payload(f))));
        } catch (Exception e) {
            f.setSignature(null);
            log.warn("[FileTransfer] TPM signing failed, sending {} unsigned: {}", file.getName(), e.getMessage());
        }
    }

    /** 接收端：驗證第一個區塊的標頭 */
    public static Result verify(FileTransferFormat f) {
        String ek = f.getSenderEk();
        if (f.getSignature() == null || ek == null || f.getTransferId() == null) {
            return new Result(false, false, "unsigned transfer (old version sender or no TPM)", ek);
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - f.getTimestamp()) > MAX_SKEW_MS) {
            return new Result(false, true, "timestamp out of range (clock skew over 5 minutes?)", ek);
        }
        Optional<PublicKey> pk;
        try {
            pk = keyLookup.apply(ek);
        } catch (RuntimeException e) {
            pk = Optional.empty();
        }
        if (pk.isEmpty()) {
            return new Result(false, true, "sender " + shortId(ek) + " is not registered or is revoked", ek);
        }
        boolean valid;
        try {
            Signature s = Signature.getInstance(TPMService.SIGNATURE_ALGORITHM);
            s.initVerify(pk.get());
            s.update(payload(f));
            valid = s.verify(Base64.getDecoder().decode(f.getSignature()));
        } catch (Exception e) {
            valid = false;
        }
        if (!valid) {
            return new Result(false, true, "signature invalid (header tampered or wrong key)", ek);
        }
        purge(now);
        if (SEEN.putIfAbsent(f.getTransferId(), now + 2 * MAX_SKEW_MS) != null) {
            return new Result(false, true, "transfer id already used (replay)", ek);
        }
        return new Result(true, true, "ok", ek);
    }

    static byte[] payload(FileTransferFormat f) {
        return String.join(
                        "\n",
                        PREFIX,
                        nz(f.getTransferId()),
                        nz(f.getSenderEk()),
                        Long.toString(f.getTimestamp()),
                        nz(f.getFileName()),
                        nz(f.getTargetPath()),
                        Long.toString(f.getFileSize()),
                        nz(f.getFileSha256()))
                .getBytes(UTF_8);
    }

    public static String sha256Hex(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortId(String ek) {
        return ek == null ? "?" : ek.substring(0, Math.min(16, ek.length())) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void purge(long now) {
        SEEN.values().removeIf(exp -> exp < now);
    }
}
