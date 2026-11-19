package org.gslab.tpm.blockchain;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.web3j.protocol.http.HttpService;

/**
 * 建立連往區塊鏈 RPC 的 HttpService，統一設定逾時。
 * <p>
 * web3j 預設連線逾時 10 秒；RPC 主機離線時（例如 Pi 關機）每次查詢要等到作業系統放棄（約 3 秒）才失敗。
 * 區塊鏈都在區域網路內，連線應在幾毫秒內建立，所以連線逾時設短（預設 1 秒），
 * 讓斷路器（{@link ChainCircuitBreaker}）很快就能判定鏈連不上。讀取逾時則保留足夠時間給較大的查詢。
 * </p>
 * 可用 JVM 參數調整：{@code -Dhiba.rpc.connectTimeoutMs=1000}、{@code -Dhiba.rpc.readTimeoutMs=30000}。
 */
public final class RpcHttp {

    public static final long CONNECT_TIMEOUT_MS = Long.getLong("hiba.rpc.connectTimeoutMs", 1_000L);
    public static final long READ_TIMEOUT_MS = Long.getLong("hiba.rpc.readTimeoutMs", 30_000L);

    private static final OkHttpClient CLIENT = HttpService.getOkHttpClientBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();

    private RpcHttp() {}

    /** 連往 rpcUrl 的 HttpService（共用同一個連線池） */
    public static HttpService service(String rpcUrl) {
        return new HttpService(rpcUrl, CLIENT);
    }
}
