package org.gslab;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gslab.util.JsonFileUtil;
import org.gslab.util.ResourceCleanupManager;
import org.gslab.configuration.HibaNetworkConfig;
import org.gslab.configuration.HibaServiceConfig;
import org.gslab.configuration.MobileNetworkConfig;
import org.gslab.configuration.ServiceConfiguration;
import org.gslab.configuration.SystemConfigurationLoader;
import org.gslab.errorHandling.FileCreateException;
import org.gslab.sdns.HiBAHttpRequestServer;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;
import org.gslab.mService.NTPServer;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoTrackStarter {

    static final Logger logger = LoggerFactory.getLogger(AutoTrackStarter.class);

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {

        SdnNetwork hiBASdnNetwork;

        // TODO: replace HiBAInitial with ConfigurationLoader
        SystemConfigurationLoader configLoader = new SystemConfigurationLoader("./env.json");
        HibaNetworkConfig hibaNetworkConfig = configLoader.loadNetworkConfig();
        MobileNetworkConfig mobileNetworkConfig = configLoader.loadMobileNetworkConfig();
        HibaServiceConfig hibaServiceConfig = configLoader.loadServiceConfig();

        // check if the necessary directories exist
        try {
            Utils.fileExists(hibaNetworkConfig.getFileTransferDirectory());
            Utils.fileExists(hibaServiceConfig.getRequestListenerServiceDirectory());
            Utils.fileExists("./receiveFile");
        } catch (FileCreateException e) {
            logger.atError().setCause(e).log("Failed to create directory: {}", e.getMessage());
            System.exit(1);
        }

        // Load System environment configuration.
        if (!HiBAInitial.start(configLoader)) {
            logger.atError().log("System environment initialization failed. Exiting.");
            System.exit(0);
        }

        if (!hibaNetworkConfig.isAccountingServer()) {
            hiBASdnNetwork = new HiBANetwork(hibaNetworkConfig, mobileNetworkConfig);
        } else {
            hiBASdnNetwork = new HiBANetwork();
            /*
             * Accounting server 開NTP Server
             * 使用BIO Socket server建立
             * 未來把這部分改進去Netty比較好
             */
            new Thread(new NTPServer()).start();
        }

        // load microService & system Service
        File serviceFile = new File("./service.json");

        Map<String, Object> serviceTree = JsonFileUtil.ReadFile(serviceFile).toMap();
        Map<String, Object> serviceMap = (Map<String, Object>) serviceTree.get("mService");
        Map<String, Object> inputRuleMap = new HashMap<>();
        Map<String, Object> resourceMap = new HashMap<>();
        Map<String, Object> methodMap = new HashMap<>();
        App app = new App(hiBASdnNetwork);

        JSONObject serviceConfig = new JSONObject(new JSONTokener(new FileInputStream(serviceFile)));
        serviceConfig = serviceConfig.getJSONObject("mService");

        logger.atInfo().log("Starting HiBA service");
        // 啟動HiBA微服務, 依照Json內的Distributor, Taker, Executor來動服務
        for (Map.Entry<String, Object> entry : serviceMap.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, Object> mServiceInfo = (Map<String, Object>) serviceMap.get(serviceName);
            JSONObject specificServiceConfig = serviceConfig.getJSONObject(serviceName);

            try {
                if (mServiceInfo.get("type").equals("java")) { // If mService is HiBAService, build to application.
                    Map<String, String> build = (Map<String, String>) mServiceInfo.get("build");
                    String disName = build.get("Distributor");
                    String exeName = build.get("Executor");
                    String takName = build.get("Taker");

                    List<String> inputs = specificServiceConfig.getJSONArray("input").toList().stream()
                            .map(Object::toString)
                            .toList();
                    JSONObject resources = specificServiceConfig.getJSONObject("resource");
                    JSONObject methods = specificServiceConfig.getJSONObject("method");

                    ArrayList<String> inputList = (ArrayList<String>) mServiceInfo.get("input");
                    inputRuleMap.put(serviceName, inputList);
                    Map<String, Object> resource = (Map<String, Object>) mServiceInfo.get("resource");

                    if (!resource.isEmpty()) {
                        for (String key : inputList) {
                            resourceMap.put(key, resource.get(key));
                        }
                    }

                    // hardcode of fileName resource
                    Object fileNameResource = resourceMap.computeIfAbsent("fileName", k -> new ArrayList<>());
                    if (fileNameResource instanceof List fileNameList) {
                        Set<String> uniqueFileName = new HashSet<>(getAllFileNameInResourcePath("./files/"));
                        fileNameList.addAll(uniqueFileName);
                        resourceMap.put("fileName", fileNameList);
                    }

                    Map<String, Object> method = (Map<String, Object>) mServiceInfo.get("method");

                    if (!method.isEmpty()) {
                        for (Map.Entry<String, Object> entry2 : method.entrySet()) {
                            String key = entry2.getKey();
                            methodMap.put(key, method.get(key));
                        }

                        HiBAInitial.setMethod(methodMap);
                    }

                    /*
                     * 由於分派任務需要統計到節點數量, 先以computility 並上傳節點分數, 依此使Broker方便統計底下節點數量
                     * 且當前尚未設計下對上檢查的分數回傳, 目前分數都是固定的 -> 這邊的固定是指: 啟動HiBA系統後得到的分數是不變的
                     */
                    if (!resourceMap.containsKey("computility")) {
                        ArrayList<ArrayList<Float>> scoreList = new ArrayList<>();
                        ArrayList<Float> score = new ArrayList<>();
                        score.add(Utils.getCPUScore());
                        scoreList.add(score);
                        resourceMap.put("computility", scoreList);
                    }

                    ServiceConfiguration config =
                            new ServiceConfiguration(serviceName, "java", inputs, new JSONObject(resourceMap), methods);

                    // Create instances with ServiceConfiguration
                    Distributor dis = createDistributorWithConfig(disName, config);
                    Executor exe = createExecutorWithConfig(exeName, config);
                    Taker tak = createTakerWithConfig(takName, config);
                    HiBAService service = new HiBAService.builder()
                            .Name(serviceName)
                                    .Distributor(dis)
                                    .Taker(tak)
                                    .Executor(exe)
                                    .ServiceConfiguration(config)
                                    .build();

                    app.addService(service);
                    app.setResource(resourceMap);
                    logger.atInfo().addKeyValue("serviceName", serviceName).log("load service: {}", serviceName);
                }
            } catch (Exception e) {
                logger.atError()
                        .addKeyValue("serviceName", serviceName)
                        .setCause(e)
                        .log("Failed to loading service: {}, {}", serviceName, e);
            }
        }

        // Start HTTP Request Server instead of folder monitoring
        logger.atInfo().log("Starting HTTP Request Server instead of folder monitoring");
        HiBAHttpRequestServer httpServer = new HiBAHttpRequestServer(app);
        Thread httpServerThread = new Thread(httpServer);
        httpServerThread.setDaemon(false);
        httpServerThread.start();

        ResourceCleanupManager cleanupManager = ResourceCleanupManager.getManager();
        cleanupManager.registerShutdownHook();

        // Keep main thread alive
        try {
            httpServerThread.join();
        } catch (InterruptedException e) {
            logger.atError().setCause(e).log("Main thread interrupted");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Create Distributor instance with ServiceConfiguration support
     */
    private static Distributor createDistributorWithConfig(String distributorName, ServiceConfiguration config) {
        try {
            Class<?> distributorClass = Class.forName("org.gslab.mService.distributor." + distributorName);

            // Try constructor with ServiceConfiguration first
            try {
                return (Distributor) distributorClass
                        .getDeclaredConstructor(ServiceConfiguration.class)
                        .newInstance(config);
            } catch (NoSuchMethodException e) {
                // Fallback to default constructor and set config via method if available
                Distributor distributor =
                        (Distributor) distributorClass.getDeclaredConstructor().newInstance();
                try {
                    distributorClass
                            .getMethod("setServiceConfiguration", ServiceConfiguration.class)
                            .invoke(distributor, config);
                } catch (NoSuchMethodException ex) {
                    logger.atWarn()
                            .addKeyValue("distributorName", distributorName)
                            .log("Warning: {} doesn't support ServiceConfiguration", distributorName);
                }
                return distributor;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create distributor: " + distributorName, e);
        }
    }

    /**
     * Create Executor instance with ServiceConfiguration support
     */
    private static Executor createExecutorWithConfig(String executorName, ServiceConfiguration config) {
        try {
            Class<?> executorClass = Class.forName("org.gslab.mService.executor." + executorName);

            // Try constructor with ServiceConfiguration first
            try {
                return (Executor) executorClass
                        .getDeclaredConstructor(ServiceConfiguration.class)
                        .newInstance(config);
            } catch (NoSuchMethodException e) {
                // Fallback to default constructor and set config via method if available
                Executor executor =
                        (Executor) executorClass.getDeclaredConstructor().newInstance();
                try {
                    executorClass
                            .getMethod("setServiceConfiguration", ServiceConfiguration.class)
                            .invoke(executor, config);
                } catch (NoSuchMethodException ex) {
                    logger.atWarn()
                            .addKeyValue("executorName", executorName)
                            .log("Warning: {} doesn't support ServiceConfiguration", executorName);
                }
                return executor;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create executor: " + executorName, e);
        }
    }

    /**
     * Create Taker instance with ServiceConfiguration support
     */
    private static Taker createTakerWithConfig(String takerName, ServiceConfiguration config) {
        try {
            Class<?> takerClass = Class.forName("org.gslab.mService.taker." + takerName);

            // Try constructor with ServiceConfiguration first
            try {
                return (Taker) takerClass
                        .getDeclaredConstructor(ServiceConfiguration.class)
                        .newInstance(config);
            } catch (NoSuchMethodException e) {
                // Fallback to default constructor and set config via method if available
                Taker taker = (Taker) takerClass.getDeclaredConstructor().newInstance();
                try {
                    takerClass
                            .getMethod("setServiceConfiguration", ServiceConfiguration.class)
                            .invoke(taker, config);
                } catch (NoSuchMethodException ex) {
                    logger.atWarn()
                            .addKeyValue("takerName", takerName)
                            .log("Warning: {} doesn't support ServiceConfiguration", takerName);
                }
                return taker;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create taker: " + takerName, e);
        }
    }

    /**
     * Initialize resource path from service configuration and check file protection status
     */
    private static List<String> getAllFileNameInResourcePath(String resourcePath) {
        File resourceDir = new File(resourcePath);
        if (!resourceDir.exists() || !resourceDir.isDirectory()) {
            logger.atWarn()
                    .addKeyValue("resourcePath", resourcePath)
                    .log("Resource directory does not exist or is not a directory: {}", resourcePath);
            return List.of();
        }

        File[] files = resourceDir.listFiles();
        if (files == null || files.length == 0) {
            logger.atInfo()
                    .addKeyValue("resourcePath", resourcePath)
                    .log("No files found in resource directory: {}", resourcePath);
            return List.of();
        }

        // Filter to include only files, excluding directories
        logger.atInfo().log("Filtering valid files from resource directory");

        List<String> fileNameList =
                Arrays.stream(files).filter(File::isFile).map(File::getName).toList();

        fileNameList.forEach(
                fileName -> logger.atInfo().addKeyValue("fileName", fileName).log("Valid file found: {}", fileName));

        if (fileNameList.isEmpty()) {
            logger.atInfo()
                    .addKeyValue("resourcePath", resourcePath)
                    .log("No valid files to process in resource directory: {}", resourcePath);
            return List.of();
        }

        return fileNameList;
    }
}
