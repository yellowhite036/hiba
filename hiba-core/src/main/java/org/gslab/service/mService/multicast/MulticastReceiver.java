package org.gslab.service.mService.multicast;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.util.HashMap;
import java.util.Map;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;

public class MulticastReceiver implements Runnable {

    // private static final String MULTICAST_INTERFACE = HiBANetwork.MULTICAST_INTERFACE; //eno1  eth0
    private static final int MULTICAST_PORT = HiBAInitial.multicastPort;
    private static final String MULTICAST_IP = HiBAInitial.multicastIP;

    public static void receiveInfo() {}

    public void run() {
        try {
            while (true) {
                if ("None".equals(HiBANetwork.LOCAL_IP)) {
                    Thread.sleep(500);
                    continue;
                }
                String CurrentIP = HiBANetwork.LOCAL_IP;
                String MULTICAST_INTERFACE = HiBANetwork.MULTICAST_INTERFACE;

                if (!MulticastController.getInterfaceState()) { // 網路線被拔除
                    Thread.sleep(500);
                    continue;
                } else { // 網路線連線正常
                    MulticastController.setRestartReceiver(true);
                }

                // Searches for the network interface with the specified name.
                NetworkInterface networkInterface = DatagramChannelBuilder.ifaceName(MULTICAST_INTERFACE);

                // Create a datagram channel to receive datagrams from a multicast group that uses IPv4 address type
                DatagramChannel datagramChannel = DatagramChannelBuilder.receiveChannel(StandardProtocolFamily.INET)
                        .setOption(StandardSocketOptions.SO_REUSEADDR, true)
                        .bind(new InetSocketAddress(MULTICAST_PORT))
                        .setOption(StandardSocketOptions.IP_MULTICAST_IF, networkInterface);

                datagramChannel.join(InetAddress.getByName(MULTICAST_IP), networkInterface);

                while (true) {
                    try {
                        Map<String, Object> information = new HashMap<>();
                        String currentMode = "";
                        // System.out.println("Waiting for the multicast broadcast message...");
                        MembershipKey membershipKey = datagramChannel.join(
                                InetAddress.getByName(MULTICAST_IP),
                                networkInterface); // MembershipKey class is used for block multicast message source.
                        String data = DatagramChannelBuilder.receiveBuffer(datagramChannel);
                        information.putAll(DatagramChannelBuilder.converToMap(data));
                        currentMode = MulticastController.getMode();

                        if (!CurrentIP.equals(HiBANetwork.LOCAL_IP)) {
                            /*
                             * MobileController換網域時, 會改變HiBANetwork.LOCAL_IP
                             * 若當前IP (CurrentIP)與 MobileController給的LOCAL_IP不同, 則代表已經換網路
                             * 此時break掉, 重啟MulticastReceiver
                             */
                            break;
                        }
                        if (!MulticastController.getRestartReceiver()) { // 網路線被拔除
                            membershipKey.drop();
                            datagramChannel.close();
                            break;
                        }

                        if (information.get("nodeName").equals(HiBAInitial.nodeName)
                                & !information.get("mode").equals("highestScoreBroadcast")) {
                            continue;
                        }
                        if (information.get("mode").equals("checkVoteTimeout")) {
                            HashMap<String, Object> checkVoteTimeoutResponse = new HashMap<>();
                            checkVoteTimeoutResponse.put("mode", "highestScoreBroadcast");
                            checkVoteTimeoutResponse.put("nodeName", MulticastController.localName);
                            checkVoteTimeoutResponse.put("ip", HiBANetwork.LOCAL_IP);
                            MulticastPublisher.sendMulticast(checkVoteTimeoutResponse);
                        }
                        switch (currentMode) {
                                // Initialization mode: range. -> Receive other node information
                            case "init":
                                if (information.get("mode").equals("init")) {
                                    Map<String, Object> initList = new HashMap<>();
                                    initList.put((String) information.get("nodeName"), information.get("ip"));
                                    MulticastController.addInitNode(initList);
                                } else if (information.get("mode").equals("brokerBroadcast")
                                        && !HiBANetwork.blackList.containsKey((String) information.get("nodeName"))) {
                                    String nodeName = (String) information.get("nodeName");
                                    String ip = (String) information.get("ip");
                                    HiBANetwork.brokerInformation.put("nodeName", nodeName);
                                    HiBANetwork.brokerInformation.put("ip", ip);
                                }
                                break;

                            case "waiting":
                                break;

                            case "isBroker":
                                if (information.get("mode").equals("init")) {
                                    HashMap<String, Object> brokerContent = new HashMap<>();
                                    brokerContent.put("mode", "brokerBroadcast");
                                    brokerContent.put("nodeName", MulticastController.localName);
                                    brokerContent.put("ip", HiBANetwork.LOCAL_IP);
                                    MulticastPublisher.sendMulticast(brokerContent);
                                }
                                break;

                            case "isClient":
                                if (information.get("mode").equals("brokerBroadcast")) {
                                    String nodeName = (String) information.get("nodeName");
                                    String ip = (String) information.get("ip");
                                    HiBANetwork.brokerInformation.put("nodeName", nodeName);
                                    HiBANetwork.brokerInformation.put("ip", ip);
                                }
                                break;

                            case "connection":
                                break;

                            case "highestScoreBroadcast":
                                if (information.get("mode").equals("highestScoreBroadcast")) {
                                    String nodeName = (String) information.get("nodeName");
                                    String highestScoreNode = (String) information.get("highestScoreNode");
                                    MulticastController.addVotingNode(nodeName, highestScoreNode);
                                }
                            default:
                                break;
                        }
                        membershipKey.drop();
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                        break;
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
