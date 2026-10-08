package org.gslab.service.mService.multicast;

import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MulticastController implements Runnable {

    /*
     *
     * Multicast 廣播機制的主控制程式，主要大流程為: Ranges -> Connection -> Associate。
     * Ranges: 尋找週遭其他節點。
     * Connection: TCP連線取得節點分數。
     * Associate: TCP連線組織為群組。
     *
     * Ranges階段，Controller設為"init"模式，會呼叫MulticastPublisher 進行群播，發出自身節點資訊，
     * 同時也會接收其他節點的init訊息，並將其他節點資訊加入初始化節點清單(initNodeList)。若群播網路
     * 中已有Broker，則該Broker會廣播Broker資訊，使其可以直接加入該Broker群組，跳過Connection階段。
     *
     * Connection階段1.，該部份由HiBANetwork負責進行TCP Socket連線，會連接至initNodeList中所有節點，
     * 並接收他們的節點分數，並且選出initNodeList中最高分的節點設定為highestScoreNodeName(Multicas
     * tController.setHighestScoreNodeName)。此時Controller發現highestScoreBroadcast不為空時，
     * Controller設為"highestScoreBroadcast"模式，廣播自身判斷的最高分節點。
     *
     * Connection階段2.，接收到其他節點判斷到的最高分節點後，進行清票，檢查大家對於最高分節點的選擇是否
     * 一致，若不同，則擇最高票的節點選為Broker。此時，若local為Broker，會將Controller設為"isBroker"
     * 模式，並發出Broker廣播，反之設為"isClient"模式。
     *
     * Associate階段，Client接收到Broker廣播時，會設定HiBANetwork.brokerInformation，並且對其進行連
     * 線。由HiBANetwork進行TCP Socket連線，加入該Broker。
     *
     */

    private static final Logger LOGGER = LoggerFactory.getLogger(MulticastController.class);

    public static String mode = "init";
    public static final Map<String, Object> initNodeList = new HashMap<>();
    public static final Map<String, String> votingNodeList = new HashMap<>(); // Highest scoring node clearing votes.
    public static String localName = "";
    public static String highestScoreNodeName = "";
    private static final Map<String, Float> scoreMap = new HashMap<>();
    public static boolean interfaceState = true;
    public static boolean restartReceiver = true;

    public static long startMulticastTime;
    public static long endMulticastTime;
    final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    public void receiverController(String localName) {}

    public void run() {
        HashMap<String, Object> brokerContent = new HashMap<>();
        HashMap<String, Object> content = new HashMap<>();
        HashMap<String, Object> highestScoreInfo = new HashMap<>();
        int interfaceStatePrint = 0;
        String currentMode = "";
        int highestScoreNodeNameTimeout = 0;
        int checkVoteTimeout = 0;
        int isClientAndNoBrokerBroadcastTimeout = 0;
        localName = HiBAInitial.nodeName;

        startMulticastTime = System.currentTimeMillis();
        LOGGER.atInfo().log("啟動廣播時間: {}", dateFormat.format(startMulticastTime));

        while (true) {
            if (startMulticastTime == 0L) {
                startMulticastTime = System.currentTimeMillis();
                LOGGER.atInfo().log("啟動廣播時間: {}", dateFormat.format(startMulticastTime));
            }

            try {
                // 判斷網路線是否被拔除, 用於觸發Multicast Receiver重新載入用
                NetworkInterface networkIntface = NetworkInterface.getByName(HiBANetwork.MULTICAST_INTERFACE);
                if (networkIntface.isUp() && !getInterfaceState()) { // 網路線插回
                    LOGGER.atInfo().log("The network interface is up and running.");
                    setInterfaceState(true);
                }
                if (!networkIntface.isUp()) { // 網路線拔除
                    if (interfaceStatePrint >= 5) {
                        LOGGER.atInfo().log("Detected that the network cable has been unplugged.");
                        interfaceStatePrint = 0;
                    }
                    interfaceStatePrint += 5;
                    setInterfaceState(false);
                    setRestartReceiver(false);
                }
            } catch (Exception e) {
            }

            currentMode = getMode();

            delay(500);

            if ("None".equals(HiBANetwork.LOCAL_IP)) {
                continue;
            }

            if (HiBAInitial.mandatoryBroker) {
                currentMode = "isBroker";
                setMode("isBroker");
            }

            switch (currentMode) {
                    // Initialization mode: range. -> Confirm the nodes in the LAN
                case "init":
                    brokerContent.clear();
                    content.clear();
                    highestScoreInfo.clear();
                    initNodeList.clear();
                    votingNodeList.clear();
                    scoreMap.clear();
                    highestScoreNodeName = "";

                    content.put("mode", "init");
                    content.put("nodeName", localName);
                    content.put("ip", HiBANetwork.LOCAL_IP);
                    LOGGER.atInfo().log("Start Initializing Broadcast : {}", content);

                    for (int i = 0; i < HiBAInitial.initMCASTFrequency; i++) {
                        MulticastPublisher.sendMulticast(content);
                        delay(HiBAInitial.initMCASTTimeInterval);
                    }
                    if (!HiBANetwork.brokerInformation.isEmpty()) {
                        String ip = (String) HiBANetwork.brokerInformation.get("ip");
                        String nodeName = (String) HiBANetwork.brokerInformation.get("nodeName");
                        if (nodeName != null) {
                            LOGGER.atInfo().log("Find Broker : {}:{}", nodeName, ip);
                            setMode("isClient");
                        } else {
                            HiBANetwork.brokerInformation.clear();
                        }
                        break;
                    }
                    delay(500);

                    if (!initNodeList.isEmpty()) {
                        LOGGER.atInfo().log("Start Connection Stage.");
                        setMode("connection");
                    }
                    break;

                case "waiting":
                    break;

                case "connection":
                    // HiBANetwork performs TCP connection and judges the program.
                    // If the highest score node has been selected -> set mode = highestScoreBroadcast
                    if (highestScoreNodeName.isEmpty()) {
                        highestScoreNodeNameTimeout += 1;
                        /*
                         * 這部份可能需要加timeout判斷
                         * 因為有發生在這邊卡住的情況，判斷有可能是因為HiBANetwork
                         */
                        if (highestScoreNodeNameTimeout >= 20) {
                            LOGGER.atInfo().log("highestScoreNodeName.isEmpty Timeout.");
                            highestScoreNodeNameTimeout = 0;
                            setMode("init");
                        }
                        break;
                    }
                    setMode("highestScoreBroadcast");
                    break;

                case "highestScoreBroadcast":
                    highestScoreInfo.put("mode", "highestScoreBroadcast");
                    highestScoreInfo.put("nodeName", localName);
                    highestScoreInfo.put("highestScoreNode", getHighestScoreNodeName());
                    LOGGER.atInfo().log("Start the highest score node broadcast program : {}", highestScoreInfo);

                    for (int i = 0; i < HiBAInitial.highestScoreMCASTFrequency; i++) {
                        MulticastPublisher.sendMulticast(highestScoreInfo);
                        delay(HiBAInitial.highestScoreMCASTTimeInterval);
                    }
                    delay(1000);

                    if (!(MulticastController.getVotingNode().keySet()).equals(scoreMap.keySet())) { // 檢查是否收到清單中其他節點的分數
                        LOGGER.atInfo().log(
                                "check voting [Received/ScoreMap]: {} / {}",
                                MulticastController.getVotingNode().keySet(),
                                scoreMap.keySet());
                        checkVoteTimeout += 1;
                        if (checkVoteTimeout >= 4) { // timeout sec = 2s
                            setMode("init");
                            LOGGER.atInfo().log("Check voting node list timeout.");
                        }
                        break;
                    }
                    // if (MulticastController.getVotingNode().size() == 1) {
                    //     LOGGER.info("voting list no other nodes.");
                    //     setMode("init");
                    //     break;
                    //     // if (MulticastController.getVotingNode().keySet().iterator().next() ==
                    // HiBAInitial.nodeName) {
                    //     //     setMode("init");
                    //     //     LOGGER.info("voting list no other nodes.");
                    //     //     break;
                    //     // }
                    // }
                    String broker = voteCounting(MulticastController.getVotingNode());
                    if (broker.equals(localName)) {
                        LOGGER.atInfo().log("local is Broker.");
                        MulticastController.endMulticastTime = System.currentTimeMillis();
                        LOGGER.atInfo().log(
                                "> 當選Broker時間: {}", dateFormat.format(MulticastController.endMulticastTime));
                        long timeConsuming =
                                MulticastController.endMulticastTime - MulticastController.startMulticastTime;
                        LOGGER.atInfo().log("> 選舉總計耗時: {} sec.", timeConsuming / 1000.00);

                        brokerContent.put("mode", "brokerBroadcast");
                        brokerContent.put("nodeName", localName);
                        brokerContent.put("ip", HiBANetwork.LOCAL_IP);
                        LOGGER.atInfo().log("broker broadcast : {}", brokerContent);
                        for (int i = 0; i < HiBAInitial.isBrokerMCASTFrequency; i++) {
                            MulticastPublisher.sendMulticast(brokerContent);
                            delay(HiBAInitial.isBrokerMCASTTimeInterval);
                        }
                        setMode("isBroker");
                    } else if (broker.isEmpty()) {
                        LOGGER.atInfo().log("Vote counting error.");
                        setMode("init");
                    } else {
                        setMode("isClient");
                    }
                    break;

                case "isBroker":
                    break;

                case "isClient":
                    if (HiBANetwork.brokerInformation.isEmpty()) {
                        isClientAndNoBrokerBroadcastTimeout += 1;
                        if (isClientAndNoBrokerBroadcastTimeout >= 10) {
                            isClientAndNoBrokerBroadcastTimeout = 0;
                            LOGGER.atInfo().log("Waiting for broker broadcast timeout.");
                            setMode("init");
                        }
                    }
                    break;

                default:
                    break;
            }
        }
    }

    public static void addInitNode(Map<String, Object> nodeInfo) {
        if (!localName.equals(nodeInfo.keySet().stream().findFirst().get())) {
            initNodeList.putAll(nodeInfo);
        }
        HiBANetwork.mulitcastMap.putAll(initNodeList);
    }

    // 設定網路線連接狀態判斷 (被拔除False或連接正常True)
    public static void setInterfaceState(Boolean state) {
        interfaceState = state;
    }

    // 獲取當前網路線連接狀態 (用介面卡狀態來判斷)
    public static boolean getInterfaceState() {
        return interfaceState;
    }

    // MulticastReceiver重啟後 設為True
    public static void setRestartReceiver(Boolean state) {
        restartReceiver = state;
    }

    // MulticastReceiver 偵測網路線被拔除用
    public static boolean getRestartReceiver() {
        return restartReceiver;
    }

    public static void setHighestScoreNodeName(String nodeName) {
        highestScoreNodeName = nodeName;
    }

    public static String getHighestScoreNodeName() {
        return highestScoreNodeName;
    }

    public static void setScoreMap(Map<String, Float> scoreList) {
        scoreMap.putAll(scoreList);
    }

    public static Map<String, Float> getScoreMap() {
        return scoreMap;
    }

    public static void addVotingNode(String nodeName, String highestScoreString) {
        votingNodeList.put(nodeName, highestScoreString);
    }

    public static HashMap<String, String> getVotingNode() {
        return (HashMap<String, String>) votingNodeList;
    }

    public static String voteCounting(HashMap<String, String> votingNodeList) {
        try {
            HashMap<String, Integer> countVotes = new HashMap<>();
            ArrayList<String> candidate = new ArrayList<>();
            for (String key : votingNodeList.keySet()) {
                candidate.add(votingNodeList.get(key));
            }

            LOGGER.atInfo().log("Candidate list : {}", candidate);

            // 點票
            for (String s : candidate) {
                if (countVotes.containsKey(s)) {
                    int formerValue = countVotes.get(s) + 1;
                    countVotes.put(s, formerValue);
                } else {
                    countVotes.put(s, 1);
                }
            }

            // 取最高票
            Collection<Integer> count = countVotes.values();
            Integer maxCount = Collections.max(count);
            String broker = "";
            for (Entry<String, Integer> entry : countVotes.entrySet()) {

                if (entry.getValue().equals(maxCount)) {
                    broker = entry.getKey();
                }
            }
            return broker;
        } catch (Exception e) {
            System.out.println("triggering vote count exception.");
            e.printStackTrace();
            return "";
        }
    }

    public static void setMode(String setMode) {
        mode = setMode;
    }

    public static String getMode() {
        return mode;
    }

    private void delay(int time) {
        try {
            TimeUnit.MILLISECONDS.sleep(time);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        setMode("init");
        new Thread(new MulticastController()).start();
        new Thread(new MulticastReceiver()).start();
    }
}
