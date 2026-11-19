package org.gslab.tpm.blockchain;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 區塊鏈斷路器：RPC 連不上時，暫停查詢一段時間，避免每次查詢都卡在連線逾時。
 * <p>
 * 背景：TPM 驗章在 Netty 網路執行緒上查鏈上公鑰；Pi 離線時每次查詢要等約 3 秒才失敗，
 * 期間心跳無法回覆，節點會一直判定 broker 斷線、重新選舉。
 * </p>
 * <ul>
 * <li>關閉（正常）：所有查詢都送出。</li>
 * <li>查詢因連線問題失敗（{@link #isConnectionFailure}）→ 開啟 {@code openMillis}，期間 {@link #allowRequest()} 回傳 false，
 *     呼叫端直接當成「鏈暫時無法使用」。</li>
 * <li>時間到後只放行一個試探查詢：成功就關閉，失敗就再開啟 {@code openMillis}。</li>
 * </ul>
 * 合約回傳的錯誤（例如 revert、設備未註冊）代表鏈是通的，不會開啟斷路器。
 */
@Slf4j
public final class ChainCircuitBreaker {

    public static final long DEFAULT_OPEN_MILLIS = 30_000;

    private final long openMillis;
    private final LongSupplier clock;

    /** 0＝關閉；否則為暫停查詢到的時間（毫秒） */
    private volatile long openUntil = 0;
    /** 開啟後時間到，是否已有一個試探查詢在進行 */
    private final AtomicBoolean probing = new AtomicBoolean(false);
    private volatile String lastError = null;
    private volatile Runnable onRecovered = () -> {};

    public ChainCircuitBreaker() {
        this(DEFAULT_OPEN_MILLIS, System::currentTimeMillis);
    }

    ChainCircuitBreaker(long openMillis, LongSupplier clock) {
        this.openMillis = openMillis;
        this.clock = clock;
    }

    /** 斷路器從開啟回到關閉（鏈恢復）時要做的事 */
    public void onRecovered(Runnable action) {
        this.onRecovered = action == null ? () -> {} : action;
    }

    /** 這次查詢可以送出嗎（false＝鏈暫時無法使用，請直接走備援） */
    public boolean allowRequest() {
        long until = openUntil;
        if (until == 0) {
            return true;
        }
        if (clock.getAsLong() < until) {
            return false;
        }
        return probing.compareAndSet(false, true); // 時間到：只放行一個試探查詢
    }

    /** 查詢成功（包含合約回傳「未註冊」等正常結果） */
    public void recordSuccess() {
        boolean wasOpen = openUntil != 0;
        openUntil = 0;
        lastError = null;
        probing.set(false);
        if (wasOpen) {
            log.info("[Blockchain] RPC reachable again, resuming blockchain queries");
            try {
                onRecovered.run();
            } catch (RuntimeException e) {
                log.warn("[Blockchain] Recovery action failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 查詢失敗。只有連線問題會開啟斷路器。
     *
     * @return true 表示這是連線問題（鏈暫時無法使用）
     */
    public boolean recordFailure(Throwable error) {
        if (!isConnectionFailure(error)) {
            probing.set(false);
            return false;
        }
        boolean wasClosed = openUntil == 0;
        String message = rootMessage(error);
        lastError = message;
        openUntil = clock.getAsLong() + openMillis;
        probing.set(false);
        if (wasClosed) {
            log.warn(
                    "[Blockchain] RPC unreachable ({}), pausing blockchain queries for {} s", message, openMillis / 1000);
        } else {
            log.info("[Blockchain] RPC still unreachable ({}), retry in {} s", message, openMillis / 1000);
        }
        return true;
    }

    /** 斷路器開啟中（鏈暫時無法使用） */
    public boolean isOpen() {
        return openUntil != 0;
    }

    /** 最近一次連線失敗的原因；正常時為 null */
    public String lastError() {
        return lastError;
    }

    /**
     * 連線類錯誤：沿著 cause 找到任何 IOException（連不上、逾時、HTTP 錯誤等）。
     * web3j 的 HttpService 連線失敗會丟 IOException；合約 revert 等是 RuntimeException，不算。
     */
    public static boolean isConnectionFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
