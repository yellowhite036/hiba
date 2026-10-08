package org.gslab.handlers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.gslab.message_hiba.RequestFinishMessage;
import org.gslab.model.RequestId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Broker completion handlers - extracted from App.java sendRequest()
 *
 * Handles WaitingRequestMessage and RequestFinishMessage lifecycle
 * Replaces nested anonymous handlers with explicit requestId passing (no MDC)
 */
public class BrokerCompletionHandlers {

    private static final Logger logger = LoggerFactory.getLogger(BrokerCompletionHandlers.class);

    /**
     * Generic debug handler for request finish channel lifecycle
     * Monitors channel state and logs diagnostic information
     */
    public static class CompletionChannelDebugHandler extends ChannelInboundHandlerAdapter {
        private final RequestId requestId;
        private final AtomicReference<String> errorMessage;

        public CompletionChannelDebugHandler(RequestId requestId, AtomicReference<String> errorMessage) {
            this.requestId = requestId;
            this.errorMessage = errorMessage;
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("channel", ctx.channel())
                    .log("RFM_CHAN: Channel Registered");
            super.channelRegistered(ctx);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("channel", ctx.channel())
                    .log("RFM_CHAN: Channel Unregistered");
            super.channelUnregistered(ctx);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("channel", ctx.channel())
                    .log("RFM_CHAN: Channel Active");
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // logger.atError()
            // .addKeyValue("requestId", requestId)
            // .addKeyValue("channel", ctx.channel())
            // .log("RFM_CHAN: Channel Inactive (DISCONNECTED!): This may cause
            // RequestFinishMessage to be lost!");
            errorMessage.set("Channel became inactive");
            super.channelInactive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            logger.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("messageType", msg.getClass().getName())
                    .addKeyValue("message", msg)
                    .log("RFM_CHAN: *** RECEIVED DATA ***");
            // Pass it on to the next handler (RequestFinishMessageHandler)
            super.channelRead(ctx, msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("channel", ctx.channel())
                    .log("RFM_CHAN: Channel Read Complete");
            super.channelReadComplete(ctx);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("channel", ctx.channel())
                    .addKeyValue("event", evt)
                    .log("RFM_CHAN: User Event Triggered");
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("errorMessage", cause.getMessage())
                    .setCause(cause)
                    .log("RFM_CHAN: Exception Caught in GenericDebugHandler");
            errorMessage.set("Exception in channel: " + cause.getMessage());
            super.exceptionCaught(ctx, cause);
        }
    }

    /**
     * Handler for RequestFinishMessage
     * Completes the CompletableFuture with the result
     */
    public static class RequestFinishMessageHandler extends SimpleChannelInboundHandler<RequestFinishMessage> {
        private final RequestId requestId;
        private final String requestName;
        private final CompletableFuture<Map<String, Object>> resultFuture;
        private final AtomicReference<String> errorMessage;

        public RequestFinishMessageHandler(
                RequestId requestId,
                String requestName,
                CompletableFuture<Map<String, Object>> resultFuture,
                AtomicReference<String> errorMessage) {
            this.requestId = requestId;
            this.requestName = requestName;
            this.resultFuture = resultFuture;
            this.errorMessage = errorMessage;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .log("RFM_HANDLER: Channel Active for RequestFinishMessage");
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .log("RFM_HANDLER: Channel Inactive for RequestFinishMessage");
            super.channelInactive(ctx);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void channelRead0(ChannelHandlerContext ctx, RequestFinishMessage msg) {
            logger.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("*** RECEIVED RequestFinishMessage ***");
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestResult", msg.getRequestResult())
                    .log("RequestFinishMessage details");

            try {
                Map<String, Object> resultMsg =
                        (Map<String, Object>) msg.getRequestResult().get("result");
                Map<String, Object> taskDetails =
                        (Map<String, Object>) msg.getRequestResult().get("allResult");

                if (resultMsg == null) {
                    resultMsg = new HashMap<>();
                }

                resultMsg.put("taskDetails", taskDetails);
                resultMsg.put("state", true);

                // Complete the future with the result
                resultFuture.complete(resultMsg);

                logger.atInfo()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .log("*** RequestFinishMessage processed successfully ***");
            } catch (Exception e) {
                logger.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .setCause(e)
                        .log("Error processing taskDetails from RequestFinishMessage");
                errorMessage.set("Error processing RequestFinishMessage: " + e.getMessage());
                resultFuture.completeExceptionally(e);
            }

            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("WaitingRequestMessage channelRead0 complete, closing channel");
            ctx.channel().close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .setCause(cause)
                    .log("Exception in WaitingRequestMessage (RequestFinishMessage) channel handler");
            errorMessage.set("Exception while waiting for/processing RequestFinishMessage: " + cause.getMessage());
            resultFuture.completeExceptionally(cause);
            ctx.close();
        }
    }
}
