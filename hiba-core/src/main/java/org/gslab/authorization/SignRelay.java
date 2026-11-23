package org.gslab.authorization;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.gslab.SdnNetwork;
import org.gslab.authorization.AuthorizationCertificate.ValidatorSignature;
import org.gslab.message_hiba.AuthSignRequest;
import org.gslab.message_hiba.AuthSignResponse;
import org.gslab.sdns.HiBAInitial;
import org.json.JSONObject;

/**
 * 沿著 HiBA 既有的 broker／client 連線收集 Validator 簽章（Netty 轉送）。
 *
 * <pre>
 * 發起者（分派任務的 Broker）
 *   ├─ 自己是 Validator 就自己簽
 *   └─ AuthSignRequest ──▶ 每個相鄰節點（下層 clients ＋ 上層 broker，不含來源）
 *                            ├─ 是 Validator 就簽
 *                            ├─ 再轉給它的相鄰節點（不含來源），等它們回覆
 *                            └─ AuthSignResponse（自己＋轉送收到的簽章）──▶ 回給來源
 * </pre>
 *
 * HiBA 的連線一律由下層主動連上層（NAT 後面的網域也連得上），這裡只沿用已建立的連線傳訊息，
 * 所以上層不需要能主動連到下層。轉送的節點不需要被信任：請求內容有提出者的 TPM 簽章，
 * 每個 Validator 簽章都由發起者用鏈上公鑰驗證，偽造或修改都會被丟棄；轉送節點最多只能不轉送。
 * 同一個請求在一個節點只處理一次（correlationId 去重），避免環路。
 */
@Slf4j
public final class SignRelay {

    /** 每往下一跳，等待時間扣掉這麼多，讓下層先回覆 */
    static final long HOP_MARGIN_MS = 500;
    /** 剩下的時間少於這個就不再往下轉送 */
    static final long MIN_FORWARD_MS = 300;
    /** 轉送節點回覆上層前保留的時間 */
    static final long REPLY_MARGIN_MS = 100;

    private static final SignRelay INSTANCE = new SignRelay();

    private volatile SdnNetwork network;
    /** correlationId → (送出的連線 → 等待的回覆) */
    private final Map<String, Map<Channel, CompletableFuture<AuthSignResponse>>> pending = new ConcurrentHashMap<>();
    /** 處理過的 correlationId → 過期時間 */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "auth-relay");
        t.setDaemon(true);
        return t;
    });

    public record Collected(List<ValidatorSignature> signatures, List<String> notes) {}

    private SignRelay() {}

    public static SignRelay get() {
        return INSTANCE;
    }

    /** App 建立時設定（取得相鄰連線用） */
    public void setNetwork(SdnNetwork network) {
        this.network = network;
    }

    /** 發起者：收集整個 HiBA 網路中 Validator 的簽章（不驗證，由呼叫者驗證） */
    Collected collect(String correlationId, String requestJson, long budgetMs) {
        return gather(correlationId, requestJson, budgetMs, null);
    }

    private Collected gather(String correlationId, String requestJson, long budgetMs, Channel from) {
        long start = System.currentTimeMillis();
        List<ValidatorSignature> sigs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String self = String.valueOf(HiBAInitial.nodeName);

        purgeSeen(start);
        if (seen.putIfAbsent(correlationId, start + 120_000) != null) {
            return new Collected(sigs, notes); // 已經處理過（例如經由另一條路徑又收到）
        }

        signSelf(requestJson, self, sigs, notes);

        long childBudget = budgetMs - HOP_MARGIN_MS;
        SdnNetwork net = network;
        if (net == null || childBudget < MIN_FORWARD_MS) {
            return new Collected(sigs, notes);
        }

        Map<Channel, CompletableFuture<AuthSignResponse>> waits = new LinkedHashMap<>();
        List<Channel> neighbors = new ArrayList<>(net.getClients().keySet());
        neighbors.add(net.getBrokerChannel());
        for (Channel ch : neighbors) {
            if (ch != null && ch != from && ch.isActive() && !waits.containsKey(ch)) {
                waits.put(ch, new CompletableFuture<>());
            }
        }
        if (waits.isEmpty()) {
            return new Collected(sigs, notes);
        }

        pending.put(correlationId, new ConcurrentHashMap<>(waits));
        try {
            AuthSignRequest req = new AuthSignRequest(correlationId, requestJson, childBudget);
            for (Channel ch : waits.keySet()) {
                ch.writeAndFlush(req);
            }
            long waitMs = budgetMs - (System.currentTimeMillis() - start) - (from == null ? 0 : REPLY_MARGIN_MS);
            if (waitMs > 0) {
                try {
                    CompletableFuture.allOf(waits.values().toArray(new CompletableFuture[0]))
                            .get(waitMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // 沒回的當作沒有簽章
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("[Auth] Relay {} wait failed: {}", shortId(correlationId), e.getMessage());
        } finally {
            pending.remove(correlationId);
        }

        for (Map.Entry<Channel, CompletableFuture<AuthSignResponse>> e : waits.entrySet()) {
            AuthSignResponse resp = e.getValue().getNow(null);
            if (resp == null) {
                notes.add(peerName(net, e.getKey()) + ": no response");
                continue;
            }
            for (String[] s : resp.getSignatures()) {
                if (s != null && s.length == 2) {
                    sigs.add(new ValidatorSignature(s[0], s[1]));
                }
            }
            notes.addAll(resp.getNotes());
        }
        return new Collected(sigs, notes);
    }

    private static void signSelf(String requestJson, String self, List<ValidatorSignature> sigs, List<String> notes) {
        AuthorizationService auth = AuthorizationService.get();
        if (!auth.isEnabled()) {
            return;
        }
        try {
            AuthorizationCertificate cert = AuthorizationCertificate.fromRequestJson(new JSONObject(requestJson));
            String sig = auth.evaluateAndSign(cert);
            sigs.add(new ValidatorSignature(auth.selfIdOrEmpty(), sig));
        } catch (AuthorizationService.Rejection r) {
            if (!r.getMessage().startsWith("this node is not an active validator")) {
                notes.add(self + ": " + r.getMessage());
                log.info("[Auth] Refused to sign (relay): {}", r.getMessage());
            }
        } catch (Exception e) {
            notes.add(self + ": bad request (" + e.getMessage() + ")");
        }
    }

    private void purgeSeen(long now) {
        if (seen.size() > 1000) {
            seen.values().removeIf(exp -> exp < now);
        }
    }

    private static String peerName(SdnNetwork net, Channel ch) {
        Map.Entry<String, String> client = net.getClients().get(ch);
        if (client != null && client.getKey() != null) {
            return client.getKey();
        }
        if (ch == net.getBrokerChannel()) {
            return "upper broker";
        }
        return ch.remoteAddress() instanceof InetSocketAddress a ? a.getAddress().getHostAddress() : "peer";
    }

    private static String shortId(String id) {
        return id == null || id.length() < 8 ? id : id.substring(0, 8);
    }

    // =====================================================================
    // Netty handlers（加在 broker 端與 client 端的 pipeline）
    // =====================================================================

    /** 收到相鄰節點的簽章請求：在背景執行緒簽章與轉送，完成後回覆來源 */
    public static final class RequestHandler extends SimpleChannelInboundHandler<AuthSignRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, AuthSignRequest msg) {
            Channel from = ctx.channel();
            INSTANCE.pool.execute(() -> {
                Collected c;
                try {
                    c = INSTANCE.gather(msg.getCorrelationId(), msg.getRequestJson(), msg.getBudgetMs(), from);
                } catch (Exception e) {
                    log.warn("[Auth] Relay {} failed: {}", shortId(msg.getCorrelationId()), e.getMessage());
                    c = new Collected(List.of(), List.of(HiBAInitial.nodeName + ": relay error"));
                }
                List<String[]> out = new ArrayList<>();
                for (ValidatorSignature s : c.signatures()) {
                    out.add(new String[] {s.validatorId(), s.signature()});
                }
                if (from.isActive()) {
                    from.writeAndFlush(new AuthSignResponse(msg.getCorrelationId(), out, c.notes()));
                }
            });
        }
    }

    /** 收到相鄰節點的回覆：交給等待中的 gather */
    public static final class ResponseHandler extends SimpleChannelInboundHandler<AuthSignResponse> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, AuthSignResponse msg) {
            Map<Channel, CompletableFuture<AuthSignResponse>> waits = INSTANCE.pending.get(msg.getCorrelationId());
            CompletableFuture<AuthSignResponse> f = waits == null ? null : waits.get(ctx.channel());
            if (f != null) {
                f.complete(msg);
            }
        }
    }
}
