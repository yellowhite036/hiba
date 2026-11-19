package org.gslab.tpm;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicKeyCacheLastKnownTest {

    private static final String EK = "A".repeat(64);
    private static final String EK2 = "B".repeat(64);
    private static PublicKey key;
    private static PublicKey key2;

    private final AtomicLong nanos = new AtomicLong(0);
    private final AtomicLong millis = new AtomicLong(1_800_000_000_000L);
    private final Ticker ticker = nanos::get;

    @TempDir
    Path dir;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        key = gen.generateKeyPair().getPublic();
        key2 = gen.generateKeyPair().getPublic();
    }

    private LastKnownKeyStore store(Path file) {
        return new LastKnownKeyStore(file, Duration.ofHours(24), 100, millis::get);
    }

    private PublicKeyCacheService cache(Path file) {
        return new PublicKeyCacheService(100, Duration.ofMinutes(60), Duration.ofMinutes(30), store(file), ticker);
    }

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
        millis.addAndGet(d.toMillis());
    }

    @Test
    void lastKnownOutlivesFreshCache() {
        PublicKeyCacheService cache = cache(null);
        cache.put(EK, key);
        advance(Duration.ofHours(2));
        assertThat(cache.get(EK)).isEmpty(); // 一般快取已過期
        assertThat(cache.getLastKnown(EK)).contains(key); // 鏈離線時仍可沿用
    }

    @Test
    void lastKnownExpiresAfterMaxAge() {
        PublicKeyCacheService cache = cache(null);
        cache.put(EK, key);
        advance(Duration.ofHours(24).plusMinutes(1));
        assertThat(cache.getLastKnown(EK)).isEmpty();
    }

    @Test
    void reVerificationRestartsMaxAge() {
        PublicKeyCacheService cache = cache(null);
        cache.put(EK, key);
        advance(Duration.ofHours(20));
        cache.put(EK, key); // 又從鏈上驗證一次
        advance(Duration.ofHours(20));
        assertThat(cache.getLastKnown(EK)).contains(key);
    }

    @Test
    void invalidateRemovesLastKnown() {
        PublicKeyCacheService cache = cache(null);
        cache.put(EK, key);
        cache.invalidate(EK); // 撤銷或鏈上查無此設備
        assertThat(cache.get(EK)).isEmpty();
        assertThat(cache.getLastKnown(EK)).isEmpty();
    }

    @Test
    void expireAllFreshKeepsLastKnown() {
        PublicKeyCacheService cache = cache(null);
        cache.put(EK, key);
        cache.expireAllFresh(); // 鏈恢復：全部重新查詢
        assertThat(cache.get(EK)).isEmpty();
        assertThat(cache.getLastKnown(EK)).contains(key);
    }

    @Test
    void unknownDeviceHasNoLastKnown() {
        assertThat(cache(null).getLastKnown(EK)).isEmpty();
    }

    @Test
    void survivesRestartThroughFile() {
        Path file = dir.resolve(LastKnownKeyStore.DEFAULT_FILE);
        PublicKeyCacheService before = cache(file);
        before.put(EK, key);
        before.put(EK2, key2);
        assertThat(file).exists();

        advance(Duration.ofHours(3));
        PublicKeyCacheService after = cache(file); // 節點在鏈離線期間重啟
        assertThat(after.get(EK)).isEmpty();
        assertThat(after.getLastKnown(EK)).contains(key);
        assertThat(after.getLastKnown(EK2)).contains(key2);
    }

    @Test
    void removedDeviceIsGoneAfterRestart() {
        Path file = dir.resolve(LastKnownKeyStore.DEFAULT_FILE);
        PublicKeyCacheService before = cache(file);
        before.put(EK, key);
        before.put(EK2, key2);
        before.invalidate(EK2); // 鏈上查到已撤銷

        PublicKeyCacheService after = cache(file);
        assertThat(after.getLastKnown(EK)).contains(key);
        assertThat(after.getLastKnown(EK2)).isEmpty();
    }

    @Test
    void expiredEntriesAreNotLoaded() {
        Path file = dir.resolve(LastKnownKeyStore.DEFAULT_FILE);
        cache(file).put(EK, key);
        advance(Duration.ofHours(25));
        assertThat(cache(file).getLastKnown(EK)).isEmpty();
    }

    @Test
    void futureTimestampIsNotTrusted() {
        Path file = dir.resolve(LastKnownKeyStore.DEFAULT_FILE);
        cache(file).put(EK, key);
        millis.addAndGet(-Duration.ofHours(1).toMillis()); // 時鐘被往回調
        assertThat(cache(file).getLastKnown(EK)).isEmpty();
    }

    @Test
    void corruptFileIsIgnored() throws Exception {
        Path file = dir.resolve(LastKnownKeyStore.DEFAULT_FILE);
        Files.writeString(file, "not json");
        assertThat(cache(file).getLastKnown(EK)).isEmpty();
    }
}
