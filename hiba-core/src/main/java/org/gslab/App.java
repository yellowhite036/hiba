package org.gslab;

import io.netty.channel.Channel;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.gslab.SdnNetwork.Initializer;
import org.gslab.Tool.JsonFileTool;
import org.gslab.Tool.PathUtil;
import org.gslab.context.RequestManager;
import org.gslab.distribution.DistributionThreadExecutor;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.handlers.BrokerCompletionHandlers;
import org.gslab.handlers.BrokerRegistrationHandlers;
import org.gslab.handlers.ClientChannelHandlers;
import org.gslab.handlers.ServerChannelHandlers;
import org.gslab.message_hiba.RegisteMessage;
import org.gslab.message_hiba.RegisteResponse;
import org.gslab.message_hiba.ResourceUpdateMessage;
import org.gslab.message_hiba.WaitingRequestMessage;
import org.gslab.model.RequestId;
import org.gslab.model.TaskRequest;
import org.gslab.model.TaskResult;
import org.gslab.nettyhandlers_hiba.FileTransferHandler;
import org.gslab.sdns.FileTransferClient;
import org.gslab.sdns.FileUploadInfo;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;
import org.gslab.sdns.HttpRequestResultListener;
import org.gslab.service.mService.multicast.MulticastController;
import org.gslab.task.TaskDataAdapter;
import org.gslab.tpm.TPMService;
import org.gslab.tpm.exception.TPMConnectionException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    private final SdnNetwork network;
    private Map<String, Object> localResource = new HashMap<>();
    private final Map<String, HiBAService> serviceList = new HashMap<>(); // serviceName : HiBAservice
    private final BlockingQueue<Map<String, Object>> requestQueue = new LinkedBlockingQueue<>(10);
    private final Map<String, Map<String, Object>> resourceList = new ConcurrentHashMap<>(); // nodeName : resourceMap
    private final Map<String, Map<String, Object>> taskDistributeList = new ConcurrentHashMap<>(); // nodeName : taskMap
    private final Map<String, Map<String, Object>> taskResultList = new ConcurrentHashMap<>(); // nodeName : resultMap
    private final RequestManager requestManager = new RequestManager(); // Unified request context manager
    private final ArrayList<Map<String, Object>> failedTaskList = new ArrayList<>();
    private Boolean reassignTask = false;

    // HTTP請求結果監聽器
    private final Map<RequestId, HttpRequestResultListener> httpRequestListeners = new ConcurrentHashMap<>();

    private final FileTransferHandler fileTransferHandler = new FileTransferHandler();
    private final FileProtectionService fileProtectionService;
    private final TPMService tpmService;

    private ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private Distributor distributor = null;
    private Thread distributeThread = null;
    private DistributionThreadExecutor distributionExecutor = null;
    public static Map<String, Object> method = new HashMap<>();
    public static Map<String, Object> nodeResource = new HashMap<>(); // 暫時用, 給HiBANetwork 更新CPU使用率用

    @SuppressWarnings("unchecked")
    public App(SdnNetwork network) throws Exception {

        // Initialize network first before any other components access it
        this.network = network;
        this.fileProtectionService = HiBAInitial.fileProtectionService;
        this.tpmService = HiBAInitial.tpmService;
        // Setup handlers before starting threads
        this.network.clientInitialHandler(initializeClientHandler());
        this.network.serverInitialHandler(initializeServerHandler());

        // Initialize distribute thread (but don't start yet)
        AtomicInteger distributeNodes = new AtomicInteger(0);
        distributeThread = initializeDistributeThread(distributeNodes);

        // Start network and thread last to avoid race conditions
        network.start();
        distributeThread.start();
    }

    private Thread initializeDistributeThread(AtomicInteger distributeNodes) {
        logger.atDebug().addKeyValue("distributeNodes", distributeNodes.get()).log("initializeDistributeThread: Entry");

        distributionExecutor = new DistributionThreadExecutor(
                (HiBANetwork) network,
                requestQueue,
                resourceList,
                taskDistributeList,
                taskResultList,
                requestManager,
                failedTaskList,
                this::getTaskExecutor,
                serviceList,
                localResource,
                distributeNodes,
                this::getService,
                this::getAllResource,
                this::outputResponse,
                this::nodeDisconnectWhenCompute);

        return new Thread(distributionExecutor);
    }

    private Initializer initializeServerHandler() {
        logger.atDebug().log("initializeServerHandler: Entry");
        return new SdnNetwork.Initializer() {
            @Override
            public void initial(Channel channel) {
                logger.atDebug()
                        .addKeyValue("channelId", channel.id())
                        .addKeyValue("localAddress", channel.localAddress())
                        .addKeyValue("remoteAddress", channel.remoteAddress())
                        .log("initializeServerHandler.initial: Initializing server channel pipeline");

                channel.pipeline()
                        .addLast(
                                "ServerLifecycleHandler",
                                new ServerChannelHandlers.ServerLifecycleHandler(
                                        network,
                                        resourceList,
                                        App.this::updateResource,
                                        App.this::nodeDisconnectWhenCompute));

                channel.pipeline()
                        .addLast(
                                "ResourceUpdateMessageHandler",
                                new ServerChannelHandlers.ResourceUpdateHandler(
                                        network, resourceList, App.this::getAllResource));

                channel.pipeline().addLast(fileTransferHandler); // Assuming fileTransferHandler has its own logging or
                // is tested separately

                channel.pipeline()
                        .addLast(
                                "TaskFinishMessageHandler",
                                new ServerChannelHandlers.TaskFinishHandler(network, taskResultList));
                channel.pipeline()
                        .addLast(
                                "WaitingRequestMessageHandler",
                                new ServerChannelHandlers.WaitingRequestHandler(requestManager));

                channel.pipeline()
                        .addLast(
                                "RegisteMessageHandler",
                                new ServerChannelHandlers.RegistrationHandler(
                                        network,
                                        requestQueue,
                                        requestManager,
                                        App.this::getService,
                                        App.this::getAllResource,
                                        App::traverseDirectory,
                                        App.this::getLocalTime));

                logger.atDebug()
                        .addKeyValue("channelId", channel.id())
                        .log("initializeServerHandler.initial: Server channel pipeline initialized");
            }
        };
    }

    private Initializer initializeClientHandler() {
        logger.atDebug().log("initializeClientHandler: Entry");
        return new SdnNetwork.Initializer() {
            @Override
            public void initial(Channel channel) {
                logger.atDebug()
                        .addKeyValue("channelId", channel.id())
                        .addKeyValue("localAddress", channel.localAddress())
                        .addKeyValue("remoteAddress", channel.remoteAddress())
                        .log("initializeClientHandler.initial: Initializing client channel pipeline");

                channel.pipeline()
                        .addLast(
                                "ClientLifecycleHandler",
                                new ClientChannelHandlers.ClientLifecycleHandler(
                                        network,
                                        App.this::getAllResource,
                                        App.this::getDistributor,
                                        App.this::getDistributeThread,
                                        App.this::getTaskExecutor,
                                        App.this::setTaskExecutor));

                channel.pipeline()
                        .addLast(
                                "TaskStartMessageHandler",
                                new ClientChannelHandlers.TaskStartHandler(
                                        network, requestQueue, requestManager, taskExecutor, App.this::getService));
                logger.atDebug()
                        .addKeyValue("channelId", channel.id())
                        .log("initializeClientHandler.initial: Client channel pipeline initialized");
            }
        };
    }

    public App addService(HiBAService service) {
        serviceList.put(service.getName(), service);
        return this;
    }

    public Distributor getDistributor() {
        return distributionExecutor != null ? distributionExecutor.getDistributor() : distributor;
    }

    private void attachPreparedMetadataIfNeeded(TaskRequest taskRequest) {
        if (taskRequest == null || HiBAInitial.blockchainFileProtect) {
            return;
        }

        List<Object> uploadedFiles = taskRequest.getUploadedFiles();
        if (uploadedFiles == null || uploadedFiles.isEmpty()) {
            return;
        }

        String description = null;
        if (taskRequest.getTaskSpecificData() != null) {
            Object descriptionValue = taskRequest.getTaskSpecificData().get("description");
            if (descriptionValue != null) {
                description = descriptionValue.toString();
            }
        }

        try {
            String uploader = tpmService.getEKFingerprint();
            List<FileMetadata> preparedMetadata = new ArrayList<>();

            for (Object item : uploadedFiles) {
                if (item instanceof FileUploadInfo fileInfo) {
                    File file = new File(fileInfo.getStoredPath());
                    FileMetadata metadata = fileProtectionService.createFileMetadata(file, uploader, description);
                    preparedMetadata.add(metadata);
                }
            }

            if (!preparedMetadata.isEmpty()) {
                Map<String, Object> taskData = taskRequest.getTaskSpecificData();
                if (taskData == null) {
                    taskData = new HashMap<>();
                }
                taskData.put("preparedMetadata", preparedMetadata);
                taskRequest.setTaskSpecificData(taskData);
                logger.atInfo()
                        .addKeyValue("preparedMetadataCount", preparedMetadata.size())
                        .log("Prepared metadata for broker forwarding");
            }
        } catch (TPMConnectionException e) {
            logger.atError().setCause(e).log("Failed to get TPM EK fingerprint for metadata preparation");
        } catch (Exception e) {
            logger.atError().setCause(e).log("Failed to prepare FileMetadata for broker forwarding");
        }
    }

    public Thread getDistributeThread() {
        return distributeThread;
    }

    public ExecutorService getTaskExecutor() {
        return taskExecutor;
    }

    public void setTaskExecutor(ExecutorService executor) {
        this.taskExecutor = executor;
    }

    public RequestManager getRequestManager() {
        return requestManager;
    }

    public void updateResource() {
        if (network.getBrokerChannel() != null) {
            network.getBrokerChannel().writeAndFlush(new ResourceUpdateMessage(getAllResource()));
        }
    }

    public App setResource(Map<String, Object> resource) {
        this.localResource = resource;
        return this;
    }

    /**
     * Merge new resource data into localResource and notify broker.
     */
    @SuppressWarnings("unchecked")
    public void mergeAndUpdateResource(Map<String, Object> newResource) {
        if (newResource == null || newResource.isEmpty()) {
            return;
        }

        // Merge into localResource
        for (Map.Entry<String, Object> entry : newResource.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof List) {
                List<String> existingList = (List<String>) localResource.getOrDefault(key, new ArrayList<>());
                List<String> newList = new ArrayList<>((List<String>) value);
                existingList.addAll(newList);
                localResource.put(key, existingList);
            } else {
                localResource.put(key, value);
            }
        }

        // Send full resource to broker
        updateResource();
        logger.atDebug().log("Merged and updated resource: {}", newResource.keySet());
    }

    public void sendRequest(Map<String, Object> request) throws Exception {
        // 轉換為 TaskRequest 以獲得類型安全
        TaskRequest taskRequest = TaskDataAdapter.mapToTaskRequest(request);

        RequestId requestId = taskRequest.getRequestId();
        String requestName = taskRequest.getRequestName();
        String serviceName = taskRequest.getServiceName();

        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .addKeyValue("serviceName", serviceName)
                .log("sendRequest: Entry");
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("request", request)
                .log("sendRequest: Full request map");

        HiBAService service = getService(serviceName);
        if (service == null) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("serviceName", serviceName)
                    .addKeyValue("requestName", requestName)
                    .log("sendRequest: Service not found");
            TaskResult failureResult = new TaskResult(requestName, requestId, serviceName, "unknown");
            failureResult.setState(false);
            failureResult.setSuccess(false);
            failureResult.setErrorMessage("Service not found: " + serviceName);
            outputResponse(requestName, requestId, TaskDataAdapter.taskResultToMap(failureResult));
            return;
        }

        boolean hasBroker = !(network.getBrokerChannel() == null);
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("hasBroker", hasBroker)
                .addKeyValue("requestName", requestName)
                .log("sendRequest: Has upstream broker check");

        Taker taker = service.getTaker();
        taker.setRequestInformation(TaskDataAdapter.taskRequestToMap(taskRequest));

        boolean canHandleLocally = taker.checkCanHandleRequest(hasBroker, getAllResource());
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("canHandleLocally", canHandleLocally)
                .addKeyValue("requestName", requestName)
                .log("sendRequest: Taker canHandleRequest check");

        if (canHandleLocally) {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("sendRequest: Handling request locally");

            if (taskRequest.hasTransferFile()) {
                String receiveTransferFile = taskRequest.getReceiveTargetPath();
                List<String> transferFile = taskRequest.getTransferFileList();
                logger.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("transferFile", transferFile)
                        .addKeyValue("receiveTargetPath", receiveTransferFile)
                        .addKeyValue("requestName", requestName)
                        .log("sendRequest: Local handling - Transferring files");
                transferFile(new ArrayList<>(transferFile), receiveTransferFile, null, requestId);
            }

            logger.atInfo().addKeyValue("requestId", requestId).log("Local can handle - executing directly");

            // Execute locally using Executor instead of queuing for distribution
            try {
                Executor executor = service.getExecutor();
                executor.setTaskInformation(TaskDataAdapter.taskRequestToMap(taskRequest));

                logger.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .log("sendRequest: Calling executor.execute() for local processing");
                Map<String, Object> result = executor.execute(
                        TaskDataAdapter.taskRequestToMap(taskRequest),
                        null // No broker channel needed for local execution
                        );

                // Check if executor requests resource update
                if (result.containsKey("updateResource")) {
                    Map<String, Object> partialUpdate = new HashMap<>();
                    partialUpdate.put("updateResource", result.get("updateResource"));
                    mergeAndUpdateResource(partialUpdate);
                }

                logger.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("result", result)
                        .log("sendRequest: Local execution completed");
                outputResponse(requestName, requestId, result);
            } catch (Exception e) {
                logger.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .setCause(e)
                        .log("sendRequest: Local execution failed");
                TaskResult failureResult = new TaskResult(requestName, serviceName, "unknown");
                failureResult.setState(false);
                failureResult.setSuccess(false);
                failureResult.setErrorMessage("Local execution failed: " + e.getMessage());
                outputResponse(requestName, requestId, TaskDataAdapter.taskResultToMap(failureResult));
            }
            return;
        }

        if (!hasBroker) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("sendRequest: Cannot handle locally and no upstream broker");
            TaskResult failureResult = new TaskResult(requestName, serviceName, "unknown");
            failureResult.setState(false);
            failureResult.setSuccess(false);
            failureResult.setErrorMessage("Cannot handle locally and no upstream broker.");
            outputResponse(requestName, requestId, TaskDataAdapter.taskResultToMap(failureResult));
            return;
        }

        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .log("sendRequest: Handling request via broker");

        if ("BlockchainFileProtect".equals(serviceName)) {
            attachPreparedMetadataIfNeeded(taskRequest);
        }

        // Use CompletableFuture instead of AtomicReference for async handling
        CompletableFuture<RegisteResponse> registeResponseFuture = new CompletableFuture<>();

        String brokerHost = ((InetSocketAddress) network.getBrokerChannel().remoteAddress()).getHostName();
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("brokerHost", brokerHost)
                .addKeyValue("requestName", requestName)
                .log("sendRequest: Upstream brokerHost");
        logger.atInfo()
                .addKeyValue("requestId", requestId)
                .addKeyValue("brokerHost", brokerHost)
                .log("brokerHost determined");

        if (MulticastController.getMode().equals("isBroker")) {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("sendRequest: Current node isBroker, adding self to brokerRoute");
            taskRequest.addBrokerToRoute(HiBAInitial.nodeName);
        }

        if (taskRequest.hasTransferFile()) {
            String receiveTransferFile = taskRequest.getReceiveTargetPath();
            java.util.List<String> transferFile = taskRequest.getTransferFileList();
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("transferFile", transferFile)
                    .addKeyValue("receiveTargetPath", receiveTransferFile)
                    .addKeyValue("brokerHost", brokerHost)
                    .addKeyValue("requestName", requestName)
                    .log("sendRequest: Remote handling - Transferring files");
            transferFile(new ArrayList<>(transferFile), receiveTransferFile, brokerHost, requestId);
        }

        Map<String, Object> requestInformation = TaskDataAdapter.taskRequestToMap(taskRequest);
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .log("sendRequest: Sending RegisteMessage to broker");

        network.sendShortConnectMessage(
                brokerHost,
                new RegisteMessage(requestName, requestId, 86400, System.currentTimeMillis(), requestInformation),
                new Initializer() {
                    @Override
                    public void initial(Channel channel) {
                        logger.atDebug()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("localAddress", channel.localAddress())
                                .addKeyValue("remoteAddress", channel.remoteAddress())
                                .addKeyValue("requestName", requestName)
                                .log("sendRequest: RegisteMessage channel initialized to broker");

                        // Use extracted handlers - no MDC, explicit requestId passing
                        channel.pipeline()
                                .addLast(new BrokerRegistrationHandlers.RegistrationLifecycleHandler(
                                        requestId, requestName));
                        channel.pipeline()
                                .addLast(new BrokerRegistrationHandlers.RegistrationResponseHandler(
                                        requestId, requestName, registeResponseFuture));
                    }
                });

        // Wait for RegisteResponse with timeout (10 seconds)
        RegisteResponse response;
        try {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("sendRequest: Waiting for RegisteResponse (timeout: 10s)");
            response = registeResponseFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
            logger.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("response", response)
                    .addKeyValue("handleBrokerHost", response.getHandleBrokerHost())
                    .log("RegisteResponse received");
        } catch (java.util.concurrent.TimeoutException e) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .setCause(e)
                    .log("sendRequest: Timeout waiting for RegisteResponse");
            TaskResult failureResult = new TaskResult(requestName, requestId, serviceName, "unknown");
            failureResult.setState(false);
            failureResult.setSuccess(false);
            failureResult.setErrorMessage("Timeout waiting for RegisteResponse from broker (10 seconds).");
            outputResponse(requestName, requestId, TaskDataAdapter.taskResultToMap(failureResult));
            return;
        } catch (Exception e) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .setCause(e)
                    .log("sendRequest: Error receiving RegisteResponse");
            TaskResult failureResult = new TaskResult(requestName, requestId, serviceName, "unknown");
            failureResult.setState(false);
            failureResult.setSuccess(false);
            failureResult.setErrorMessage("Error receiving RegisteResponse: " + e.getMessage());
            outputResponse(requestName, requestId, TaskDataAdapter.taskResultToMap(failureResult));
            return;
        }
        if (response.isSuccess()) {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("handleBrokerHost", response.getHandleBrokerHost())
                    .addKeyValue("directBrokerHost", brokerHost)
                    .addKeyValue("requestName", requestName)
                    .log(
                            "sendRequest: RegisteResponse successful. Sending WaitingRequestMessage to direct broker via long connection");

            // Use CompletableFuture for async result handling
            CompletableFuture<Map<String, Object>> requestFinishFuture = new CompletableFuture<>();
            AtomicReference<String> errorMessage = new AtomicReference<>(null);

            network.sendConnectMessage(
                    brokerHost,
                    new WaitingRequestMessage(requestName, requestId),
                    channel -> {
                        logger.atDebug()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("localAddress", channel.localAddress())
                                .addKeyValue("remoteAddress", channel.remoteAddress())
                                .addKeyValue("requestName", requestName)
                                .log(
                                        "sendRequest: WaitingRequestMessage channel (for RequestFinishMessage) PIPELINE SETUP START");

                        // Use extracted handlers - no MDC, explicit requestId passing
                        channel.pipeline()
                                .addLast(
                                        "GenericDebugHandler_For_RequestFinishMessage_Channel",
                                        new BrokerCompletionHandlers.CompletionChannelDebugHandler(
                                                requestId, errorMessage));

                        channel.pipeline().addLast(fileTransferHandler); // Still commented out

                        channel.pipeline()
                                .addLast(
                                        "RequestFinishMessageHandler",
                                        new BrokerCompletionHandlers.RequestFinishMessageHandler(
                                                requestId, requestName, requestFinishFuture, errorMessage));

                        logger.atDebug()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("requestName", requestName)
                                .log(
                                        "sendRequest: WaitingRequestMessage channel (for RequestFinishMessage) PIPELINE SETUP END");
                    },
                    false); // 使用長連線模式，不關閉連線 // 等待 RequestFinishMessage 的回應

            // Wait for RequestFinishMessage with CompletableFuture (timeout: 30 seconds)
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("sendRequest: Waiting for RequestFinishMessage response (timeout: 30s)");
            try {
                Map<String, Object> result = requestFinishFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
                logger.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .log("sendRequest: RequestFinishMessage received successfully");
                outputResponse(requestName, requestId, result);
            } catch (java.util.concurrent.TimeoutException e) {
                logger.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .setCause(e)
                        .log("sendRequest: Timeout waiting for RequestFinishMessage (30 seconds)");
                TaskResult failureResult = new TaskResult(requestName, requestId, serviceName, "unknown");
                failureResult.setState(false);
                failureResult.setSuccess(false);
                failureResult.setErrorMessage("Timeout waiting for RequestFinishMessage after 30 seconds");
                outputResponse(requestName, requestId, TaskDataAdapter.taskResultToMap(failureResult));
            } catch (Exception e) {
                String error = errorMessage.get() != null ? errorMessage.get() : e.getMessage();
                logger.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("error", error)
                        .setCause(e)
                        .log("sendRequest: Error occurred while processing RequestFinishMessage");
                TaskResult failureResult = new TaskResult(requestName, requestId, serviceName, "unknown");
                failureResult.setState(false);
                failureResult.setSuccess(false);
                failureResult.setErrorMessage(error);
                outputResponse(requestName, requestId, TaskDataAdapter.taskResultToMap(failureResult));
            }

        } else {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .addKeyValue("brokerInfo", response.getHandleBrokerHost())
                    .log("sendRequest: RegisteResponse was not successful");
            TaskResult failureResult = new TaskResult(requestName, requestId, serviceName, "unknown");
            failureResult.setState(false);
            failureResult.setSuccess(false);
            failureResult.setErrorMessage(
                    "Registration with broker failed. Broker info: " + response.getHandleBrokerHost());
            outputResponse(requestName, requestId, TaskDataAdapter.taskResultToMap(failureResult));
        }

        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .log("sendRequest: Exit");
    }

    public void outputResponse(String requestName, RequestId requestId, Map<String, Object> responseMsg) {
        try {
            Map<String, Object> upperBrokerResponse = new HashMap<>(responseMsg);

            // 檢查 requestTimeStamp 是否存在，如果不存在則使用當前時間
            Object requestTimeStampObj = upperBrokerResponse.get("requestTimeStamp");
            long startTime;
            if (requestTimeStampObj instanceof Long) {
                startTime = (Long) requestTimeStampObj;
            } else {
                // 如果沒有 requestTimeStamp（例如超時情況），使用當前時間減去一個合理的估計時間
                Map<String, Object> localTime = getLocalTime();
                startTime = (long) localTime.get("timeStamp") - 30000; // 假設請求耗時 30 秒
                logger.atWarn()
                        .addKeyValue("requestId", requestId)
                        .log("outputResponse: requestTimeStamp not found, using estimated time");
            }

            Map<String, Object> localTime = getLocalTime();
            long endTime = (long) localTime.get("timeStamp");
            double executionTime = (endTime - startTime) / 1000.000;

            upperBrokerResponse.put("taskCompletionTime", endTime);
            upperBrokerResponse.put("taskCompletionTimeStamp", localTime.get("timeStamp"));
            upperBrokerResponse.put("taskExecutionTime", executionTime);

            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("response", upperBrokerResponse.toString())
                    .log("Full upper broker response");

            // TODO: 這裡是getFile微服務處理完成的邏輯，需要將TPM簽章和FileProtection驗證的內容儲存一份實體檔案，後續要做攻擊演示
            if ("getFile".equals(upperBrokerResponse.get("serviceName"))) {
                boolean verificationResult = verifyGetFileResponse(upperBrokerResponse);
                logger.atInfo()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("verificationResult", verificationResult ? "PASSED" : "FAILED")
                        .log("Overall getFile verification result");
            }

            Path responsePath = Path.of("./response", requestName + "_" + localTime.get("date") + ".json");

            logger.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .addKeyValue("executionTime", executionTime)
                    .addKeyValue("responseFile", responsePath.toString())
                    .addKeyValue("result", upperBrokerResponse)
                    .log("Request completed - END");

            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("responsePath", responsePath.toString())
                    .log("Writing response to file");

            JsonFileTool.WriteFile(new JSONObject(upperBrokerResponse), new File(responsePath.toString()));

            // 通知HTTP請求監聽器
            notifyHttpRequestListeners(requestId, upperBrokerResponse);

        } catch (IOException e) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .setCause(e)
                    .log("An error occurred parsing the response");
            e.printStackTrace();
        }
    }

    public static void traverseDirectory(File directory, ArrayList<String> list) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            for (File file : files) {
                if (file.isFile()) {
                    list.add(file.getPath());
                } else {
                    traverseDirectory(file, list);
                }
            }
        }
    }

    /**
     * Verify getFile response and all transferred files
     *
     * @param upperBrokerResponse The complete response from broker
     * @return true if all files pass verification, false otherwise
     */
    private boolean verifyGetFileResponse(Map<String, Object> upperBrokerResponse) {
        try {
            // 使用upperBrokerResponse中的transfers資料進行檔案驗證
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) upperBrokerResponse.get("transfers");

            if (transfers == null || transfers.isEmpty()) {
                logger.atWarn().log("No transfers found in response for service 'getFile'");
                return false;
            }

            boolean allVerificationsPassed = true;

            for (Map<String, Object> transfer : transfers) {
                String fileName = (String) transfer.get("fileName");
                String targetPath = (String) transfer.get("targetPath");

                if (targetPath != null && fileName != null) {
                    Path fullFilePath = PathUtil.buildPath(targetPath, fileName);
                    File receivedFile = fullFilePath.toFile();

                    logger.atInfo()
                            .addKeyValue("fileName", fileName)
                            .addKeyValue("filePath", fullFilePath.toString())
                            .log("Verifying received file");

                    // 執行單個檔案驗證
                    boolean singleFileResult = performSingleFileVerification(transfer, receivedFile);
                    if (!singleFileResult) {
                        allVerificationsPassed = false;
                    }
                } else {
                    logger.atError()
                            .addKeyValue("transfer", transfer)
                            .log("Missing fileName or targetPath in transfer data");
                    allVerificationsPassed = false;
                }
            }

            return allVerificationsPassed;

        } catch (Exception e) {
            logger.atError().setCause(e).log("Error during getFile verification");
            return false;
        }
    }

    /**
     * Perform file verification for a single transferred file
     *
     * @param transfer     Transfer information from upperBrokerResponse
     * @param receivedFile The received file
     * @return true if verification passes, false otherwise
     */
    private boolean performSingleFileVerification(Map<String, Object> transfer, File receivedFile) {
        try {
            String fileName = (String) transfer.get("fileName");
            String expectedFileHash = (String) transfer.get("fileHash");
            String fileId = (String) transfer.get("fileId");

            // ========================================
            // [VERIFICATION] Stage 1: Local File Analysis
            // ========================================
            logger.atInfo().log("========================================");
            logger.atInfo().log("[VERIFICATION] Stage 1: Local File Analysis");
            logger.atInfo().log("========================================");

            if (!receivedFile.exists()) {
                logger.atError().log("Stage 1 FAILED: File does not exist");
                logger.atError().log("  → fileName: {}", fileName);
                logger.atError().log("  → filePath: {}", receivedFile.getAbsolutePath());
                return false;
            }

            // 計算接收檔案的實際hash
            FileMetadata localMetadata =
                    fileProtectionService.createFileMetadata(receivedFile, tpmService.getEKFingerprint());
            String actualFileHash = localMetadata.getFileHash();

            logger.atInfo().log("Local file metadata computed:");
            logger.atInfo().log("  → fileName:   {}", localMetadata.getFileName());
            logger.atInfo().log("  → filePath:   {}", receivedFile.getAbsolutePath());
            logger.atInfo().log("  → fileSize:   {} bytes", localMetadata.getFileSize());
            logger.atInfo().log("  → fileHash:   {}", actualFileHash);
            logger.atInfo().log("  → uploader:   {}", localMetadata.getUploader());
            logger.atInfo().log("  → uploadTime: {}", localMetadata.getUploadTime());

            logger.atInfo().log("Transfer record from sender:");
            logger.atInfo().log("  → fileId:       {}", fileId);
            logger.atInfo().log("  → expectedHash: {}", expectedFileHash);
            logger.atInfo().log("  → actualHash:   {}", actualFileHash);

            // ========================================
            // [VERIFICATION] Stage 2: Hash Comparison
            // ========================================
            logger.atInfo().log("========================================");
            logger.atInfo().log("[VERIFICATION] Stage 2: Hash Comparison");
            logger.atInfo().log("========================================");

            boolean hashMatch = expectedFileHash.equals(actualFileHash);
            logger.atInfo().log("Hash comparison:");
            logger.atInfo().log("  → expectedHash: {}", expectedFileHash);
            logger.atInfo().log("  → actualHash:   {}", actualFileHash);
            logger.atInfo().log("  → match: {}", hashMatch ? "✓ PASSED" : "✗ FAILED");

            if (!hashMatch) {
                logger.atError().log("Stage 2 FAILED: File hash mismatch - file may be corrupted or tampered");
                logger.atError().log("  → fileName:     {}", fileName);
                logger.atError().log("  → expectedHash: {}", expectedFileHash);
                logger.atError().log("  → actualHash:   {}", actualFileHash);
                return false;
            }

            // ========================================
            // [VERIFICATION] Stage 3: Blockchain Query
            // ========================================
            logger.atInfo().log("========================================");
            logger.atInfo().log("[VERIFICATION] Stage 3: Blockchain Query");
            logger.atInfo().log("========================================");

            logger.atInfo().log("Querying blockchain for file metadata...");
            logger.atInfo().log("  → queryHash: {}", actualFileHash);

            Optional<FileMetadata> bcMetadataOpt =
                    fileProtectionService.findFileMetadataByFileHashFromBlockchain(actualFileHash);

            boolean blockchainRecordFound = bcMetadataOpt.isPresent();
            logger.atInfo().log("Blockchain query result:");
            logger.atInfo().log("  → found: {}", blockchainRecordFound ? "✓ YES" : "✗ NO");

            if (blockchainRecordFound) {
                FileMetadata bcMetadata = bcMetadataOpt.get();
                logger.atInfo().log("Blockchain metadata retrieved:");
                logger.atInfo().log("  → fileName:   {}", bcMetadata.getFileName());
                logger.atInfo().log("  → fileHash:   {}", bcMetadata.getFileHash());
                logger.atInfo().log("  → fileSize:   {} bytes", bcMetadata.getFileSize());
                logger.atInfo().log("  → uploader:   {}", bcMetadata.getUploader());
                logger.atInfo().log("  → uploadTime: {}", bcMetadata.getUploadTime());
            } else {
                logger.atWarn().log("No blockchain record found - file may not be registered on blockchain");
                logger.atWarn().log("  → fileHash: {}", actualFileHash);
            }

            // ========================================
            // [VERIFICATION] Stage 4: Field-by-Field Comparison
            // ========================================
            logger.atInfo().log("========================================");
            logger.atInfo().log("[VERIFICATION] Stage 4: Field-by-Field Comparison");
            logger.atInfo().log("========================================");

            boolean metadataMatch = false;
            if (blockchainRecordFound) {
                FileMetadata bcMetadata = bcMetadataOpt.get();

                // Field-by-field comparison with detailed logging
                boolean fileNameMatch = java.util.Objects.equals(localMetadata.getFileName(), bcMetadata.getFileName());
                boolean fileHashMatch = java.util.Objects.equals(localMetadata.getFileHash(), bcMetadata.getFileHash());
                boolean fileSizeMatch = localMetadata.getFileSize() == bcMetadata.getFileSize();
                boolean uploaderMatch = java.util.Objects.equals(localMetadata.getUploader(), bcMetadata.getUploader());

                logger.atInfo().log("Field comparison results:");
                logger.atInfo().log(
                        "  • fileName:  LOCAL={} | BLOCKCHAIN={} | {}",
                        localMetadata.getFileName(),
                        bcMetadata.getFileName(),
                        fileNameMatch ? "✓ MATCH" : "✗ MISMATCH");
                logger.atInfo().log(
                        "  • fileHash:  LOCAL={} | BLOCKCHAIN={} | {}",
                        localMetadata.getFileHash(),
                        bcMetadata.getFileHash(),
                        fileHashMatch ? "✓ MATCH" : "✗ MISMATCH");
                logger.atInfo().log(
                        "  • fileSize:  LOCAL={} | BLOCKCHAIN={} | {}",
                        localMetadata.getFileSize(),
                        bcMetadata.getFileSize(),
                        fileSizeMatch ? "✓ MATCH" : "✗ MISMATCH");
                logger.atInfo().log(
                        "  • uploader:  LOCAL={} | BLOCKCHAIN={} | {}",
                        localMetadata.getUploader(),
                        bcMetadata.getUploader(),
                        uploaderMatch ? "✓ MATCH" : "✗ MISMATCH");

                metadataMatch = fileProtectionService.compareFileMetadata(bcMetadata, localMetadata);
                logger.atInfo().log("Overall metadata match: {}", metadataMatch ? "✓ PASSED" : "✗ FAILED");
            } else {
                logger.atWarn().log("Skipping field comparison - no blockchain record available");
            }

            // ========================================
            // [VERIFICATION] Final Verdict
            // ========================================
            logger.atInfo().log("========================================");
            logger.atInfo().log("[VERIFICATION] Final Verdict");
            logger.atInfo().log("========================================");

            logger.atInfo().log("Verification summary:");
            logger.atInfo().log("  → Hash Match:             {}", hashMatch ? "✓ PASSED" : "✗ FAILED");
            logger.atInfo().log("  → Blockchain Record Found: {}", blockchainRecordFound ? "✓ YES" : "✗ NO");
            logger.atInfo().log("  → Metadata Match:         {}", metadataMatch ? "✓ PASSED" : "✗ FAILED");

            if (hashMatch && blockchainRecordFound && metadataMatch) {
                logger.atInfo().log("----------------------------------------");
                logger.atInfo().log("File: {}", fileName);
                logger.atInfo().log("Result: ✅ VERIFICATION SUCCESSFUL");
                logger.atInfo().log("All verification stages passed");
                logger.atInfo().log("----------------------------------------");
            } else if (hashMatch && !blockchainRecordFound) {
                logger.atWarn().log("----------------------------------------");
                logger.atWarn().log("File: {}", fileName);
                logger.atWarn().log("Result: ⚠️ PARTIAL SUCCESS - No blockchain record");
                logger.atWarn().log("Hash verified but blockchain record missing");
                logger.atWarn().log("----------------------------------------");
            } else if (hashMatch && blockchainRecordFound && !metadataMatch) {
                logger.atWarn().log("----------------------------------------");
                logger.atWarn().log("File: {}", fileName);
                logger.atWarn().log("Result: ⚠️ PARTIAL SUCCESS - Metadata mismatch");
                logger.atWarn().log("Hash verified but metadata differs from blockchain");
                logger.atWarn().log("----------------------------------------");
            }

            logger.atInfo().log("========================================");

            return true;

        } catch (Exception e) {
            logger.atError().setCause(e).log("File verification error");
            return false;
        }
    }

    public void transferFile(
            ArrayList<String> transferFile, String receiveTransferFile, String brokerHost, RequestId requestId) {
        for (String path : transferFile) {
            ArrayList<String> transferFileList = new ArrayList<>();
            traverseDirectory(new File(path), transferFileList);
            for (String file : transferFileList) {
                logger.atInfo()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("file", file)
                        .log("Transferring file");
                File file1 = new File(file);
                String fileRelativePath = file1.getParent().replaceAll("\\.", "");
                if (brokerHost != null) {
                    FileTransferClient.doTransfer(
                            file,
                            receiveTransferFile + fileRelativePath,
                            brokerHost,
                            null,
                            HiBAInitial.DATA_PORT,
                            requestId);
                } else {
                    try {
                        Files.copy(
                                file1.toPath(),
                                new File(receiveTransferFile + fileRelativePath + File.separator + file1.getName())
                                        .toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        e.printStackTrace();
                        logger.atError()
                                .addKeyValue("requestId", requestId)
                                .addKeyValue("file", file)
                                .setCause(e)
                                .log("An error occurred while copying file");
                    }
                }
            }
        }
    }

    private synchronized void nodeDisconnectWhenCompute(String nodeName) {
        if (taskDistributeList.containsKey(nodeName)) {
            Map<String, Object> task = taskDistributeList.get(nodeName);
            distributor.handleTaskFail(nodeName, resourceList.get(nodeName), task);
            logger.atInfo()
                    .addKeyValue("nodeName", nodeName)
                    .addKeyValue("task", task)
                    .log("Adding task to failed task list for reassignment");
            failedTaskList.add(task); // 測試用, 重新分派離線節點的任務
            reassignTask = true;
            taskDistributeList.remove(nodeName);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAllResource() {

        Map<String, Object> local = new HashMap<>(localResource);
        Map<String, Object> allResource = new HashMap<>(local);

        Map<String, Map<String, Object>> resourceListClone = new HashMap<>(resourceList);

        for (String name : resourceListClone.keySet()) {
            Map<String, Object> resource = new HashMap<>(resourceListClone.get(name));
            for (String resourceKey : resource.keySet()) {
                Object value = resource.get(resourceKey);
                if (value instanceof ArrayList<?>) {
                    ArrayList<String> temp = new ArrayList<>();
                    ArrayList<String> fileNameList = (ArrayList<String>) value;
                    if (allResource.containsKey(resourceKey)) {
                        ArrayList<String> sumList = (ArrayList<String>) allResource.get(resourceKey);
                        temp.addAll(fileNameList);
                        temp.addAll(sumList);
                        allResource.replace(resourceKey, temp);
                    } else {
                        allResource.put(resourceKey, fileNameList);
                    }
                } else if (value instanceof Integer) {
                    int resourceValue = (int) resource.get(resourceKey);
                    if (allResource.containsKey(resourceKey)) {
                        int allResourceValue = (int) allResource.get(resourceKey);
                        allResource.put(resourceKey, allResourceValue + resourceValue);
                    } else {
                        allResource.put(resourceKey, resourceValue);
                    }
                }
            }
        }

        // // HiBA v3實驗用, 向Broker傳遞當前設備硬體資訊
        // if (HiBAInitial.updateDeviceInfo &
        // !MulticastController.getMode().equals("isBroker")) {
        // // ArrayList<ArrayList<Object>> deviceInfo = new ArrayList<>();
        // // ArrayList<Object> getRateInfo = new ArrayList<>();
        // // Map<String, Object> getRate = CPUUsageRate.getCPURate();
        // // getRate.put("nodeName", HiBAInitial.nodeName);
        // // getRateInfo.add(getRate);
        // // deviceInfo.add(getRateInfo);
        // // allResource.put("deviceInfo", deviceInfo);
        // ArrayList<Object> deviceInfo = new ArrayList<>();
        // Map<String, Object> getRate = CPUUsageRate.getCPURate();
        // getRate.put("nodeName", HiBAInitial.nodeName);
        // deviceInfo.add(getRate.toString());
        // allResource.put("deviceInfo", deviceInfo);
        // }

        // HiBANetwork get資料用
        nodeResource = allResource;
        return allResource;
    }

    private HiBAService getService(String name) {
        HiBAService service = serviceList.get(name);
        if (service != null) {
            service = serviceList.get(name).getNewInstance();
        }
        return service;
    }

    private Map<String, Object> getLocalTime() {
        Map<String, Object> timeInfo = new HashMap<>();
        long timeStamp = System.currentTimeMillis();
        SimpleDateFormat dataFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_S");
        String date = dataFormat.format(new Date(Long.parseLong(String.valueOf(timeStamp))));
        timeInfo.put("timeStamp", timeStamp);
        timeInfo.put("date", date);
        return timeInfo;
    }

    private Map<String, Object> createErrorResult(String errorMessage) {
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("success", false);
        errorResult.put("error", errorMessage);
        return errorResult;
    }

    /**
     * 註冊HTTP請求結果監聽器
     */
    public void registerHttpRequestListener(RequestId requestId, HttpRequestResultListener listener) {
        httpRequestListeners.put(requestId, listener);
        logger.atDebug().addKeyValue("requestId", requestId).log("registerHttpRequestListener: Registered listener");
    }

    /**
     * 移除HTTP請求結果監聽器
     */
    public void unregisterHttpRequestListener(RequestId requestId) {
        HttpRequestResultListener removed = httpRequestListeners.remove(requestId);
        if (removed != null) {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .log("unregisterHttpRequestListener: Unregistered listener");
        } else {
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .log("unregisterHttpRequestListener: No listener found");
        }
    }

    /**
     * 通知HTTP請求監聽器結果已完成
     */
    private void notifyHttpRequestListeners(RequestId requestId, Map<String, Object> result) {
        HttpRequestResultListener listener = httpRequestListeners.get(requestId);
        if (listener != null) {
            logger.atDebug().addKeyValue("requestId", requestId).log("notifyHttpRequestListeners: Notifying listener");
            try {
                listener.onResult(result);
                // 結果通知後移除監聽器以防內存泄漏
                unregisterHttpRequestListener(requestId);
            } catch (Exception e) {
                logger.atError()
                        .addKeyValue("requestId", requestId)
                        .setCause(e)
                        .log("notifyHttpRequestListeners: Error notifying listener");
                listener.onError("Failed to process result: " + e.getMessage());
                unregisterHttpRequestListener(requestId);
            }
        }
    }
}
