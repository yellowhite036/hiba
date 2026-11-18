package org.gslab.status;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.gslab.sdns.AcctRole;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 節點狀態頁的 HTTP 路由。
 * <p>accounting server 上：</p>
 * <ul>
 * <li>GET / 、GET /nodes → 狀態網頁（resources/web/nodes.html）</li>
 * <li>GET /api/nodes → 狀態 JSON（安裝腳本也會讀它）</li>
 * <li>DELETE /api/nodes/{name} → 從節點表移除一台離線節點（在線的回 409）</li>
 * <li>GET /dist/hiba-core-0.0.1.jar.md5?node=&amp;have=&amp;running= → 目前提供的 jar 的 md5；
 *     節點的自動更新程式（hiba_autoupdate.sh）每次檢查時呼叫，順便回報自己的版本</li>
 * <li>GET /dist/hiba-core-0.0.1.jar → 下載 jar（見 {@link DistJar}）</li>
 * </ul>
 * <p>每台都有：GET /chain 區塊鏈動作紀錄網頁；GET /api/chain 由 accounting server 回答（{@link ChainActivity}），
 * 一般節點轉送過去。</p>
 * <p>一般節點上：同樣提供 GET / 、GET /nodes 網頁，GET /api/nodes 與 DELETE /api/nodes/{name}
 * 轉送給 env.json 設定的 accounting server，所以從任何一台節點都能看狀態頁。</p>
 */
public final class NodeStatusHttp {

    private static final Logger logger = LoggerFactory.getLogger(NodeStatusHttp.class);
    private static final String PAGE_RESOURCE = "/web/nodes.html";
    private static final String CHAIN_PAGE_RESOURCE = "/web/chain.html";
    private static final String NODE_PATH_PREFIX = "/api/nodes/";
    private static final String DIST_JAR_PATH = "/dist/" + DistJar.FILE_NAME;
    private static final String DIST_MD5_PATH = DIST_JAR_PATH + ".md5";

    private NodeStatusHttp() {}

    /** @return true 表示已處理這個請求（呼叫端不要再處理） */
    public static boolean handle(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = request.uri();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        NodeStatusRegistry registry = NodeStatusRegistry.get();
        if (HttpMethod.GET.equals(request.method()) && "/api/acct".equals(path)) {
            // 各節點用來找出目前的 accounting server（AcctRole）；只回答本機已知的資訊，不再去問別台
            boolean dedicated = registry.isEnabled() && !registry.isActing();
            send(ctx, HttpResponseStatus.OK, "application/json; charset=UTF-8",
                    AcctRole.describe(dedicated).toString().getBytes(StandardCharsets.UTF_8));
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ("/chain".equals(path) || "/chain/".equals(path))) {
            // 區塊鏈動作紀錄網頁：每台都提供，資料（/api/chain）由 accounting server 回答或轉送過去
            sendHtml(ctx, CHAIN_PAGE_RESOURCE);
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && "/api/chain".equals(path)) {
            if (registry.isEnabled()) {
                send(ctx, HttpResponseStatus.OK, "application/json; charset=UTF-8",
                        ChainActivity.get().toJson().toString().getBytes(StandardCharsets.UTF_8));
            } else {
                proxyToAcct(ctx, "GET", path, false);
            }
            return true;
        }
        if (!registry.isEnabled()) {
            return handleOnNode(ctx, request);
        }
        if (HttpMethod.DELETE.equals(request.method()) && path.startsWith(NODE_PATH_PREFIX)) {
            forget(ctx, URLDecoder.decode(path.substring(NODE_PATH_PREFIX.length()), StandardCharsets.UTF_8));
            return true;
        }
        if (!HttpMethod.GET.equals(request.method())) {
            return false;
        }
        switch (path) {
            case "/":
            case "/nodes":
            case "/nodes/":
                byte[] page = loadResource(PAGE_RESOURCE);
                if (page == null) {
                    send(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain; charset=UTF-8",
                            "nodes.html not found in jar".getBytes(StandardCharsets.UTF_8));
                } else {
                    send(ctx, HttpResponseStatus.OK, "text/html; charset=UTF-8", page);
                }
                return true;
            case "/api/nodes":
                byte[] json = NodeStatusRegistry.get().toJson().toString().getBytes(StandardCharsets.UTF_8);
                send(ctx, HttpResponseStatus.OK, "application/json; charset=UTF-8", json);
                return true;
            case DIST_MD5_PATH:
                distMd5(ctx, request);
                return true;
            case DIST_JAR_PATH:
                distJar(ctx);
                return true;
            default:
                return false;
        }
    }

    // ============ 一般節點：網頁自己提供，資料轉送給 accounting server ============

    private static final HttpClient PROXY_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private static boolean handleOnNode(ChannelHandlerContext ctx, FullHttpRequest request) {
        HttpMethod method = request.method();
        String path = request.uri();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (HttpMethod.GET.equals(method)) {
            switch (path) {
                case "/":
                case "/nodes":
                case "/nodes/":
                    sendPage(ctx);
                    return true;
                case "/api/nodes":
                    proxyToAcct(ctx, "GET", path, true);
                    return true;
                // 每台都提供自己執行中的 jar：Pi 改成一般節點後，各節點的自動更新仍可從 Pi 取得新版
                case DIST_MD5_PATH:
                    distMd5(ctx, request);
                    return true;
                case DIST_JAR_PATH:
                    distJar(ctx);
                    return true;
                default:
                    return false;
            }
        }
        if (HttpMethod.DELETE.equals(method) && path.startsWith(NODE_PATH_PREFIX)) {
            proxyToAcct(ctx, "DELETE", path, false);
            return true;
        }
        return false;
    }

    /**
     * 非同步轉送給 accounting server（不卡住 Netty 的執行緒）。
     *
     * @param rawPath 原始（已 URL 編碼）的路徑，原樣轉送
     * @param annotate true 時在回傳的 JSON 加上 accounting server 位址與「透過哪台節點檢視」
     */
    private static void proxyToAcct(ChannelHandlerContext ctx, String method, String rawPath, boolean annotate) {
        // 目前的 accounting server（可能是接手的根網段 broker）；還不知道時先用設定的位址，並在背景重新找
        String known = AcctRole.knownAcct();
        if (known == null) {
            CompletableFuture.runAsync(AcctRole::currentAcct);
        }
        String acct = known != null ? known : HiBAInitial.accountingServer;
        if (acct == null || acct.isBlank()) {
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "這台節點的 env.json 沒有設定 accountingServer");
            return;
        }
        URI uri;
        try {
            uri = URI.create("http://" + acct + ":" + HiBAInitial.HTTP_REQUEST_PORT + rawPath);
        } catch (IllegalArgumentException e) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "路徑不正確");
            return;
        }
        HttpRequest proxied = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        PROXY_CLIENT
                .sendAsync(proxied, java.net.http.HttpResponse.BodyHandlers.ofByteArray()) // Netty 也有 HttpResponse
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY,
                                "連不到 accounting server " + acct + "（" + rootMessage(err) + "）");
                        return;
                    }
                    byte[] body = resp.body();
                    if (annotate && resp.statusCode() == 200) {
                        body = annotate(body, acct);
                    }
                    String type = resp.headers().firstValue("content-type").orElse("application/json; charset=UTF-8");
                    send(ctx, HttpResponseStatus.valueOf(resp.statusCode()), type, body);
                });
    }

    private static byte[] annotate(byte[] body, String acct) {
        try {
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONObject server = json.optJSONObject("accountingServer");
            if (server == null) {
                server = new JSONObject();
                json.put("accountingServer", server);
            }
            server.put("address", acct);
            json.put("viewedVia", new JSONObject()
                    .put("name", HiBAInitial.nodeName == null ? JSONObject.NULL : HiBAInitial.nodeName)
                    .put("ip", HiBANetwork.LOCAL_IP == null ? JSONObject.NULL : HiBANetwork.LOCAL_IP));
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return body;
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        send(ctx, status, "application/json; charset=UTF-8",
                new JSONObject().put("error", message).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendPage(ChannelHandlerContext ctx) {
        sendHtml(ctx, PAGE_RESOURCE);
    }

    private static void sendHtml(ChannelHandlerContext ctx, String resource) {
        byte[] page = loadResource(resource);
        if (page == null) {
            send(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain; charset=UTF-8",
                    (resource + " not found in jar").getBytes(StandardCharsets.UTF_8));
        } else {
            send(ctx, HttpResponseStatus.OK, "text/html; charset=UTF-8", page);
        }
    }

    /** DELETE /api/nodes/{name}：從節點表移除一台離線節點 */
    private static void forget(ChannelHandlerContext ctx, String name) {
        NodeStatusRegistry.ForgetResult result = NodeStatusRegistry.get().forget(name);
        HttpResponseStatus status =
                switch (result) {
                    case REMOVED -> HttpResponseStatus.OK;
                    case NOT_FOUND -> HttpResponseStatus.NOT_FOUND;
                    case STILL_ONLINE -> HttpResponseStatus.CONFLICT;
                };
        byte[] body = new JSONObject()
                .put("name", name)
                .put("result", result.name())
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        send(ctx, status, "application/json; charset=UTF-8", body);
    }

    /** 回傳目前 jar 的 md5，並記下呼叫的節點回報的版本 */
    private static void distMd5(ChannelHandlerContext ctx, FullHttpRequest request) {
        String md5 = DistJar.md5();
        QueryStringDecoder query = new QueryStringDecoder(request.uri());
        String node = param(query, "node");
        if (node != null && !node.isBlank()) {
            NodeStatusRegistry.get()
                    .onVersionCheck(node, param(query, "have"), "1".equals(param(query, "running")), remoteIp(ctx));
        }
        if (md5 == null) {
            send(ctx, HttpResponseStatus.NOT_FOUND, "text/plain; charset=UTF-8",
                    "no jar available\n".getBytes(StandardCharsets.UTF_8));
        } else {
            send(ctx, HttpResponseStatus.OK, "text/plain; charset=UTF-8",
                    (md5 + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 用 FileRegion 直接從檔案送出，不把整個 jar 讀進記憶體 */
    private static void distJar(ChannelHandlerContext ctx) {
        File f = DistJar.file();
        if (f == null) {
            send(ctx, HttpResponseStatus.NOT_FOUND, "text/plain; charset=UTF-8",
                    "no jar available\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        RandomAccessFile raf;
        long length;
        try {
            raf = new RandomAccessFile(f, "r");
            length = raf.length();
        } catch (IOException e) {
            logger.atWarn().log("Cannot open {}: {}", f, e.getMessage());
            send(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain; charset=UTF-8",
                    "cannot read jar\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/java-archive");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        ctx.write(response);
        ctx.write(new DefaultFileRegion(raf.getChannel(), 0, length)); // 傳完後 FileRegion 會關閉檔案
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
    }

    private static String param(QueryStringDecoder query, String name) {
        List<String> values = query.parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String remoteIp(ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress() instanceof InetSocketAddress address
                ? address.getAddress().getHostAddress()
                : null;
    }

    private static byte[] loadResource(String resource) {
        try (InputStream in = NodeStatusHttp.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            logger.atWarn().setCause(e).log("Failed to read {}", resource);
            return null;
        }
    }

    private static void send(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType, byte[] body) {
        FullHttpResponse response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
