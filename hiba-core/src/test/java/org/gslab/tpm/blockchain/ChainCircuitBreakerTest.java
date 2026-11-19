package org.gslab.tpm.blockchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.NoRouteToHostException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ChainCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final ChainCircuitBreaker breaker = new ChainCircuitBreaker(30_000, now::get);

    @Test
    void closedByDefault() {
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.lastError()).isNull();
    }

    @Test
    void connectionFailureOpensForThirtySeconds() {
        assertThat(breaker.recordFailure(new NoRouteToHostException("No route to host"))).isTrue();
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.lastError()).isEqualTo("No route to host");
        assertThat(breaker.allowRequest()).isFalse();

        now.addAndGet(29_999);
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void onlyOneProbeAfterTimeout() {
        breaker.recordFailure(new IOException("down"));
        now.addAndGet(30_000);
        assertThat(breaker.allowRequest()).isTrue(); // 試探查詢
        assertThat(breaker.allowRequest()).isFalse(); // 其他查詢等試探結果
    }

    @Test
    void successfulProbeCloses() {
        breaker.recordFailure(new IOException("down"));
        now.addAndGet(30_000);
        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordSuccess();
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.lastError()).isNull();
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void failedProbeReopens() {
        breaker.recordFailure(new IOException("down"));
        now.addAndGet(30_000);
        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordFailure(new IOException("still down"));
        assertThat(breaker.allowRequest()).isFalse();
        now.addAndGet(30_000);
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void contractErrorDoesNotOpen() {
        // 合約 revert 等錯誤代表鏈是通的
        assertThat(breaker.recordFailure(new RuntimeException("execution reverted"))).isFalse();
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void contractErrorDuringProbeReleasesProbe() {
        breaker.recordFailure(new IOException("down"));
        now.addAndGet(30_000);
        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordFailure(new RuntimeException("decode error"));
        assertThat(breaker.allowRequest()).isTrue(); // 試探名額已釋放
    }

    @Test
    void wrappedIoExceptionCountsAsConnectionFailure() {
        Exception wrapped = new RuntimeException("call failed", new IOException("Connection refused"));
        assertThat(ChainCircuitBreaker.isConnectionFailure(wrapped)).isTrue();
        assertThat(breaker.recordFailure(wrapped)).isTrue();
        assertThat(breaker.lastError()).isEqualTo("Connection refused");
    }
}
