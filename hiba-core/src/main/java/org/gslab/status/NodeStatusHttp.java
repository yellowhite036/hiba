package org.gslab.status;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 節點狀態頁的 HTTP 路由（只在 accounting server 上有效）：
 * <ul>
 * <li>GET / 、GET /nodes → 狀態網頁（resources/web/nodes.html）</li>
 * <li>GET /api/nodes → 狀態 JSON（安裝腳本也會讀它）</li>
 * </ul>
 */
public final class NodeStatusHttp {

    private static final Logger logger = LoggerFactory.getLogger(NodeStatusHttp.class);
    private static final String PAGE_RESOURCE = "/web/nodes.html";

    private NodeStatusHttp() {}

    /** @return true 表示已處理這個請求（呼叫端不要再處理） */
    public static boolean handle(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!NodeStatusRegistry.get().isEnabled() || !HttpMethod.GET.equals(request.method())) {
            return false;
        }
        String path = request.uri();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        switch (path) {
            case "/":
            case "/nodes":
            case "/nodes/":
                byte[] page = loadPage();
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
            default:
                return false;
        }
    }

    private static byte[] loadPage() {
        try (InputStream in = NodeStatusHttp.class.getResourceAsStream(PAGE_RESOURCE)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            logger.atWarn().setCause(e).log("Failed to read {}", PAGE_RESOURCE);
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
