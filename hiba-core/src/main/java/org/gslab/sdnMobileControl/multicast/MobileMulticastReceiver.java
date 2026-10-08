package org.gslab.sdnMobileControl.multicast;

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

public class MobileMulticastReceiver implements Runnable {

    // private static final String MULTICAST_INTERFACE = HiBANetwork.MULTICAST_INTERFACE; //eno1  eth0
    private static final int MULTICAST_PORT = HiBAInitial.multicastPort;
    private static final String MULTICAST_IP = HiBAInitial.multicastIP;

    public static void receiveInfo() {}

    public void run() {
        try {
            String MULTICAST_INTERFACE = HiBAInitial.OBURouterInterface;

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
                    String data = DatagramChannelBuilder.recevieBuffer(datagramChannel);
                    information.putAll(DatagramChannelBuilder.converToMap(data));

                    // currentMode = MobileMulticastController.getMode();
                    currentMode = "isBroker";

                    if (information.get("nodeName").equals(HiBAInitial.nodeName)) {
                        continue;
                    }
                    // System.out.println("MobileMulticastReceiver接收廣播 -> " + data);
                    /*
                    if (information.get("mode").equals("checkVoteTimeout")) {
                        HashMap<String, Object> checkVoteTimeoutResponse = new HashMap<>();
                        checkVoteTimeoutResponse.put("mode", "highestScoreBroadcast");
                        checkVoteTimeoutResponse.put("nodeName", MobileMulticastController.localName);
                        checkVoteTimeoutResponse.put("ip", HiBANetwork.LOCAL_IP);
                        MobileMulticastPublisher.sendMulticast(checkVoteTimeoutResponse);
                    }
                    */
                    switch (currentMode) {
                            // Initialization mode: range. -> Receive other node information
                            /*
                            case "init":
                                if (information.get("mode").equals("init")) {
                                    Map<String, Object> initList = new HashMap<String, Object>();
                                    initList.put((String) information.get("nodeName"), information.get("ip"));
                                    MobileMulticastController.addInitNode(initList);
                                } else if (information.get("mode").equals("brokerBroadcast") && !HiBANetwork.blackList.containsKey((String) information.get("nodeName"))) {
                                    String nodeName = (String) information.get("nodeName");
                                    String ip = (String) information.get("ip");
                                    HiBANetwork.brokerInformation.put("nodeName", nodeName);
                                    HiBANetwork.brokerInformation.put("ip", ip);
                                }
                                break;
                            */
                        case "waiting":
                            break;

                        case "isBroker":
                            if (information.get("mode").equals("init")) {
                                HashMap<String, Object> brokerContent = new HashMap<>();
                                brokerContent.put("mode", "brokerBroadcast");
                                brokerContent.put("nodeName", HiBAInitial.nodeName);
                                brokerContent.put("ip", HiBAInitial.OBURouterAddress);
                                MobileMulticastPublisher.sendMulticast(brokerContent);
                            }
                            break;

                            /*
                             * case "isClient":
                             * if (information.get("mode").equals("brokerBroadcast")) {
                             * String nodeName = (String) information.get("nodeName");
                             * String ip = (String) information.get("ip");
                             * HiBANetwork.brokerInformation.put("nodeName", nodeName);
                             * HiBANetwork.brokerInformation.put("ip", ip);
                             * }
                             * break;
                             *
                             * case "connection":
                             * break;
                             *
                             * case "highestScoreBroadcast":
                             * if (information.get("mode").equals("highestScoreBroadcast")) {
                             * String nodeName = (String) information.get("nodeName");
                             * String highestScoreNode = (String) information.get("highestScoreNode");
                             * MobileMulticastController.addVotingNode(nodeName, highestScoreNode);
                             * }
                             */
                        default:
                            break;
                    }
                    membershipKey.drop();
                } catch (Exception e) {
                    break;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
