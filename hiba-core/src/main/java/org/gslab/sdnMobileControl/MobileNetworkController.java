package org.gslab.sdnMobileControl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MobileNetworkController extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(MobileNetworkController.class);

    private final Map<String, Object> allWiFiList;

    public MobileNetworkController(Map<String, Object> allWiFiList) {
        this.allWiFiList = allWiFiList;
    }

    @SuppressWarnings("unchecked")
    public void run() {
        String goodSignalWiFiName = "";
        ArrayList<String> InitializedWifiName = new ArrayList<>(); // 已登錄過的Wi-Fi名稱
        Boolean connectionStatus = false; // 當前是否已連線至RSU的狀態
        String connectingWiFiName = ""; // 連線的RSU Wi-Fi名稱
        int wifiSignal = 0; // RSU Wi-Fi 訊號值
        for (String wifiName : this.allWiFiList.keySet()) {
            InitializedWifiName.add(wifiName);
        }
        boolean state = startDevice();
        int disconnectCount = 0;
        int searchCount = 0;
        String lastStationName = "None";
        int repeatStationTimeoutCount = 0;
        if (state) {
            logger.atInfo().log("Start mobile network controller.");
            while (true) {
                try {
                    goodSignalWiFiName = wifiSignalFilter(InitializedWifiName);
                    if (!connectionStatus & !goodSignalWiFiName.isEmpty()) {
                        if (goodSignalWiFiName.equals(
                                lastStationName)) { // 重複基站過濾, 若已離開上一個Station, 為避免訊號很弱或不穩的情況下, 又重新連上同一個同一個Station
                            repeatStationTimeoutCount += 1;
                            if (repeatStationTimeoutCount >= 20) {
                                lastStationName = "None";
                                repeatStationTimeoutCount = 0;
                            } else {
                                goodSignalWiFiName = "";
                                Thread.sleep(500);
                                continue;
                            }
                        } else {
                            logger.atInfo()
                                    .addKeyValue("baseStation", goodSignalWiFiName)
                                    .log("Find the base station");
                            ArrayList<String> stationInfo = (ArrayList<String>) allWiFiList.get(goodSignalWiFiName);
                            String password = stationInfo.get(0);
                            String gateway = stationInfo.get(1);
                            connectingWiFiName = connectWiFi(goodSignalWiFiName, password);
                            if (!connectingWiFiName.isEmpty()) {
                                HiBANetwork.LOCAL_IP = ckeckCurrentIP();
                                addDefaultRoute(gateway);
                                logger.atInfo()
                                        .addKeyValue("connectedWiFi", connectingWiFiName)
                                        .addKeyValue("localIP", HiBANetwork.LOCAL_IP)
                                        .log("Connected successfully");
                                connectionStatus = true;
                                lastStationName = connectingWiFiName;
                            } else {
                                continue;
                            }
                        }
                        // LOGGER.info("Find the base station: " + goodSignalWiFiName);
                        // String password = allWiFiList.get(goodSignalWiFiName);
                        // connectingWiFiName = connectWiFi(goodSignalWiFiName, password);
                        // if (connectingWiFiName != "") {
                        //     HiBANetwork.LOCAL_IP = ckeckCurrentIP();
                        //     LOGGER.info("Connected successfully: " + connectingWiFiName + " local IP: " +
                        // HiBANetwork.LOCAL_IP);
                        //     connectionStatus = true;
                        //     searchCount = 0;
                        // } else {
                        //     continue;
                        // }
                    } else if (!connectionStatus & goodSignalWiFiName.isEmpty()) {
                        logger.atInfo().log("Searching...");
                        if (searchCount >= 6) {
                            startDevice();
                            System.out.println("------ Searching restart device ------");
                            searchCount = 0;
                        } else {
                            searchCount += 1;
                        }
                    } else {
                        wifiSignal = signalCheck(connectingWiFiName);
                        logger.atInfo()
                                .addKeyValue("rsu", goodSignalWiFiName)
                                .addKeyValue("wifiSignal", wifiSignal)
                                .log("RSU signal");
                        if (wifiSignal <= HiBAInitial.disconnectSignalQuality || goodSignalWiFiName.isEmpty()) {
                            disconnectCount += 1;
                        } else {
                            disconnectCount = 0;
                        }
                        if (disconnectCount >= 4) {
                            logger.info(
                                    "The connection quality is lower than the threshold, and the connection is automatically terminated.");
                            disconnectWiFi(HiBAInitial.wifiInterface);
                            HiBANetwork.LOCAL_IP = "None";
                            connectionStatus = false;
                            disconnectCount = 0;
                            searchCount = 0;
                        }
                    }
                    Thread.sleep(500);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else {
            logger.atInfo().log("Mobile network controller initialization failed.");
        }
    }

    public static boolean startDevice() {
        ArrayList<String> uuidList = new ArrayList<>();
        Process child;
        String offDevice = "nmcli r wifi off";
        String startDevice = "nmcli r wifi on";
        String getUUID = "nmcli -t -f UUID connection";
        String removeUUID = "nmcli c delete ";
        try {
            child = Runtime.getRuntime().exec(getUUID);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                uuidList.add(content);
            }
            for (String uuid : uuidList) {
                String command = removeUUID + uuid;
                Runtime.getRuntime().exec(command);
            }
            Runtime.getRuntime().exec(offDevice);
            Thread.sleep(100);
            Runtime.getRuntime().exec(startDevice);
            return true;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String wifiSignalFilter(ArrayList<String> wifiList) {
        String goodSignal = "";
        Process child;
        // String command = "nmcli -t -f SSID,SIGNAL dev wifi list | awk -F: '$2 < 50 {print $1}'";
        String command = "bash ./scripts/checkRSUSignal.sh " + HiBAInitial.disconnectSignalQuality;
        try {
            child = Runtime.getRuntime().exec(command);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                String stationName = content.split(":")[0];
                if (!wifiList.contains(stationName)) {
                    continue;
                } else if (wifiList.contains(stationName)) { // 訊號大於斷線門檻之Wi-Fi
                    goodSignal = stationName;
                    break;
                }
            }
            return goodSignal;
        } catch (IOException e) {
            e.printStackTrace();
            return goodSignal;
        }
    }

    public static String connectWiFi(String wifiName, String wifiPassword) {
        Process child;
        String connectingWiFiName = "";
        String command = "nmcli d wifi connect " + wifiName + " password " + wifiPassword;

        try {
            child = Runtime.getRuntime().exec(command);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                if (content.contains("successfully")) {
                    connectingWiFiName = wifiName;
                    break;
                }
                if (content.contains("Error")) {
                    connectingWiFiName = "";
                    break;
                }
            }
            return connectingWiFiName;
        } catch (IOException e) {
            e.printStackTrace();
            return connectingWiFiName;
        }
    }

    public static boolean disconnectWiFi(String inrerface) {
        Process child;
        boolean connectionStatus = false;
        String disconnect = "nmcli dev disconnect iface " + inrerface;
        try {
            child = Runtime.getRuntime().exec(disconnect);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                if (content.contains("successfully")) {
                    logger.atInfo().log("successfully disconnected.");
                    connectionStatus = true;
                    break;
                }
            }
            return connectionStatus;
        } catch (IOException e) {
            e.printStackTrace();
            return connectionStatus;
        }
    }

    public static int signalCheck(String connectingWiFiName) {
        Process child;
        // String checkSignal = "nmcli -t -f SSID,SIGNAL dev wifi list";
        String command = "bash ./scripts/checkRSUSignal.sh " + HiBAInitial.disconnectSignalQuality;
        int signal = 0;
        try {
            child = Runtime.getRuntime().exec(command);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                String station = content.split(":")[0];
                if (connectingWiFiName.equals(station)) {
                    signal = Integer.parseInt(content.split(":")[1]);
                    break;
                }
            }
            return signal;
        } catch (IOException e) {
            e.printStackTrace();
            return signal;
        }
    }

    public static boolean disconnectCheck() {
        Process child;
        String checkSignal = "nmcli connection show --active";
        boolean state = true;
        try {
            child = Runtime.getRuntime().exec(checkSignal);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                if (content.contains(HiBAInitial.wifiInterface)) {
                    state = false;
                }
            }
            return state;
        } catch (IOException e) {
            e.printStackTrace();
            return state;
        }
    }

    public static String ckeckCurrentIP() {
        Process child;
        String checkSignal = "nmcli d show " + HiBAInitial.wifiInterface;
        String ip = null;
        try {
            child = Runtime.getRuntime().exec(checkSignal);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                if (content.contains("IP4.ADDRESS[1]")) {
                    int lastIndex = content.lastIndexOf(" ") + 1;
                    ip = (content.substring(lastIndex)).split("/")[0];
                    break;
                }
            }
            return ip;
        } catch (IOException e) {
            e.printStackTrace();
            return ip;
        }
    }

    public static void addDefaultRoute(String gateway) {
        String addDefaultRoute = "route add default gw " + gateway + " " + HiBAInitial.wifiInterface;
        System.out.println("cmd: " + addDefaultRoute);
        try {
            Runtime.getRuntime().exec(addDefaultRoute);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        if (!HiBAInitial.start()) {
            System.out.println("System environment initialization failed.");
            System.exit(0);
        }
        Map<String, Object> RSUWiFiList = new HashMap<>();
        RSUWiFiList.put("ASUSXIU_2.4G", "qqq1234567");
        RSUWiFiList.put("Staff_2.4G", "qqq12345");
        RSUWiFiList.put("HiBA_NAT1", "qqq12345");
        RSUWiFiList.put("HiBA_NAT2", "qqq12345");
        System.out.println("download test wifi list: " + RSUWiFiList);
        MobileNetworkController thread = new MobileNetworkController(RSUWiFiList);
        thread.start();

        // ArrayList<String> InitializedWifiName = new ArrayList<>();     // 已登錄過的Wi-Fi名稱
        // for (String wifiName : RSUWiFiList.keySet()) {
        //     InitializedWifiName.add(wifiName);
        // }
        // String goodSignal = wifiSignalFilter(InitializedWifiName);
        // if (goodSignal != "") {
        //     System.out.println(goodSignal);
        // } else {
        //     System.out.println("為空: " + goodSignal);
        // }
    }
}
