package org.gslab.tpm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

/**
 * 每台設備最後一次從區塊鏈驗證過的公鑰，只在區塊鏈連不上時使用。
 * <ul>
 * <li>每筆記錄保留到「最後一次驗證」後 {@code maxAge}（預設 24 小時），之後不再使用。</li>
 * <li>可寫入檔案（{@code known_device_keys.json}），節點在鏈離線期間重啟也還認得已知設備；
 *     檔案只有公鑰與驗證時間，沒有任何私密資料。</li>
 * <li>鏈上查到設備沒註冊或已撤銷時，呼叫端要 {@link #remove}，該設備立即失去備援資格。</li>
 * </ul>
 */
@Slf4j
public final class LastKnownKeyStore {

    /** 預設的保存檔名（HiBA 工作目錄下） */
    public static final String DEFAULT_FILE = "known_device_keys.json";

    private record Entry(PublicKey key, long verifiedAt) {}

    private final Path file;
    private final long maxAgeMillis;
    private final int maxEntries;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * @param file       保存檔；null 表示只存在記憶體
     * @param maxAge     最後一次驗證後最多沿用多久
     * @param maxEntries 最多保存幾台設備（超過時捨棄最舊的）
     * @param clock      目前時間（毫秒，牆上時鐘）
     */
    public LastKnownKeyStore(Path file, Duration maxAge, int maxEntries, LongSupplier clock) {
        this.file = file;
        this.maxAgeMillis = maxAge.toMillis();
        this.maxEntries = maxEntries;
        this.clock = clock;
        load();
    }

    /** 從鏈上驗證過這把公鑰（重新開始計算保留時間） */
    public void put(String ek, PublicKey key) {
        if (ek == null || key == null) {
            return;
        }
        Entry prev = entries.put(ek, new Entry(key, clock.getAsLong()));
        trim();
        // 同一把公鑰在 10 分鐘內重複驗證就不重寫檔案（每台設備約每小時驗證一次）
        if (prev == null || !prev.key().equals(key) || clock.getAsLong() - prev.verifiedAt() > 10 * 60_000L) {
            save();
        }
    }

    /** 最後一次驗證過的公鑰；沒有或已超過保留時間時為 empty */
    public Optional<PublicKey> get(String ek) {
        Entry e = ek == null ? null : entries.get(ek);
        if (e == null) {
            return Optional.empty();
        }
        if (clock.getAsLong() - e.verifiedAt() > maxAgeMillis) {
            entries.remove(ek, e);
            return Optional.empty();
        }
        return Optional.of(e.key());
    }

    /** 鏈上查無此設備或已撤銷：不再當作已知設備 */
    public void remove(String ek) {
        if (ek != null && entries.remove(ek) != null) {
            save();
        }
    }

    public void clear() {
        if (!entries.isEmpty()) {
            entries.clear();
            save();
        }
    }

    public int size() {
        return entries.size();
    }

    private void trim() {
        while (entries.size() > maxEntries) {
            entries.entrySet().stream()
                    .min(Comparator.comparingLong(en -> en.getValue().verifiedAt()))
                    .ifPresent(oldest -> entries.remove(oldest.getKey()));
        }
    }

    // ============ 檔案 ============

    private synchronized void save() {
        if (file == null) {
            return;
        }
        JSONObject root = new JSONObject();
        long now = clock.getAsLong();
        entries.forEach((ek, e) -> {
            if (now - e.verifiedAt() <= maxAgeMillis) {
                root.put(
                        ek,
                        new JSONObject()
                                .put("algorithm", e.key().getAlgorithm())
                                .put("publicKey", Base64.getEncoder().encodeToString(e.key().getEncoded()))
                                .put("verifiedAt", e.verifiedAt()));
            }
        });
        try {
            Path dir = file.toAbsolutePath().getParent();
            Path tmp = Files.createTempFile(dir, ".known_device_keys", ".tmp");
            Files.writeString(tmp, root.toString(2), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot save last-known device keys to {}: {}", file, e.getMessage());
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            long now = clock.getAsLong();
            int loaded = 0;
            for (String ek : root.keySet()) {
                JSONObject o = root.optJSONObject(ek);
                if (o == null) {
                    continue;
                }
                long verifiedAt = o.optLong("verifiedAt", 0);
                if (verifiedAt <= 0 || verifiedAt > now || now - verifiedAt > maxAgeMillis) {
                    continue; // 過期，或時間在未來（時鐘被調過），都不採用
                }
                try {
                    byte[] der = Base64.getDecoder().decode(o.getString("publicKey"));
                    PublicKey key = KeyFactory.getInstance(o.optString("algorithm", "RSA"))
                            .generatePublic(new X509EncodedKeySpec(der));
                    entries.put(ek, new Entry(key, verifiedAt));
                    loaded++;
                } catch (Exception e) {
                    log.debug("Skipping unreadable last-known key for {}: {}", ek, e.getMessage());
                }
            }
            trim();
            log.info("Loaded {} last-known device key(s) from {}", loaded, file);
        } catch (Exception e) {
            log.warn("Cannot read last-known device keys from {}: {}", file, e.getMessage());
        }
    }
}
