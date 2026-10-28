package org.gslab.sdns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import org.gslab.util.CPUInfo;
import org.gslab.util.JsonFileUtil;
import org.gslab.util.ResourceCleanupManager;
import org.gslab.configuration.HibaNetworkConfig;
import org.gslab.configuration.SystemConfigurationLoader;
import org.gslab.fileProtection.BlockchainFileProtectManager;
import org.gslab.fileProtection.config.BlockchainFileProtectManagerConfig;
import org.gslab.fileProtection.service.FileProtectionService;
import org.gslab.mService.multicast.Traceroute;
import org.gslab.tpm.TPMService;
import org.gslab.tpm.exception.TPMConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HiBAInitial {

    private static final Logger logger = LoggerFactory.getLogger(HiBAInitial.class);

    public static String sysName;
    public static String pubKey; // ssh reverse channel pub key.

    public static Map<String, String> hibaNetworkInterfaceInfo = new HashMap<>();

    // HiBANetwork environment content:
    public static boolean isAccountingServer;
    public static String nodeName;
    public static int HIBA_PORT;
    public static int DATA_PORT;
    public static int REVERSE_DATA_PORT;
    public static int HTTP_REQUEST_PORT;
    public static boolean reverseChannel;
    public static int multicastPort;
    public static String multicastIP;
    public static String accountingServer;
    public static boolean autoGetNetworkInterface;
    public static String multicastInterface;
    public static String interfaceAddress;
    public static boolean mandatoryBroker;
    public static boolean offlineBrokerScan; // 無網路broker暴力掃描
    public static String fileTransferDirectory;
    public static String fileUploadDirectory;
    public static String SSLPath;
    public static String clientContext;
    public static String serverContext;
    public static String devicePerformanceEvaluation;
    public static boolean tpmSignatureVerification;
    public static boolean blockchainFileProtect;

    // MobileNetwork environment content:
    public static boolean MobileControllerState;
    public static int connectionSignalQuality;
    public static int disconnectSignalQuality;
    public static String wifiInterface;
    public static int signalPrintCtrl;
    public static boolean startOBU; // 設備為OBU, 若為OBU需要設定router
    public static String OBURouterInterface; // 連接Router的Interface
    public static String OBURouterAddress; // 連接Router的Address
    public static String OBURouterGateway;
    // public static int OBUMulticastPort;

    // multicastController environment content:
    public static int initMCASTFrequency;
    public static int initMCASTTimeInterval;
    public static int highestScoreMCASTFrequency;
    public static int highestScoreMCASTTimeInterval;
    public static int isBrokerMCASTFrequency;
    public static int isBrokerMCASTTimeInterval;

    // requestListenerServiceDirectory environment content:
    public static String requestListenerServiceDirectory;
    public static boolean updateDeviceInfo;
    public static int updateDeviceInfoFrequency;

    // import service.json method
    public static final Map<String, Object> method = new HashMap<>();

    // check ubuntu device cpu information
    public static Map<String, String> cpuInfo = new HashMap<>();

    public static FileProtectionService fileProtectionService;
    public static TPMService tpmService;

    public static boolean start() {
        return start(null);
    }

    @SuppressWarnings("unchecked")
    public static boolean start(SystemConfigurationLoader configLoader) {
        File serviceFile = new File("./env.json");
        boolean state = false;
        sysName = System.getProperty("user.name");

        Map<String, Object> serviceTree = JsonFileUtil.ReadFile(serviceFile).toMap();
        Map<String, Object> hibaNetwork = (Map<String, Object>) serviceTree.get("HiBANetwork");
        Map<String, Object> multicastController = (Map<String, Object>) serviceTree.get("multicastController");
        Map<String, Object> mobileNetwork = (Map<String, Object>) serviceTree.get("MobileNetwork");
        Map<String, Object> hibaService = (Map<String, Object>) serviceTree.get("HiBAService");

        HibaNetworkConfig hibaNetworkConfig = configLoader.loadNetworkConfig();

        // HiBANetwork environment content:
        isAccountingServer = (Boolean) hibaNetwork.get("isAccountingServer"); // 尚未加至HiBANetwork主程式
        nodeName = (String) hibaNetwork.get("nodeName");
        HIBA_PORT = (int) hibaNetwork.get("HiBA_PORT");
        DATA_PORT = (int) hibaNetwork.get("DATA_PORT");
        REVERSE_DATA_PORT = (int) hibaNetwork.get("REVERSE_DATA_PORT");
        HTTP_REQUEST_PORT = (int) hibaNetwork.get("HTTP_REQUEST_PORT");
        reverseChannel = (Boolean) hibaNetwork.get("reverseChannel");
        if (reverseChannel && !isAccountingServer) {
            pubKey = getSSHPubKey(sysName);
        } else {
            pubKey = null;
        }
        multicastPort = (int) hibaNetwork.get("multicastPort");
        multicastIP = (String) hibaNetwork.get("multicastIP");
        accountingServer = (String) hibaNetwork.get("accountingServer");
        autoGetNetworkInterface = (Boolean) hibaNetwork.get("autoGetNetworkInterface");
        mandatoryBroker = (Boolean) hibaNetwork.get("mandatoryBroker");
        offlineBrokerScan = (Boolean) hibaNetwork.get("offlineBrokerScan");
        fileTransferDirectory = hibaNetworkConfig.getFileTransferDirectory();
        fileUploadDirectory = hibaNetworkConfig.getFileUploadDirectory();
        SSLPath = (String) hibaNetwork.get("SSL");
        clientContext = SSLPath + "/clientStore.jks";
        serverContext = SSLPath + "/serverStore.jks";
        devicePerformanceEvaluation = (String) hibaNetwork.get("devicePerformanceEvaluation");
        tpmSignatureVerification = Boolean.TRUE.equals(hibaNetworkConfig.getTpmSignatureVerification());
        blockchainFileProtect = Boolean.TRUE.equals(hibaNetworkConfig.getBlockchainFileProtect());

        // MobileNetwork environment content:
        MobileControllerState = (Boolean) mobileNetwork.get("state");
        connectionSignalQuality = (int) mobileNetwork.get("connectionSignalQuality");
        disconnectSignalQuality = (int) mobileNetwork.get("disconnectSignalQuality");
        wifiInterface = (String) mobileNetwork.get("wifiInterface");
        // signalPrintCtrl = (int) mobileNetwork.get("signalPrintCtrl");
        startOBU = (Boolean) mobileNetwork.get("OBU");
        if (startOBU) {
            OBURouterInterface = (String) mobileNetwork.get("OBURouterInterface");
            OBURouterAddress = (String) mobileNetwork.get("OBURouterAddress");
            OBURouterGateway = (String) mobileNetwork.get("OBURouterGateway");
            // OBUMulticastPort = (int) mobileNetwork.get("OBUMulticastPort");
            addDefaultRoute(OBURouterGateway, OBURouterInterface);
        }

        interfaceAddress = "";
        if (MobileControllerState) {
            // multicastInterface = wifiInterface;
            if (!checkWiFiInterface()) { // 檢查Wi-Fi網卡設定是否正確
                logger.atError().log("Exiting due to incorrect Wi-Fi interface setting.");
                System.exit(0);
            }
        }
        if (autoGetNetworkInterface) { // & MobileControllerState == false
            try {
                if (isAccountingServer) {
                    hibaNetworkInterfaceInfo = Traceroute.checkNetworkInterface(accountingServer);
                } else {
                    hibaNetworkInterfaceInfo =
                            Traceroute.connectAccountingServerToCheckInterface(accountingServer, HIBA_PORT);
                }
            } catch (Exception e) {
                logger.atError().log("System init error info:");
                logger.atError().log("------------");
                logger.atError().setCause(e).log("Exception during system initialization.");
                logger.atError().log("------------");
                logger.atError().log("Auto get network Interface error, and it is forced to close.");
                logger.atError().log("Please reset the Accounting Server Info or manually set the local IP.");
                logger.atError().log("Exiting due to network interface error.");
                System.exit(0);
            }
            if (hibaNetworkInterfaceInfo == null) {
                logger.atError().log("Unable to connect to Accounting Server.");
                logger.atError()
                        .log(
                                "If you still want to start the system, please manually set the LAN IP and Network Interface.");
                logger.atError().log("Exiting due to inability to connect to Accounting Server.");
                System.exit(0);
            }
        } else {
            multicastInterface = (String) hibaNetwork.get("multicastInterface");
            interfaceAddress = (String) hibaNetwork.get("interfaceAddress");
            hibaNetworkInterfaceInfo.put("interface", multicastInterface);
            hibaNetworkInterfaceInfo.put("address", interfaceAddress);
        }

        // multicastController environment content:
        initMCASTFrequency = (int) multicastController.get("initMCASTFrequency");
        initMCASTTimeInterval = (int) multicastController.get("initMCASTTimeInterval");
        highestScoreMCASTFrequency = (int) multicastController.get("highestScoreMCASTFrequency");
        highestScoreMCASTTimeInterval = (int) multicastController.get("highestScoreMCASTTimeInterval");
        isBrokerMCASTFrequency = (int) multicastController.get("isBrokerMCASTFrequency");
        isBrokerMCASTTimeInterval = (int) multicastController.get("isBrokerMCASTTimeInterval");

        requestListenerServiceDirectory = (String) hibaService.get("requestListenerServiceDirectory");
        updateDeviceInfo = (Boolean) hibaService.get("updateDeviceInfo");
        updateDeviceInfoFrequency = (int) hibaService.get("updateDeviceInfoFrequency");

        cpuInfo = CPUInfo.getCPUInfo();
        if (devicePerformanceEvaluation.equals("on")) {
            CPUInfo.getTOPS(cpuInfo.get("clock"), cpuInfo.get("core"));
        }

        BlockchainFileProtectManagerConfig blockchainManagerConfig = configLoader != null
                ? configLoader.loadBlockchainFileProtectManagerConfig()
                : new BlockchainFileProtectManagerConfig();

        fileProtectionService = BlockchainFileProtectManager.getFileProtectionService(blockchainManagerConfig);

        // Initialize shared TPMService instance
        try {
            tpmService = new TPMService();
            ResourceCleanupManager.getManager().addApplicationServiceResource(tpmService, "TPMService");
            logger.atInfo().log("Shared TPMService instance initialized successfully");
        } catch (TPMConnectionException e) {
            logger.atError().setCause(e).log("Failed to initialize TPMService");
            throw new RuntimeException("Failed to initialize TPMService", e);
        }

        ResourceCleanupManager.getManager()
                .addApplicationServiceResource(fileProtectionService, "FileProtectionService");
        state = true;
        return state;
    }

    public static void setMethod(Map<String, Object> info) {
        method.putAll(info);
    }

    public static Map<String, Object> getMethod() {
        return method;
    }

    public static Map<String, String> getCPUInfo() {
        return cpuInfo;
    }

    public static String getSSHPubKey(String sysName) {
        File file = new File("/home/" + sysName + "/.ssh/id_rsa.pub");
        String keyPub = "";
        try {
            FileReader fileRead = new FileReader(file);
            BufferedReader buffRead = new BufferedReader(fileRead);
            String line;
            while ((line = buffRead.readLine()) != null) {
                keyPub = line;
            }
            buffRead.close();
            return keyPub;
        } catch (FileNotFoundException e) {
            logger.atError()
                    .log(
                            "Read reverse channel pub key error, please check whether the reverse channel pub key exists.");
            logger.atError().log("Exiting due to reverse channel pub key error.");
            System.exit(0);
            return null;
        } catch (IOException e) {
            logger.atError().setCause(e).log("Exiting due to IOException in getSSHPubKey.");
            System.exit(0);
            return null;
        }
    }

    private static boolean checkWiFiInterface() {
        Process child;
        boolean state = false;
        String disconnect = "nmcli device";
        try {
            child = Runtime.getRuntime().exec(disconnect);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                if (content.contains(wifiInterface)) {
                    state = true;
                    break;
                }
            }
            return state;
        } catch (IOException e) {
            e.printStackTrace();
            return state;
        }
    }

    // 發現這樣可能會清除預設的gateway, 先不使用
    /*
     * public static void checkDefaultGateway(String networkInterface) {
     * Process child;
     * String command = "bash checkInterfaceGateway.sh " + networkInterface;
     * try {
     * child = Runtime.getRuntime().exec(command);
     * BufferedReader info = new BufferedReader(new
     * InputStreamReader(child.getInputStream()));
     * String content;
     * while ((content = info.readLine()) != null) {
     * if (content.contains("gateway")) {
     * String gateway = content.split("=")[1];
     * delDefaultRoute(gateway, networkInterface);
     * }
     * }
     * } catch (IOException e) {
     * e.printStackTrace();
     * }
     * }
     */

    public static void addDefaultRoute(String gateway, String networkInterface) {
        String addDefaultRoute = "sudo route add default gw " + gateway + " " + networkInterface;
        logger.atInfo().addKeyValue("command", addDefaultRoute).log("Executing command: {}", addDefaultRoute);
        try {
            Runtime.getRuntime().exec(addDefaultRoute);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void delDefaultRoute(String gateway, String networkInterface) {
        String delDefaultRoute = "sudo route del default gw " + gateway + " " + networkInterface;
        logger.atInfo().addKeyValue("command", delDefaultRoute).log("Executing command: {}", delDefaultRoute);
        try {
            Runtime.getRuntime().exec(delDefaultRoute);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
