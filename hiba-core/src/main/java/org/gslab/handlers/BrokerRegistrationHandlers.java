package org.gslab.handlers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.concurrent.CompletableFuture;
import org.gslab.message_hiba.RegisteResponse;
import org.gslab.model.RequestId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Broker registration handlers - extracted from App.java sendRequest()
 *
 * Handles RegisteMessage/RegisteResponse lifecycle with CompletableFuture
 * Replaces nested anonymous handlers with explicit requestId passing (no MDC)
 */
public class BrokerRegistrationHandlers {

    private static final Logger logger = LoggerFactory.getLogger(BrokerRegistrationHandlers.class);

    /**
     * Lifecycle handler for RegisteMessage channel
     * Handles exceptionCaught events.
     *
     * Note: the channel must NOT be closed in channelReadComplete. That event fires after every
     * socket read batch, even when the frame decoder has only received part of a message. A signed
     * RegisteResponse (~1.7 KB) spans two TCP segments; if the second segment arrives a few ms later
     * (e.g. over Wi-Fi), closing here drops it and the request times out. The channel is closed by
     * RegistrationResponseHandler once a complete RegisteResponse has been decoded.
     */
    public static class RegistrationLifecycleHandler extends ChannelInboundHandlerAdapter {
        private final RequestId requestId;
        private final String requestName;

        public RegistrationLifecycleHandler(RequestId requestId, String requestName) {
            this.requestId = requestId;
            this.requestName = requestName;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .setCause(cause)
                    .log("Exception in RegisteMessage channel handler");
            ctx.close();
        }
    }

    /**
     * Handler for RegisteResponse messages
     * Completes the CompletableFuture with the response
     */
    public static class RegistrationResponseHandler extends SimpleChannelInboundHandler<RegisteResponse> {
        private final RequestId requestId;
        private final String requestName;
        private final CompletableFuture<RegisteResponse> responseFuture;

        public RegistrationResponseHandler(
                RequestId requestId, String requestName, CompletableFuture<RegisteResponse> responseFuture) {
            this.requestId = requestId;
            this.requestName = requestName;
            this.responseFuture = responseFuture;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RegisteResponse msg) {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("success", msg.isSuccess())
                    .addKeyValue("handleBrokerHost", msg.getHandleBrokerHost())
                    .addKeyValue("requestName", requestName)
                    .log("Received RegisteResponse");
            logger.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("success", msg.isSuccess())
                    .addKeyValue("brokerIP", ctx.channel().remoteAddress())
                    .log("Registe Result");

            // Complete the future with the response
            responseFuture.complete(msg);

            // Close only after a complete RegisteResponse has been received
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("RegisteResponse received, closing channel");
            ctx.channel().close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // Connection closed before a complete RegisteResponse arrived: fail fast instead of
            // waiting for the caller's timeout. No effect if the future is already completed.
            responseFuture.completeExceptionally(
                    new java.io.IOException("Connection closed before RegisteResponse was received"));
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .setCause(cause)
                    .log("Exception in RegisteResponse handler");
            responseFuture.completeExceptionally(cause);
            ctx.close();
        }
    }
}
