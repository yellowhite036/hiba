package org.gslab.sdnMobileControl.multicast;

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

public class MobileMulticastController implements Runnable {

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

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileMulticastController.class);

    public static String mode = "init";
    public static final Map<String, Object> initNodeList = new HashMap<>();
    public static final Map<String, String> votingNodeList = new HashMap<>(); // Highest scoring node clearing votes.
    public static String localName = "";
    public static String highestScoreNodeName = "";
    private static final Map<String, Float> scoreMap = new HashMap<>();

    public static long startMulticastTime;
    public static long endMulticastTime;

    public void receiverController(String localName) {}

    public void run() {
        HashMap<String, Object> brokerContent = new HashMap<>();
        HashMap<String, Object> content = new HashMap<>();
        HashMap<String, Object> highestScoreInfo = new HashMap<>();
        String currentMode = "";
        int checkVoteTimeout = 0;
        int isClientAndNoBrokerBroadcastTimeout = 0;
        localName = HiBAInitial.nodeName;

        startMulticastTime = System.currentTimeMillis();
        System.out.println("> 啟動廣播時間: " + startMulticastTime + " Time Millis");
        while (true) {

            if (startMulticastTime == 0L) {
                startMulticastTime = System.currentTimeMillis();
                System.out.println("> 啟動廣播時間: " + startMulticastTime + " Time Millis");
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
                    LOGGER.atInfo().addKeyValue("content", content).log("Start Initializing Broadcast");

                    for (int i = 0; i < HiBAInitial.initMCASTFrequency; i++) {
                        MobileMulticastPublisher.sendMulticast(content);
                        delay(HiBAInitial.initMCASTTimeInterval);
                    }
                    if (!HiBANetwork.brokerInformation.isEmpty()) {
                        LOGGER.atInfo()
                                .addKeyValue("brokerNodeName", HiBANetwork.brokerInformation.get("nodeName"))
                                .log("Find Broker");
                        setMode("isClient");
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
                        break;
                    }
                    setMode("highestScoreBroadcast");
                    break;

                case "highestScoreBroadcast":
                    highestScoreInfo.put("mode", "highestScoreBroadcast");
                    highestScoreInfo.put("nodeName", localName);
                    highestScoreInfo.put("highestScoreNode", getHighestScoreNodeName());
                    LOGGER.atInfo()
                            .addKeyValue("highestScoreInfo", highestScoreInfo)
                            .log("Start the highest score node broadcast program");

                    for (int i = 0; i < HiBAInitial.highestScoreMCASTFrequency; i++) {
                        MobileMulticastPublisher.sendMulticast(highestScoreInfo);
                        delay(HiBAInitial.highestScoreMCASTTimeInterval);
                    }
                    delay(1000);

                    if (!(MobileMulticastController.getVotingNode().keySet())
                            .equals(scoreMap.keySet())) { // 檢查是否收到清單中其他節點的分數
                        LOGGER.atInfo()
                                .addKeyValue(
                                        "receivedVotes",
                                        MobileMulticastController.getVotingNode()
                                                .keySet())
                                .addKeyValue("scoreMapKeys", scoreMap.keySet())
                                .log("Check voting");
                        checkVoteTimeout += 1;
                        if (checkVoteTimeout >= 4) { // timeout sec = 2s
                            setMode("init");
                            LOGGER.atInfo().log("Check voting node list timeout.");
                        }
                        break;
                    }

                    String broker = voteCounting(MobileMulticastController.getVotingNode());
                    if (broker.equals(localName)) {
                        LOGGER.atInfo().log("local is Broker.");
                        brokerContent.put("mode", "brokerBroadcast");
                        brokerContent.put("nodeName", localName);
                        brokerContent.put("ip", HiBANetwork.LOCAL_IP);
                        LOGGER.atInfo()
                                .addKeyValue("brokerContent", brokerContent)
                                .log("broker broadcast");
                        for (int i = 0; i < HiBAInitial.isBrokerMCASTFrequency; i++) {
                            MobileMulticastPublisher.sendMulticast(brokerContent);
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

            LOGGER.atInfo().addKeyValue("candidateList", candidate).log("Candidate list");

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
        new Thread(new MobileMulticastController()).start();
        new Thread(new MobileMulticastReceiver()).start();
    }
}
