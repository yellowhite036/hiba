package org.gslab.handlers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * 客戶端回應處理 Handler
 * <p>
 * 處理從伺服器接收到的回應訊息。
 * 支援同步等待回應的功能。
 * </p>
 *
 * @author TPM Test Project
 */
@Slf4j
public class ClientResponseHandler extends SimpleChannelInboundHandler<String> {

    private final CountDownLatch latch;
    private final AtomicReference<String> response;

    /**
     * 建構客戶端回應處理 Handler
     *
     * @param latch    用於同步等待回應的 CountDownLatch
     * @param response 用於儲存回應的原子引用
     */
    public ClientResponseHandler(CountDownLatch latch, AtomicReference<String> response) {
        this.latch = latch;
        this.response = response;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
        log.info("📩 收到伺服器回應: {}", msg);
        response.set(msg);
        latch.countDown();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("❌ 客戶端發生錯誤: {}", cause.getMessage(), cause);
        latch.countDown();
        ctx.close();
    }
}
