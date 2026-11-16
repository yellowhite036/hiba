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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 節點狀態頁的 HTTP 路由（只在 accounting server 上有效）：
 * <ul>
 * <li>GET / 、GET /nodes → 狀態網頁（resources/web/nodes.html）</li> * <li>GET /api/nodes → 狀態 JSON（安裝腳本也會讀它）</li>
 * <li>DELETE /api/nodes/{name} → 從節點表移除一台離線節點（在線的回 409）</li>
 * <li>GET /dist/hiba-core-0.0.1.jar.md5?node=&amp;have=&amp;running= → 目前提供的 jar 的 md5；
 *     節點的自動更新程式（hiba_autoupdate.sh）每次檢查時呼叫，順便回報自己的版本</li>
 * <li>GET /dist/hiba-core-0.0.1.jar → 下載 jar（見 {@link DistJar}）</li>
 * </ul>
 */
public final class NodeStatusHttp {

    private static final Logger logger = LoggerFactory.getLogger(NodeStatusHttp.class);
    private static final String PAGE_RESOURCE = "/web/nodes.html";
    private static final String NODE_PATH_PREFIX = "/api/nodes/";
    private static final String DIST_JAR_PATH = "/dist/" + DistJar.FILE_NAME;
    private static final String DIST_MD5_PATH = DIST_JAR_PATH + ".md5";

    private NodeStatusHttp() {}

    /** @return true 表示已處理這個請求（呼叫端不要再處理） */
    public static boolean handle(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!NodeStatusRegistry.get().isEnabled()) {
            return false;
        }
        String path = request.uri();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
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
