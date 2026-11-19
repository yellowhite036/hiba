package org.gslab.tpm;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PublicKeyCacheLastKnownTest {

    private static final String EK = "A".repeat(64);
    private static PublicKey key;

    private final AtomicLong nanos = new AtomicLong(0);
    private final Ticker ticker = nanos::get;
    private final PublicKeyCacheService cache = new PublicKeyCacheService(
            100, Duration.ofMinutes(60), Duration.ofMinutes(30), Duration.ofHours(24), ticker);

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        key = gen.generateKeyPair().getPublic();
    }

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    void lastKnownOutlivesFreshCache() {
        cache.put(EK, key);
        advance(Duration.ofHours(2));
        assertThat(cache.get(EK)).isEmpty(); // 一般快取已過期
        assertThat(cache.getLastKnown(EK)).contains(key); // 鏈離線時仍可沿用
    }

    @Test
    void lastKnownExpiresAfterMaxAge() {
        cache.put(EK, key);
        advance(Duration.ofHours(24).plusMinutes(1));
        assertThat(cache.getLastKnown(EK)).isEmpty();
    }

    @Test
    void reVerificationRestartsMaxAge() {
        cache.put(EK, key);
        advance(Duration.ofHours(20));
        cache.put(EK, key); // 又從鏈上驗證一次
        advance(Duration.ofHours(20));
        assertThat(cache.getLastKnown(EK)).contains(key);
    }

    @Test
    void invalidateRemovesLastKnown() {
        cache.put(EK, key);
        cache.invalidate(EK); // 撤銷或鏈上查無此設備
        assertThat(cache.get(EK)).isEmpty();
        assertThat(cache.getLastKnown(EK)).isEmpty();
    }

    @Test
    void expireAllFreshKeepsLastKnown() {
        cache.put(EK, key);
        cache.expireAllFresh(); // 鏈恢復：全部重新查詢
        assertThat(cache.get(EK)).isEmpty();
        assertThat(cache.getLastKnown(EK)).contains(key);
    }

    @Test
    void unknownDeviceHasNoLastKnown() {
        assertThat(cache.getLastKnown(EK)).isEmpty();
    }
}
