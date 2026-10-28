package org.gslab.sdns;

import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.gslab.Distributor;
import org.gslab.Executor;
import org.gslab.HiBAService;
import org.gslab.context.RequestContext;
import org.gslab.context.RequestManager;
import org.gslab.message_hiba.RequestFinishMessage;
import org.gslab.message_hiba.TaskStartMessage;
import org.gslab.model.RequestId;
import org.gslab.model.TaskAssignment;
import org.gslab.model.TaskRequest;
import org.gslab.task.TaskDataAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated class for distribution thread execution logic
 * Extracted from App.java to improve readability and maintainability
 */
public class DistributionThreadExecutor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(DistributionThreadExecutor.class);

    private final HiBANetwork network;
    private final BlockingQueue<Map<String, Object>> requestQueue;
    private final Map<String, Map<String, Object>> resourceList;
    private final Map<String, Map<String, Object>> taskDistributeList;
    private final Map<String, Map<String, Object>> taskResultList;
    private final RequestManager requestManager;
    private final ArrayList<Map<String, Object>> failedTaskList;
    private final TaskExecutorProvider taskExecutorProvider;
    private final Map<String, HiBAService> serviceList;
    private final Map<String, Object> localResource;
    private final AtomicInteger distributeNodes;

    private Distributor distributor = null;
    private Boolean reassignTask = false;

    // Callback to get service from App
    private final ServiceProvider serviceProvider;

    @FunctionalInterface
    public interface ServiceProvider {
        HiBAService getService(String serviceName);
    }

    // Callback to get all resources
    private final ResourceProvider resourceProvider;

    @FunctionalInterface
    public interface ResourceProvider {
        Map<String, Object> getAllResource();
    }

    // Callback to output response
    private final ResponseOutputter responseOutputter;

    @FunctionalInterface
    public interface ResponseOutputter {
        void outputResponse(String requestName, RequestId requestId, Map<String, Object> result);
    }

    // Node disconnect handler
    private final NodeDisconnectHandler nodeDisconnectHandler;

    @FunctionalInterface
    public interface NodeDisconnectHandler {
        void handleNodeDisconnect(String nodeName);
    }

    // Callback to get task executor dynamically (to handle executor replacement)
    @FunctionalInterface
    public interface TaskExecutorProvider {
        ExecutorService getTaskExecutor();
    }

    public DistributionThreadExecutor(
            HiBANetwork network,
            BlockingQueue<Map<String, Object>> requestQueue,
            Map<String, Map<String, Object>> resourceList,
            Map<String, Map<String, Object>> taskDistributeList,
            Map<String, Map<String, Object>> taskResultList,
            RequestManager requestManager,
            ArrayList<Map<String, Object>> failedTaskList,
            TaskExecutorProvider taskExecutorProvider,
            Map<String, HiBAService> serviceList,
            Map<String, Object> localResource,
            AtomicInteger distributeNodes,
            ServiceProvider serviceProvider,
            ResourceProvider resourceProvider,
            ResponseOutputter responseOutputter,
            NodeDisconnectHandler nodeDisconnectHandler) {

        logger.atDebug()
                .addKeyValue("network", network != null ? network.getClass().getSimpleName() : "NULL")
                .log("DistributionThreadExecutor: Initializing");
        if (network != null) {
            logger.atDebug()
                    .addKeyValue("clientCount", network.getClients().size())
                    .log("DistributionThreadExecutor: Network has clients");
        }

        this.network = network;
        this.requestQueue = requestQueue;
        this.resourceList = resourceList;
        this.taskDistributeList = taskDistributeList;
        this.taskResultList = taskResultList;
        this.requestManager = requestManager;
        this.failedTaskList = failedTaskList;
        this.taskExecutorProvider = taskExecutorProvider;
        this.serviceList = serviceList;
        this.localResource = localResource;
        this.distributeNodes = distributeNodes;
        this.serviceProvider = serviceProvider;
        this.resourceProvider = resourceProvider;
        this.responseOutputter = responseOutputter;
        this.nodeDisconnectHandler = nodeDisconnectHandler;
    }

    @Override
    public void run() {
        logger.atDebug().log("Distribute thread started");
        while (true) {
            try {
                logger.atDebug().log("Waiting to take request from queue");
                Map<String, Object> requestMap = requestQueue.take();

                // Get requestId - guaranteed non-null from HTTP layer
                // RequestId requestId = RequestId.of((String) requestMap.get("requestId"));
                RequestId requestId = (RequestId) requestMap.get("requestId");

                logger.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestMap", requestMap)
                        .log("Took request from queue");
                processRequest(requestMap);

            } catch (InterruptedException e) {
                logger.atError().log("Broker Distribute Shutdown");
                logger.atError().setCause(e).log("Interrupted. Shutting down distribute thread");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.atError().setCause(e).log("Uncaught exception in distribute thread loop");
                e.printStackTrace();
            }
        }
        logger.atDebug().log("Distribute thread exiting");
    }

    private void processRequest(Map<String, Object> requestMap) throws Exception {
        TaskRequest request = TaskDataAdapter.mapToTaskRequest(requestMap);
        String serviceName = request.getServiceName();
        String requestName = request.getRequestName();
        RequestId requestId = request.getRequestId();
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .addKeyValue("serviceName", serviceName)
                .log("Processing request");

        if (network.getClients().isEmpty()) {
            handleNoClients(requestId);
            return;
        }

        HiBAService service = serviceProvider.getService(serviceName);
        if (service == null) {
            handleServiceNotFound(serviceName, requestId);
            return;
        }

        distributeAndExecute(request, service);
    }

    private void handleNoClients(RequestId requestId) {
        logger.atDebug().addKeyValue("requestId", requestId).log("No clients connected");
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("availableKeys", requestManager.getActiveRequestIds())
                .log("Checking requestManager for waiting channel");

        RequestContext ctx = requestManager.getRequest(requestId);
        Channel waitingChannel = ctx != null ? ctx.originChannel : null;

        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("waitingChannel", waitingChannel)
                .log("Found waiting channel");

        if (waitingChannel != null) {
            logger.atDebug().addKeyValue("requestId", requestId).log("Sending empty RequestFinishMessage to waiter");
            waitingChannel.writeAndFlush(new RequestFinishMessage(new HashMap<>()));
            requestManager.removeRequest(requestId);
            logger.atDebug().addKeyValue("requestId", requestId).log("Removed waiter");
        } else {
            logger.atWarn()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("availableKeys", requestManager.getActiveRequestIds())
                    .log("No waiting channel found");
        }
    }

    private void handleServiceNotFound(String serviceName, RequestId requestId) {
        logger.atError()
                .addKeyValue("requestId", requestId)
                .addKeyValue("serviceName", serviceName)
                .log("Service not found");

        if (requestManager.hasRequest(requestId)) {
            RequestContext ctx = requestManager.getRequest(requestId);
            if (ctx != null && ctx.originChannel != null) {
                Map<String, Object> failedResult = new HashMap<>();
                failedResult.put("error", "Service not found: " + serviceName);
                failedResult.put("success", false);
                ctx.originChannel.writeAndFlush(new RequestFinishMessage(failedResult));
                requestManager.removeRequest(requestId);
            }
        }
    }

    private void distributeAndExecute(final TaskRequest request, final HiBAService service) throws Exception {
        final String requestName = request.getRequestName();
        final String serviceName = request.getServiceName();
        final RequestId requestId = request.getRequestId();

        AtomicInteger requestDistributeTier = new AtomicInteger(0);

        if (request.hasBrokerRoute()) {
            requestDistributeTier.set(request.getTier() + 1);
        }
        logger.atInfo()
                .addKeyValue("requestId", requestId)
                .addKeyValue("tier", requestDistributeTier.get())
                .log("Get tier");
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .log("Initializing distributor");

        distributor = service.getDistributor();
        distributor.setBrokerTier(requestDistributeTier.get());
        distributor.setTaskRequest(request);
        distributor.setRequestID(requestId);
        distributor.initial();
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .log("Distributor initialized");

        do {
            logger.atInfo().addKeyValue("requestId", requestId).log("Start distribute");
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .addKeyValue("hasMoreRound", distributor.hasMoreRound())
                    .addKeyValue("reassignTask", reassignTask)
                    .log("Starting distribution loop");

            if (!failedTaskList.isEmpty()) {
                logger.atInfo()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("taskDetails", failedTaskList.getFirst())
                        .log("Reassigning failed task");
                logger.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("failedTask", failedTaskList.getFirst())
                        .addKeyValue("requestName", requestName)
                        .log("Reassigning failed task details");
                distributor.setWorkInformation(failedTaskList.getFirst());
                failedTaskList.removeFirst();
            }

            if (failedTaskList.isEmpty()) {
                reassignTask = false;
            }

            boolean distributeFinish = false;
            distributeNodes.set(0);
            distributor.setClientSize(network.getClients().size());
            Iterator<Channel> nodesNameIterator = network.getClients().keySet().iterator();
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("clientCount", network.getClients().size())
                    .addKeyValue("requestName", requestName)
                    .log("Iterating through client channels for distribution");

            while (nodesNameIterator.hasNext() && !distributeFinish) {
                Channel clientChannel = nodesNameIterator.next();
                try {
                    String nodeName = network.getClients().get(clientChannel).getKey();
                    Map<String, Object> nodeResource = resourceList.get(nodeName);
                    logger.atDebug()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("nodeName", nodeName)
                            .addKeyValue("requestName", requestName)
                            .log("Mapping task for node");

                    TaskAssignment nodeTask = distributor.mappingTask(
                            nodeName, nodeResource, clientChannel, resourceProvider.getAllResource());

                    if (nodeTask != null) {
                        nodeTask.setRequestName(requestName);
                        nodeTask.setServiceName(serviceName);
                        nodeTask.setTier(requestDistributeTier.get());

                        logger.atInfo()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("nodeName", nodeName)
                                .addKeyValue("nodeTask", nodeTask)
                                .log("Task distributed to node");
                        logger.atDebug()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("nodeName", nodeName)
                                .addKeyValue("task", nodeTask)
                                .addKeyValue("requestName", requestName)
                                .log("Task mapped for node");

                        if (nodeTask.isDistributeFinish()) {
                            distributeFinish = true;
                            logger.atDebug()
                                    .addKeyValue("requestId", requestId)
                                    .addKeyValue("requestName", requestName)
                                    .log("Distribution finished for this round");
                        }

                        Map<String, Object> taskMap = TaskDataAdapter.taskAssignmentToMap(nodeTask);
                        if (nodeTask.isDistributeFinish()) {
                            taskMap.remove("distributeFinish");
                        }

                        taskDistributeList.put(nodeName, taskMap);
                        distributeNodes.addAndGet(1);
                        logger.atDebug()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("nodeName", nodeName)
                                .addKeyValue("taskMap", taskMap)
                                .addKeyValue("requestName", requestName)
                                .log("Sending TaskStartMessage to node");
                        clientChannel.writeAndFlush(new TaskStartMessage(requestName, requestId, taskMap));
                    } else {
                        logger.atDebug()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("nodeName", nodeName)
                                .addKeyValue("requestName", requestName)
                                .log("No task mapped for node");
                    }
                } catch (Exception e) {
                    logger.atError()
                            .addKeyValue("requestId", requestId)
                            .addKeyValue("requestName", requestName)
                            .setCause(e)
                            .log("Exception during task distribution to node");
                }
            }

            if (!distributeFinish) {
                handleLocalTask(service, requestName, serviceName, requestDistributeTier.get());
            }

            if (taskDistributeList.isEmpty()) {
                logger.atWarn()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .log("Task distribution list is empty after mapping");
            } else {
                logger.atInfo()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("nodeCount", distributeNodes.get())
                        .log("Task distributed to nodes");
                logger.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("nodeCount", distributeNodes.get())
                        .addKeyValue("requestName", requestName)
                        .log("Task distributed to nodes details");
            }

            waitForTaskResults(requestId);

            logger.atInfo().addKeyValue("requestId", requestId).log("Merge node execution results");
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("All task results received. Merging");
            taskResultList.forEach((name, result) -> {
                logger.atInfo()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("nodeName", name)
                        .addKeyValue("result", result)
                        .log("Node result");
            });

            distributor.merge(taskResultList);
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("Results merged. Clearing task lists");
            taskDistributeList.clear();
            taskResultList.clear();

        } while (distributor.hasMoreRound() || reassignTask);

        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .log("Distribution loop finished");

        handleTaskForwardingAndResult(requestId, requestDistributeTier);
    }

    private void handleLocalTask(HiBAService service, String requestName, String serviceName, int tier)
            throws Exception {
        RequestId requestId = distributor.getTaskRequest().getRequestId();
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .log("Mapping local task");
        TaskAssignment localTask = distributor.mappingLocalTask(localResource, resourceProvider.getAllResource());
        logger.atInfo().addKeyValue("requestId", requestId).log("End distribute");

        if (localTask != null) {
            localTask.setRequestName(requestName);
            localTask.setServiceName(serviceName);
            localTask.setNodeName("localhost");
            localTask.setTier(tier);
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("localTask", localTask)
                    .addKeyValue("requestName", requestName)
                    .log("Local task mapped");

            Executor executor = service.getExecutor();
            Map<String, Object> localTaskCompatMap = TaskDataAdapter.taskAssignmentToMap(localTask);

            executor.setTier(tier);
            executor.setTaskInformation(localTaskCompatMap);

            logger.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("localTask", localTask)
                    .log("Local task distributed");
            taskDistributeList.put(HiBAInitial.nodeName, localTaskCompatMap);
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("Submitting local task for execution");

            // Capture requestId before submitting to taskExecutor
            RequestId requestIdForTask = localTask.getRequestId();

            ExecutorService currentExecutor = taskExecutorProvider.getTaskExecutor();
            if (currentExecutor.isShutdown() || currentExecutor.isTerminated()) {
                logger.atWarn()
                        .addKeyValue("requestId", requestIdForTask)
                        .log("TaskExecutor is shutdown, cannot submit local task");
                return;
            }
            currentExecutor.submit(() -> {
                logger.atDebug()
                        .addKeyValue("requestId", requestIdForTask)
                        .addKeyValue("requestName", requestName)
                        .log("Local task execution started");
                Map<String, Object> localResult = executor.brokerExecute(localTaskCompatMap);
                taskResultList.put(HiBAInitial.nodeName, localResult);
                logger.atInfo().addKeyValue("requestId", requestIdForTask).log("Local task execution ended");
                logger.atDebug()
                        .addKeyValue("requestId", requestIdForTask)
                        .addKeyValue("result", localResult)
                        .addKeyValue("requestName", requestName)
                        .log("Local task execution finished");
            });
        } else {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("No local task mapped");
        }
    }

    private void waitForTaskResults(RequestId requestId) throws InterruptedException {
        boolean printCtrl = false;
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("distributed", taskDistributeList.size())
                .addKeyValue("results", taskResultList.size())
                .log("Waiting for task results");

        while (taskDistributeList.size() > taskResultList.size()) {
            ArrayList<String> taskDistributeNodeNames = new ArrayList<>(taskDistributeList.keySet());
            ArrayList<String> nowAliveNodeNames = network.getClients().values().stream()
                    .map(Entry::getKey)
                    .collect(Collectors.toCollection(ArrayList::new));
            nowAliveNodeNames.add(HiBAInitial.nodeName);

            taskDistributeNodeNames.removeAll(nowAliveNodeNames);

            if (!taskDistributeNodeNames.isEmpty()) {
                logger.atWarn()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("disconnectedNodes", taskDistributeNodeNames)
                        .log("Nodes disconnected during computation");
                for (String name : taskDistributeNodeNames) {
                    nodeDisconnectHandler.handleNodeDisconnect(name);
                }
            }

            if (!printCtrl) {
                logger.atInfo().addKeyValue("requestId", requestId).log("Waiting for task results");
                printCtrl = true;
            }
            Thread.sleep(1000);
        }
    }

    private void handleTaskForwardingAndResult(RequestId requestId, AtomicInteger requestDistributeTier) {
        // requestId is guaranteed non-null from HTTP layer
        logger.atDebug().addKeyValue("requestId", requestId).log("Handling task forwarding");
        Map<String, Object> result = org.gslab.task.TaskForwardingHandler.handleTaskForwarding(
                distributor,
                requestDistributeTier,
                requestId,
                requestManager.getAllRequestWaiters(),
                requestManager.getAllUpperBrokerWaiters());

        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("result", result)
                .log("Task forwarding handled");
        logger.atDebug().addKeyValue("requestId", requestId).log("Handling task result");

        org.gslab.task.TaskResultHandler.TaskHandleResult handleResult =
                org.gslab.task.TaskResultHandler.handleTaskResult(
                        result,
                        distributor,
                        requestId,
                        requestDistributeTier.get(),
                        requestManager.getAllRequestWaiters(),
                        requestManager.getAllUpperBrokerWaiters(),
                        network);

        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("handleResult", handleResult)
                .log("Task result handled");

        if (handleResult == org.gslab.task.TaskResultHandler.TaskHandleResult.CONTINUE) {
            logger.atDebug().addKeyValue("requestId", requestId).log("Continuing to next request processing cycle");
            return;
        }

        String requestName = distributor.getTaskRequest().getRequestName();
        responseOutputter.outputResponse(requestName, requestId, result);

        logger.atInfo().addKeyValue("requestId", requestId).log("Local Request Finish");
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .log("Local request finished");
    }

    public Distributor getDistributor() {
        return distributor;
    }
}
