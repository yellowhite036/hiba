package org.gslab.handlers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.gslab.Distributor;
import org.gslab.Executor;
import org.gslab.HiBAService;
import org.gslab.SdnNetwork;
import org.gslab.context.RequestContext;
import org.gslab.context.RequestManager;
import org.gslab.message_hiba.ResourceUpdateMessage;
import org.gslab.message_hiba.TaskFinishMessage;
import org.gslab.message_hiba.TaskStartMessage;
import org.gslab.model.RequestId;
import org.gslab.model.TaskAssignment;
import org.gslab.service.mService.multicast.MulticastController;
import org.gslab.task.TaskDataAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side Netty channel handlers
 * Extracted from App.java to improve code organization
 */
public class ClientChannelHandlers {

    private static final Logger logger = LoggerFactory.getLogger(ClientChannelHandlers.class);

    // ==================== Lifecycle Handler ====================

    public static class ClientLifecycleHandler extends ChannelInboundHandlerAdapter {
        private final SdnNetwork network;
        private final ResourceProvider resourceProvider;
        private final DistributorProvider distributorProvider;
        private final ThreadProvider threadProvider;
        private ExecutorService taskExecutor;

        @FunctionalInterface
        public interface ResourceProvider {
            Map<String, Object> getAllResource();
        }

        @FunctionalInterface
        public interface DistributorProvider {
            Distributor getDistributor();
        }

        @FunctionalInterface
        public interface ThreadProvider {
            Thread getDistributeThread();
        }

        @FunctionalInterface
        public interface TaskExecutorProvider {
            ExecutorService getTaskExecutor();
        }

        @FunctionalInterface
        public interface TaskExecutorSetter {
            void setTaskExecutor(ExecutorService executor);
        }

        private final TaskExecutorProvider taskExecutorProvider;
        private final TaskExecutorSetter taskExecutorSetter;

        public ClientLifecycleHandler(
                SdnNetwork network,
                ResourceProvider resourceProvider,
                DistributorProvider distributorProvider,
                ThreadProvider threadProvider,
                TaskExecutorProvider taskExecutorProvider,
                TaskExecutorSetter taskExecutorSetter) {
            this.network = network;
            this.resourceProvider = resourceProvider;
            this.distributorProvider = distributorProvider;
            this.threadProvider = threadProvider;
            this.taskExecutorProvider = taskExecutorProvider;
            this.taskExecutorSetter = taskExecutorSetter;
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            logger.atDebug().addKeyValue("channel", ctx.channel()).log("Channel registered");
            super.channelRegistered(ctx);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            logger.atDebug().addKeyValue("channel", ctx.channel()).log("Channel unregistered");
            super.channelUnregistered(ctx);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.atDebug()
                    .addKeyValue("channel", ctx.channel())
                    .log("Channel active. Sending initial ResourceUpdateMessage.");
            ctx.channel().writeAndFlush(new ResourceUpdateMessage(resourceProvider.getAllResource()));
            logger.atDebug()
                    .addKeyValue("resources", resourceProvider.getAllResource())
                    .log("Sent ResourceUpdateMessage");
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            logger.atWarn()
                    .addKeyValue("channel", ctx.channel())
                    .log("Channel inactive (connection to broker lost). Shutting down taskExecutor.");

            ExecutorService executor = taskExecutorProvider.getTaskExecutor();
            executor.shutdownNow();
            taskExecutorSetter.setTaskExecutor(Executors.newVirtualThreadPerTaskExecutor());

            logger.atDebug().log("taskExecutor shut down and re-initialized.");

            Distributor distributor = distributorProvider.getDistributor();
            if (distributor != null && distributor.getBrokerTier() != 0) {
                logger.atWarn()
                        .addKeyValue("distributorTier", distributor.getBrokerTier())
                        .log("Interrupting distributeThread as this node was a sub-broker");
                threadProvider.getDistributeThread().interrupt();
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.atError()
                    .addKeyValue("channel", ctx.channel())
                    .addKeyValue("errorMessage", cause.getMessage())
                    .setCause(cause)
                    .log("Exception caught in pipeline");
            ctx.close();
        }
    }

    // ==================== Task Start Handler ====================

    public static class TaskStartHandler extends SimpleChannelInboundHandler<TaskStartMessage> {
        private final SdnNetwork network;
        private final BlockingQueue<Map<String, Object>> requestQueue;
        private final RequestManager requestManager;
        private final ExecutorService taskExecutor;
        private final ServiceProvider serviceProvider;

        @FunctionalInterface
        public interface ServiceProvider {
            HiBAService getService(String serviceName);
        }

        public TaskStartHandler(
                SdnNetwork network,
                BlockingQueue<Map<String, Object>> requestQueue,
                RequestManager requestManager,
                ExecutorService taskExecutor,
                ServiceProvider serviceProvider) {
            this.network = network;
            this.requestQueue = requestQueue;
            this.requestManager = requestManager;
            this.taskExecutor = taskExecutor;
            this.serviceProvider = serviceProvider;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TaskStartMessage msg) {
            logger.atDebug()
                    .addKeyValue("remoteAddress", ctx.channel().remoteAddress())
                    .addKeyValue("requestName", msg.getRequestName())
                    .addKeyValue("requestId", msg.getRequestId())
                    .addKeyValue("taskInfo", msg.getTaskInformation())
                    .log("Received TaskStartMessage");

            if (network.getClients().isEmpty()) {
                handleWorkerNode(ctx, msg);
            } else {
                handleSubBrokerNode(ctx, msg);
            }
        }

        private void handleWorkerNode(ChannelHandlerContext ctx, TaskStartMessage msg) {
            logger.atDebug()
                    .addKeyValue("requestId", msg.getRequestId())
                    .log("This node is a worker (no clients). Submitting task for execution.");

            taskExecutor.submit(() -> {
                RequestId requestId = msg.getRequestId();

                Map<String, Object> taskInformationMap = msg.getTaskInformation();
                TaskAssignment taskAssignment = TaskDataAdapter.mapToTaskAssignment(taskInformationMap);

                String requestName = taskAssignment.getRequestName();
                String serviceName = taskAssignment.getServiceName();
                int tier = taskAssignment.getTier();

                try {
                    logger.atDebug()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("requestName", requestName)
                            .addKeyValue("serviceName", serviceName)
                            .addKeyValue("tier", tier)
                            .log("Starting task execution.");

                    logger.atInfo()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("task", requestName)
                            .addKeyValue("service", serviceName)
                            .log("start task");
                    HiBAService service = serviceProvider.getService(serviceName);

                    if (service == null) {
                        logger.atError()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("serviceName", serviceName)
                                .addKeyValue("requestName", requestName)
                                .log("Service not found for task. Cannot execute.");
                        return;
                    }

                    Executor executor = service.getExecutor();
                    Map<String, Object> executorTaskMap = TaskDataAdapter.taskAssignmentToMap(taskAssignment);
                    executor.setTaskInformation(executorTaskMap);
                    executor.setTier(tier);

                    Map<String, Object> taskResult = executor.execute(executorTaskMap, ctx.channel());
                    logger.atInfo()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("task", requestName)
                            .addKeyValue("service", serviceName)
                            .log("end task");
                    logger.atDebug()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("requestName", requestName)
                            .addKeyValue("serviceName", serviceName)
                            .addKeyValue("result", taskResult)
                            .log("Task execution finished.");
                    logger.atDebug()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("remoteAddress", ctx.channel().remoteAddress())
                            .addKeyValue("requestName", requestName)
                            .log("Sending TaskFinishMessage");
                    ctx.channel().writeAndFlush(new TaskFinishMessage(taskResult));
                } catch (Exception e) {
                    logger.atError()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("requestName", requestName)
                            .addKeyValue("serviceName", serviceName)
                            .setCause(e)
                            .log("Task execution failed.");
                }
            });
        }

        private void handleSubBrokerNode(ChannelHandlerContext ctx, TaskStartMessage msg) {
            logger.atDebug()
                    .addKeyValue("requestId", msg.getRequestId())
                    .log("This node is a sub-broker (has clients). Offering task to requestQueue.");

            // Ensure requestId is in the task map before queuing
            Map<String, Object> taskInfo = msg.getTaskInformation();
            if (msg.getRequestId() != null && !taskInfo.containsKey("requestId")) {
                taskInfo.put("requestId", msg.getRequestId());
            }

            boolean isOffer = requestQueue.offer(taskInfo);

            if (MulticastController.getMode().equals("isBroker")) {
                logger.atDebug()
                        .addKeyValue("requestId", msg.getRequestId())
                        .log("Node mode isBroker. Registering RequestContext with upstreamBrokerChannel.");

                // Create RequestContext for this sub-broker node if it doesn't exist
                if (!requestManager.hasRequest(msg.getRequestId())) {
                    TaskAssignment taskAssignment = TaskDataAdapter.mapToTaskAssignment(taskInfo);
                    RequestContext brokerContext = RequestContext.builder()
                            .requestId(msg.getRequestId())
                            .requestName(taskAssignment.getRequestName())
                            .serviceName(taskAssignment.getServiceName())
                            .originChannel(ctx.channel()) // Use current channel for result sending
                            .upstreamBrokerChannel(ctx.channel()) // Store upper broker channel
                            .requestData(taskInfo)
                            .build();
                    requestManager.registerRequest(brokerContext);
                    logger.atDebug()
                            .addKeyValue("requestId", msg.getRequestId())
                            .log("Created RequestContext on sub-broker with upstreamBrokerChannel");
                } else {
                    logger.atDebug()
                            .addKeyValue("requestId", msg.getRequestId())
                            .log("RequestContext already exists for this requestId");
                }
            }

            if (!isOffer) {
                logger.atError().log("Queue is full!");
                logger.atError()
                        .addKeyValue("requestId", msg.getRequestName())
                        .log("RequestQueue is full. Cannot add task");
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.atError()
                    .addKeyValue("channel", ctx.channel())
                    .addKeyValue("errorMessage", cause.getMessage())
                    .setCause(cause)
                    .log("Exception caught for channel");
            ctx.close();
        }
    }
}
