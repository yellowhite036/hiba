package org.gslab.handlers;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.gslab.Distributor;
import org.gslab.HiBAService;
import org.gslab.SdnNetwork;
import org.gslab.Taker;
import org.gslab.util.JsonFileUtil;
import org.gslab.context.RequestContext;
import org.gslab.context.RequestManager;
import org.gslab.message_hiba.RegisteMessage;
import org.gslab.message_hiba.RegisteResponse;
import org.gslab.message_hiba.RequestFinishMessage;
import org.gslab.message_hiba.ResourceUpdateMessage;
import org.gslab.message_hiba.TaskFinishMessage;
import org.gslab.message_hiba.WaitingRequestMessage;
import org.gslab.model.RequestId;
import org.gslab.sdns.FileTransferClient;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;
import org.json.JSONObject;

/**
 * Server-side Netty channel handlers
 * Extracted from App.java to improve code organization
 */
@Slf4j
public class ServerChannelHandlers {

    // ==================== Lifecycle Handler ====================

    public static class ServerLifecycleHandler extends ChannelInboundHandlerAdapter {
        private final SdnNetwork network;
        private final Map<String, Map<String, Object>> resourceList;
        private final ResourceUpdateCallback resourceUpdateCallback;

        @FunctionalInterface
        public interface ResourceUpdateCallback {
            void updateResource();
        }

        @FunctionalInterface
        public interface NodeDisconnectCallback {
            void nodeDisconnectWhenCompute(String nodeName);
        }

        private final NodeDisconnectCallback nodeDisconnectCallback;

        public ServerLifecycleHandler(
                SdnNetwork network,
                Map<String, Map<String, Object>> resourceList,
                ResourceUpdateCallback resourceUpdateCallback,
                NodeDisconnectCallback nodeDisconnectCallback) {
            this.network = network;
            this.resourceList = resourceList;
            this.resourceUpdateCallback = resourceUpdateCallback;
            this.nodeDisconnectCallback = nodeDisconnectCallback;
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            log.atDebug().addKeyValue("channel", ctx.channel()).log("Channel registered");
            super.channelRegistered(ctx);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            log.atDebug().addKeyValue("channel", ctx.channel()).log("Channel unregistered");
            super.channelUnregistered(ctx);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            log.atDebug().addKeyValue("channel", ctx.channel()).log("Channel active");
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            log.atDebug().addKeyValue("channel", ctx.channel()).log("Channel inactive");
            if (network.getClients().containsKey(ctx.channel())) {
                String nodeName = network.getClients().get(ctx.channel()).getKey();
                log.atWarn()
                        .addKeyValue("nodeName", nodeName)
                        .addKeyValue("channel", ctx.channel())
                        .log("Client node disconnected");
                nodeDisconnectCallback.nodeDisconnectWhenCompute(nodeName);
                resourceList.remove(nodeName);
                log.atDebug().addKeyValue("nodeName", nodeName).log("Removed resource for node");
                resourceUpdateCallback.updateResource();
            } else {
                log.atDebug()
                        .addKeyValue("channel", ctx.channel())
                        .log("Disconnected channel was not a registered client");
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.atError().addKeyValue("channel", ctx.channel()).setCause(cause).log("Exception caught in pipeline");
            ctx.close();
        }
    }

    // ==================== Resource Update Handler ====================

    public static class ResourceUpdateHandler extends SimpleChannelInboundHandler<ResourceUpdateMessage> {
        private final SdnNetwork network;
        private final Map<String, Map<String, Object>> resourceList;
        private final ResourceProvider resourceProvider;

        @FunctionalInterface
        public interface ResourceProvider {
            Map<String, Object> getAllResource();
        }

        public ResourceUpdateHandler(
                SdnNetwork network, Map<String, Map<String, Object>> resourceList, ResourceProvider resourceProvider) {
            this.network = network;
            this.resourceList = resourceList;
            this.resourceProvider = resourceProvider;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ResourceUpdateMessage msg) {
            // logger.atDebug()
            // .addKeyValue("remoteAddress", ctx.channel().remoteAddress())
            // .addKeyValue("resource", msg.getResource())
            // .log("Received ResourceUpdateMessage");
            if (network.getClients().containsKey(ctx.channel())) {
                String nodeName = network.getClients().get(ctx.channel()).getKey();
                log.atInfo().log("Node updated resource: {}", msg.getResource());
                resourceList.put(nodeName, msg.getResource());
                log.atInfo().log("Current all resources: {}", resourceProvider.getAllResource());
                log.atDebug().log("Updated resource for node: {}", nodeName);
            } else {
                log.atWarn().log("Received ResourceUpdateMessage from unknown channel: {}", ctx.channel());
            }

            if (network.getBrokerChannel() != null) {
                log.atDebug().log(
                        "Forwarding ResourceUpdateMessage to upstream broker: {}", network.getBrokerChannel());
                network.getBrokerChannel().writeAndFlush(new ResourceUpdateMessage(resourceProvider.getAllResource()));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.atError().setCause(cause).log("Exception caught for channel: {}", ctx.channel());
            ctx.close();
        }
    }

    // ==================== Task Finish Handler ====================

    public static class TaskFinishHandler extends SimpleChannelInboundHandler<TaskFinishMessage> {
        private final SdnNetwork network;
        private final Map<String, Map<String, Object>> taskResultList;

        public TaskFinishHandler(SdnNetwork network, Map<String, Map<String, Object>> taskResultList) {
            this.network = network;
            this.taskResultList = taskResultList;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TaskFinishMessage msg) {
            log.atDebug()
                    .addKeyValue("remoteAddress", ctx.channel().remoteAddress())
                    .addKeyValue("attachment", msg.getAttachment())
                    .log("Received TaskFinishMessage");
            String clientName = network.getClients().get(ctx.channel()).getKey();
            if (clientName != null) {
                taskResultList.put(clientName, msg.getAttachment());
                log.atInfo().addKeyValue("clientName", clientName).log("Client task completed");
                log.atDebug()
                        .addKeyValue("clientName", clientName)
                        .addKeyValue("result", msg.getAttachment())
                        .log("Stored task result for client");
            } else {
                log.atWarn()
                        .addKeyValue("channel", ctx.channel())
                        .log("Received TaskFinishMessage from unknown client channel");
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.atError().addKeyValue("channel", ctx.channel()).setCause(cause).log("Exception caught for channel");
            ctx.close();
        }
    }

    // ==================== Waiting Request Handler ====================

    public static class WaitingRequestHandler extends SimpleChannelInboundHandler<WaitingRequestMessage> {
        private final RequestManager requestManager;

        public WaitingRequestHandler(RequestManager requestManager) {
            this.requestManager = requestManager;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WaitingRequestMessage msg) {
            RequestId requestId = msg.getRequestId();

            log.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("remoteAddress", ctx.channel().remoteAddress())
                    .addKeyValue("requestName", msg.getRequestName())
                    .log("*** RECEIVED WaitingRequestMessage ***");

            log.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("channelId", ctx.channel().id())
                    .addKeyValue("localAddress", ctx.channel().localAddress())
                    .addKeyValue("remoteAddress", ctx.channel().remoteAddress())
                    .log("Channel details");

            // Register waiter channel - always update to ensure latest waiting channel is used
            // This replaces the old ChannelRegistry.registerRequestWaiter() pattern
            requestManager.registerWaiter(msg.getRequestId(), ctx.channel());

            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("channel", ctx.channel())
                    .log("Registered waiter channel");

            log.atInfo().addKeyValue("requestId", requestId).log("waiting list update: waiter channel registered");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.atError().addKeyValue("channel", ctx.channel()).setCause(cause).log("Exception caught for channel");
            ctx.close();
        }
    }

    // ==================== Registration Handler ====================

    public static class RegistrationHandler extends SimpleChannelInboundHandler<RegisteMessage> {
        private final SdnNetwork network;
        private final BlockingQueue<Map<String, Object>> requestQueue;
        private final RequestManager requestManager;
        private final ServiceProvider serviceProvider;
        private final ResourceProvider resourceProvider;
        private final FileTraverser fileTraverser;
        private final LocalTimeProvider localTimeProvider;

        @FunctionalInterface
        public interface ServiceProvider {
            HiBAService getService(String serviceName);
        }

        @FunctionalInterface
        public interface ResourceProvider {
            Map<String, Object> getAllResource();
        }

        @FunctionalInterface
        public interface FileTraverser {
            void traverseDirectory(File directory, ArrayList<String> fileList);
        }

        @FunctionalInterface
        public interface LocalTimeProvider {
            Map<String, Object> getLocalTime();
        }

        public RegistrationHandler(
                SdnNetwork network,
                BlockingQueue<Map<String, Object>> requestQueue,
                RequestManager requestManager,
                ServiceProvider serviceProvider,
                ResourceProvider resourceProvider,
                FileTraverser fileTraverser,
                LocalTimeProvider localTimeProvider) {
            this.network = network;
            this.requestQueue = requestQueue;
            this.requestManager = requestManager;
            this.serviceProvider = serviceProvider;
            this.resourceProvider = resourceProvider;
            this.fileTraverser = fileTraverser;
            this.localTimeProvider = localTimeProvider;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RegisteMessage msg) throws Exception {
            // Extract requestId from message - guaranteed non-null
            Map<String, Object> requestInformation = msg.getRequestInformation();
            RequestId requestId = msg.getRequestId();

            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("remoteAddress", ctx.channel().remoteAddress())
                    .addKeyValue("requestName", msg.getRequestName())
                    .addKeyValue("serviceName", requestInformation.get("serviceName"))
                    .log("Received RegisteMessage");

            String requestName = (String) requestInformation.get("requestName");
            String serviceName = (String) requestInformation.get("serviceName");

            HiBAService service = serviceProvider.getService(serviceName);
            if (service == null) {
                log.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("serviceName", serviceName)
                        .addKeyValue("requestName", requestName)
                        .log("Service not found - Sending failure response");
                ctx.channel()
                        .writeAndFlush(new RegisteResponse(
                                msg.getRequestName(),
                                msg.getRequestId(),
                                false,
                                network.getLocal().getValue()));
                return;
            }

            boolean hasBroker = !(network.getBrokerChannel() == null);
            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("hasBroker", hasBroker)
                    .addKeyValue("requestName", requestName)
                    .log("Upstream broker check");

            Taker taker = service.getTaker();
            taker.setRequestInformation(requestInformation);

            if (taker.checkCanHandleRequest(hasBroker, resourceProvider.getAllResource())) {
                handleLocalRequest(ctx, msg, requestName, serviceName, requestId);
            } else {
                handleForwardRequest(
                        ctx, msg, requestName, serviceName, hasBroker, requestInformation, service, requestId);
            }
        }

        private void handleLocalRequest(
                ChannelHandlerContext ctx,
                RegisteMessage msg,
                String requestName,
                String serviceName,
                RequestId requestId) {
            log.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .addKeyValue("serviceName", serviceName)
                    .log("Local take request");

            // Ensure requestId is in the request map before queuing
            Map<String, Object> requestInfo = msg.getRequestInformation();
            if (msg.getRequestId() != null && !requestInfo.containsKey("requestId")) {
                requestInfo.put("requestId", msg.getRequestId());
            }

            boolean isOffer = requestQueue.offer(requestInfo);
            if (!isOffer) {
                log.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .log("RequestQueue is full. Sending failure RegisteResponse");
                ctx.channel()
                        .writeAndFlush(new RegisteResponse(
                                msg.getRequestName(), msg.getRequestId(), false, "Broker queue is full"));
                return;
            }

            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("remoteAddress", ctx.channel().remoteAddress())
                    .addKeyValue("requestName", requestName)
                    .log("Sending RegisteResponse (success=true)");
            ctx.channel()
                    .writeAndFlush(new RegisteResponse(
                            msg.getRequestName(),
                            msg.getRequestId(),
                            true,
                            network.getLocal().getValue()));
        }

        @SuppressWarnings("unchecked")
        private void handleForwardRequest(
                ChannelHandlerContext ctx,
                RegisteMessage msg,
                String requestName,
                String serviceName,
                boolean hasBroker,
                Map<String, Object> requestInformation,
                HiBAService service,
                RequestId requestId)
                throws Exception {

            log.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .addKeyValue("serviceName", serviceName)
                    .log("Local can't take request - forwarding");

            if (!hasBroker) {
                log.atWarn()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .log("No upstream broker and local cannot handle");
                ctx.channel()
                        .writeAndFlush(new RegisteResponse(
                                msg.getRequestName(),
                                msg.getRequestId(),
                                false,
                                network.getLocal().getValue()));
            } else {
                forwardToUpstreamBroker(ctx, msg, requestName, requestInformation, service, requestId);
            }
        }

        @SuppressWarnings("unchecked")
        private void forwardToUpstreamBroker(
                ChannelHandlerContext ctx,
                RegisteMessage msg,
                String requestName,
                Map<String, Object> requestInformation,
                HiBAService service,
                RequestId requestId)
                throws Exception {

            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("Forwarding RegisteMessage to upstream broker");

            ArrayList<String> brokerList = new ArrayList<>();
            if (requestInformation.containsKey("brokerRoute")) {
                brokerList = (ArrayList<String>) requestInformation.get("brokerRoute");
            }
            brokerList.add(HiBAInitial.nodeName);
            requestInformation.put("brokerRoute", brokerList);

            // Store original request information in RequestContext
            Map<String, Object> originalRequestInfo = new HashMap<>(requestInformation);
            originalRequestInfo.put("requestKey", requestId);

            // Create or update RequestContext with broker route information
            if (!requestManager.hasRequest(requestId)) {
                RequestContext requestContext = RequestContext.builder()
                        .requestId(requestId)
                        .requestName(requestName)
                        .serviceName((String) requestInformation.get("serviceName"))
                        .originChannel(ctx.channel())
                        .brokerRoute(brokerList)
                        .requestData(originalRequestInfo)
                        .build();
                requestManager.registerRequest(requestContext);
                log.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("brokerRoute", brokerList)
                        .log("Created RequestContext with broker route for forwarding");
            }

            msg = new RegisteMessage(
                    msg.getRequestName(), msg.getRequestId(), msg.getJoinTime(), msg.getDeadline(), requestInformation);

            String brokerHost = ((InetSocketAddress) network.getBrokerChannel().remoteAddress()).getHostName();
            log.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("brokerHost", brokerHost)
                    .addKeyValue("requestName", requestName)
                    .log("send request to upper broker");

            AtomicReference<RegisteResponse> temp = new AtomicReference<>();

            // File transfer if needed
            if (requestInformation.containsKey("transferFile")) {
                transferFilesToUpstream(requestInformation, brokerHost, requestName, requestId);
            }

            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("brokerHost", brokerHost)
                    .log("Sending RegisteMessage via sendShortConnectMessage");

            network.sendShortConnectMessage(brokerHost, msg, channel1 -> {
                log.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("localAddress", channel1.localAddress())
                        .addKeyValue("remoteAddress", channel1.remoteAddress())
                        .log("sendShortConnectMessage (for RegisteResponse): Channel initialized to broker");

                channel1.pipeline().addLast(new SimpleChannelInboundHandler<RegisteResponse>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx2, RegisteResponse msg2) {
                        log.atDebug()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("success", msg2.isSuccess())
                                .addKeyValue("handleBrokerHost", msg2.getHandleBrokerHost())
                                .log("Received RegisteResponse");
                        temp.set(msg2);
                        log.atInfo().addKeyValue("requestId", requestId).log("--------------------");
                        log.atInfo()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("brokerHost", msg2.getHandleBrokerHost())
                                .log("The upper node can handle it, and the IP is");
                        log.atDebug().addKeyValue("requestId", requestId).log("Closing channel");
                        ctx2.channel().close();
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx2, Throwable cause) throws Exception {
                        log.atError()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("channel", ctx2.channel())
                                .setCause(cause)
                                .log("Exception caught for channel");
                        temp.set(new RegisteResponse(
                                requestName,
                                requestId,
                                false,
                                "Exception during upstream registration: " + cause.getMessage()));
                        ctx2.close();
                    }
                });
            });

            waitForUpstreamResponse(temp, requestName, requestId);

            if (temp.get() == null) {
                log.atError()
                        .addKeyValue("requestId", requestId)
                        .log("Timeout waiting for upstream RegisteResponse. Sending failure RegisteResponse");
                ctx.channel()
                        .writeAndFlush(new RegisteResponse(
                                msg.getRequestName(),
                                msg.getRequestId(),
                                false,
                                "Timeout waiting for upstream broker response"));
                return;
            }

            RegisteResponse response = temp.get();
            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("success", response.isSuccess())
                    .log("Upstream RegisteResponse received");

            if (response.isSuccess()) {
                handleUpstreamSuccess(ctx, msg, requestName, response, service, requestId);
            } else {
                handleUpstreamFailure(ctx, msg, requestName, response, requestId);
            }
        }

        @SuppressWarnings("unchecked")
        private void transferFilesToUpstream(
                Map<String, Object> requestInformation, String brokerHost, String requestName, RequestId requestId) {
            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("Transferring files for upstream broker");
            String receiveTargetPath = (String) requestInformation.get("receiveTargetPath");
            String sampleDirectory = ((String) requestInformation.get("sampleDirectory")).replaceAll("\\\\.", "");
            String taskFilePath = receiveTargetPath + "/" + sampleDirectory;
            ArrayList<String> transferFileList = new ArrayList<>();
            fileTraverser.traverseDirectory(new File(taskFilePath), transferFileList);
            for (String file : transferFileList) {
                String targetDirPath = new File(file).getParent();
                log.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("file", file)
                        .addKeyValue("targetDirPath", targetDirPath)
                        .addKeyValue("brokerHost", brokerHost)
                        .addKeyValue("requestName", requestName)
                        .log("Transferring file");
                FileTransferClient.doTransfer(file, targetDirPath, brokerHost, null, HiBAInitial.DATA_PORT, requestId);
            }
        }

        private void waitForUpstreamResponse(
                AtomicReference<RegisteResponse> temp, String requestName, RequestId requestId) {
            long waitStartTime = System.currentTimeMillis();
            while (temp.get() == null && (System.currentTimeMillis() - waitStartTime) < 10000) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.atError()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("requestName", requestName)
                            .setCause(e)
                            .log("Interrupted while waiting for upstream RegisteResponse");
                    temp.set(new RegisteResponse(
                            requestName, requestId, false, "Interrupted waiting for upstream broker response"));
                    break;
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void handleUpstreamSuccess(
                ChannelHandlerContext ctx,
                RegisteMessage msg,
                String requestName,
                RegisteResponse response,
                HiBAService service,
                RequestId requestId) {
            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("remoteAddress", ctx.channel().remoteAddress())
                    .log("Upstream broker accepted. Sending RegisteResponse (success=true) to original requester");
            ctx.channel()
                    .writeAndFlush(new RegisteResponse(
                            msg.getRequestName(), msg.getRequestId(), true, response.getHandleBrokerHost()));

            log.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("handleBrokerHost", response.getHandleBrokerHost())
                    .log("Request handle success");
            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("handleBrokerHost", response.getHandleBrokerHost())
                    .log("Sending WaitingRequestMessage");

            setupRequestFinishListener(requestName, response.getHandleBrokerHost(), service, requestId);
        }

        @SuppressWarnings("unchecked")
        private void setupRequestFinishListener(
                String requestName, String handleBrokerHost, HiBAService service, RequestId requestId) {
            try {
                network.sendShortConnectMessage(
                        handleBrokerHost, new WaitingRequestMessage(requestName, requestId), channel2 -> {
                            log.atDebug()
                                    .addKeyValue("requestId", requestId)
                                    .addKeyValue("handleBrokerHost", handleBrokerHost)
                                    .addKeyValue("localAddress", channel2.localAddress())
                                    .addKeyValue("remoteAddress", channel2.remoteAddress())
                                    .addKeyValue("requestName", requestName)
                                    .log("sendShortConnectMessage (for RequestFinishMessage): Channel initialized");

                            channel2.pipeline().addLast(new SimpleChannelInboundHandler<RequestFinishMessage>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx2, RequestFinishMessage msg2) {
                                    log.atDebug()
                                            .addKeyValue("requestId", requestId)
                                            .addKeyValue("requestName", requestName)
                                            .log("Received RequestFinishMessage");

                                    // Process the message and get upperBrokerResponse
                                    Map<String, Object> upperBrokerResponse =
                                            processRequestFinishMessage(msg2, requestName, requestId);

                                    // Forward to original requester (Node9)
                                    handleBrokerRouteAndFinish(requestName, service, upperBrokerResponse, requestId);

                                    log.atDebug()
                                            .addKeyValue("requestId", requestId)
                                            .addKeyValue("requestName", requestName)
                                            .log("Closing channel");
                                    ctx2.channel().close();
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx2, Throwable cause)
                                        throws Exception {
                                    log.atError()
                                            .addKeyValue("requestId", requestId)
                                            .addKeyValue("channel", ctx2.channel())
                                            .addKeyValue("requestName", requestName)
                                            .setCause(cause)
                                            .log("Exception caught for channel");
                                    ctx2.close();
                                }
                            });
                        });
            } catch (Exception e) {
                log.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .setCause(e)
                        .log("Failed to setup request finish listener");
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> processRequestFinishMessage(
                RequestFinishMessage msg2, String requestName, RequestId requestId) {
            try {
                log.atInfo()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestResult", msg2.getRequestResult())
                        .log("msg2.getRequestResult()");
                Map<String, Object> info =
                        (Map<String, Object>) msg2.getRequestResult().get("result");
                Map<String, Object> allResult = (Map<String, Object>) info.get("allResult");
                info.put("taskDetails", allResult);

                Map<String, Object> upperBrokerResponse = new HashMap<>();
                upperBrokerResponse.putAll(info);

                Object startTimeObj = upperBrokerResponse.get("requestTimeStamp");
                long startTime = startTimeObj != null ? (long) startTimeObj : System.currentTimeMillis();
                Map<String, Object> localTime = localTimeProvider.getLocalTime();
                Object endTimeObj = localTime.get("timeStamp");
                long endTime = endTimeObj != null ? (long) endTimeObj : System.currentTimeMillis();
                double executionTime = (endTime - startTime) / 1000.000;

                upperBrokerResponse.put("taskCompletionTime", endTime);
                upperBrokerResponse.put("taskCompletionTimeStamp", localTime.get("timeStamp"));
                upperBrokerResponse.put("taskExecutionTime", executionTime);

                String responseFile =
                        "./response" + File.separator + requestName + "_" + localTime.get("date") + ".json";
                JsonFileUtil.WriteFile(new JSONObject(upperBrokerResponse), new File(responseFile));

                log.atInfo()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("responseFile", responseFile)
                        .addKeyValue("executionTime", executionTime)
                        .addKeyValue("result", upperBrokerResponse)
                        .log("Request completed successfully");

                log.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .log("Processed RequestFinishMessage");

                return upperBrokerResponse;
            } catch (Exception e) {
                log.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .setCause(e)
                        .log("Error in processRequestFinishMessage");
                return new HashMap<>();
            }
        }

        @SuppressWarnings("unchecked")
        private void handleBrokerRouteAndFinish(
                String requestName, HiBAService service, Map<String, Object> upperBrokerResponse, RequestId requestId) {
            // Try to extract requestId from the result if available as fallback
            RequestId requestIdFromResult = null;
            if (upperBrokerResponse != null) {
                // Result structure can have requestId in different places
                requestIdFromResult = (RequestId) upperBrokerResponse.get("requestId");

                // Also check in nested "result" map if present
                if (requestIdFromResult == null && upperBrokerResponse.containsKey("result")) {
                    Map<String, Object> nestedResult = (Map<String, Object>) upperBrokerResponse.get("result");
                    if (nestedResult != null) {
                        requestIdFromResult = (RequestId) nestedResult.get("requestId");
                    }
                }
            }

            // Prioritize: explicit requestId parameter > result > requestName
            RequestId lookupKey = requestId != null
                    ? requestId
                    : (requestIdFromResult != null ? requestIdFromResult : RequestId.of(requestName));

            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("lookupKey", lookupKey)
                    .addKeyValue("requestIdFromParameter", requestId)
                    .addKeyValue("requestIdFromResult", requestIdFromResult)
                    .addKeyValue("requestName", requestName)
                    .log("handleBrokerRouteAndFinish: Attempting lookup");

            Map<String, Object> storedRequestInfo = requestManager.getOriginalRequestInfo(lookupKey);

            // Fallback: if lookup by requestId failed, try requestName
            if (storedRequestInfo == null && !lookupKey.value().equals(requestName)) {
                log.atWarn()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("lookupKey", lookupKey)
                        .addKeyValue("requestName", requestName)
                        .log("Lookup by key failed, falling back to requestName");
                storedRequestInfo = requestManager.getOriginalRequestInfo(RequestId.of(requestName));
                lookupKey = RequestId.of(requestName);
            }

            ArrayList<String> brokerRouteList = null;
            RequestId actualRequestKey = lookupKey;

            if (storedRequestInfo != null) {
                brokerRouteList = (ArrayList<String>) storedRequestInfo.get("brokerRoute");

                actualRequestKey = (RequestId) storedRequestInfo.get("requestKey");
                log.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("lookupKey", lookupKey)
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("brokerRoute", brokerRouteList)
                        .addKeyValue("actualRequestKey", actualRequestKey)
                        .log("Retrieved original broker route from stored request info");
            } else {
                log.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("lookupKey", lookupKey)
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("availableKeys", requestManager.getActiveRequestIds())
                        .log("Failed to retrieve storedRequestInfo");
            }

            if (brokerRouteList == null || brokerRouteList.isEmpty()) {
                log.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("lookupKey", lookupKey)
                        .addKeyValue("requestName", requestName)
                        .log("brokerRouteList is null or empty in original request information");

                // Try waiter channel first, fallback to originChannel
                Channel requestNodeChannelOnError = requestManager.getWaiterChannel(requestId);
                if (requestNodeChannelOnError == null) {
                    RequestContext errorCtx = requestManager.getRequest(actualRequestKey);
                    requestNodeChannelOnError = errorCtx != null ? errorCtx.originChannel : null;
                }

                if (requestNodeChannelOnError != null) {
                    requestNodeChannelOnError.writeAndFlush(new RequestFinishMessage(
                            createErrorResult("Broker route list missing in original request information")));
                }
                // Cleanup both RequestContext and waiter
                requestManager.removeRequest(actualRequestKey);
                if (!actualRequestKey.equals(lookupKey)) {
                    requestManager.removeRequest(lookupKey);
                }
                requestManager.removeWaiter(requestId);
                return;
            }

            String currentBrokerRoute = brokerRouteList.getFirst();
            log.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("currentBrokerRoute", currentBrokerRoute)
                    .log("------------- Current broker route -------------");
            log.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("currentBrokerRoute", currentBrokerRoute)
                    .addKeyValue("requestName", requestName)
                    .log("Current broker route");

            RequestContext requestCtx = requestManager.getRequest(actualRequestKey);

            // CRITICAL FIX: Use waiter channel (registered by WaitingRequestMessage) instead of originChannel
            // originChannel may be from RegisteMessage, which is a different channel
            Channel requestNodeChannel = requestManager.getWaiterChannel(requestId);

            // Fallback to originChannel if no waiter registered (shouldn't happen in normal flow)
            if (requestNodeChannel == null && requestCtx != null) {
                requestNodeChannel = requestCtx.originChannel;
                log.atWarn()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("actualRequestKey", actualRequestKey)
                        .log("No waiter channel found, falling back to RequestContext.originChannel");
            }

            if (requestNodeChannel == null) {
                log.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("actualRequestKey", actualRequestKey)
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("availableKeys", requestManager.getActiveRequestIds())
                        .log(
                                "No requestNodeChannel found (neither waiter nor originChannel). Cannot send RequestFinishMessage");
                // Use lookupKey for cleanup
                requestManager.removeRequest(lookupKey);
                requestManager.removeWaiter(requestId); // Clean up waiter too
                return;
            }

            log.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("channelInfo", requestNodeChannel)
                    .addKeyValue("channelActive", requestNodeChannel.isActive())
                    .log("Using channel for RequestFinishMessage");

            // Get distributor and set work information
            Distributor distributor = service.getDistributor();
            distributor.setWorkInformation(upperBrokerResponse);

            Map<String, Object> result;
            try {
                if (currentBrokerRoute.equals(HiBAInitial.nodeName)) {
                    // Current broker is the target - handle locally
                    log.atDebug()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("requestName", requestName)
                            .log("Current broker is self. Getting result locally");
                    result = distributor.getResult(null, requestNodeChannel, null);
                } else {
                    // Need to forward to lower broker
                    log.atDebug()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("requestName", requestName)
                            .log("Current broker is different. Getting result for lower broker");
                    Map<String, Object> lowerBrokerInfo =
                            (Map<String, Object>) HiBANetwork.reverseChannelList.get(currentBrokerRoute);

                    if (lowerBrokerInfo == null) {
                        log.atError()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("currentBrokerRoute", currentBrokerRoute)
                                .addKeyValue("requestName", requestName)
                                .log("Lower broker info not found in reverseChannelList");
                        requestNodeChannel.writeAndFlush(new RequestFinishMessage(
                                createErrorResult("Lower broker info not found for route: " + currentBrokerRoute)));
                        // Cleanup both RequestContext and waiter
                        requestManager.removeRequest(actualRequestKey);
                        if (!actualRequestKey.equals(lookupKey)) {
                            requestManager.removeRequest(lookupKey);
                        }
                        requestManager.removeWaiter(requestId);
                        return;
                    }

                    String lowerBrokerPort = (String) lowerBrokerInfo.get("port");
                    result = distributor.getResult(null, requestNodeChannel, Integer.valueOf(lowerBrokerPort));
                }

                // Send result back to requesting node
                log.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("result", result)
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("channelInfo", requestNodeChannel)
                        .log("Sending final RequestFinishMessage to original requester");
                requestNodeChannel.writeAndFlush(new RequestFinishMessage(result));

                // Clean up both RequestContext and waiter
                requestManager.removeRequest(actualRequestKey);
                if (!actualRequestKey.equals(lookupKey)) {
                    requestManager.removeRequest(lookupKey);
                }
                requestManager.removeWaiter(requestId); // Clean up waiter channel

                log.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("lookupKey", lookupKey)
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("actualRequestKey", actualRequestKey)
                        .log("Removed waiter and cleaned up original request info");

            } catch (Exception e) {
                log.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .setCause(e)
                        .log("Error in handleBrokerRouteAndFinish");
                // Try to send error response
                try {
                    requestNodeChannel.writeAndFlush(new RequestFinishMessage(
                            createErrorResult("Error processing broker route: " + e.getMessage())));
                    // Cleanup both RequestContext and waiter
                    requestManager.removeRequest(actualRequestKey);
                    if (!actualRequestKey.equals(lookupKey)) {
                        requestManager.removeRequest(lookupKey);
                    }
                    requestManager.removeWaiter(requestId); // Clean up waiter channel
                } catch (Exception e2) {
                    log.atError()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("requestName", requestName)
                            .setCause(e2)
                            .log("Failed to send error response");
                }
            }
        }

        private void handleUpstreamFailure(
                ChannelHandlerContext ctx,
                RegisteMessage msg,
                String requestName,
                RegisteResponse response,
                RequestId requestId) {
            log.atWarn()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .addKeyValue("brokerInfo", response.getHandleBrokerHost())
                    .log("Upstream broker registration failed. Sending RegisteResponse (success=false)");
            ctx.channel()
                    .writeAndFlush(new RegisteResponse(
                            msg.getRequestName(), msg.getRequestId(), false, response.getHandleBrokerHost()));
            log.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("can't handle request");
        }

        private Map<String, Object> createErrorResult(String errorMessage) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("error", errorMessage);
            return errorResult;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.atError().addKeyValue("channel", ctx.channel()).setCause(cause).log("Exception caught for channel");
            ctx.close();
        }
    }
}
