package org.gslab.mService.multicast;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.gslab.message_hiba.connectionTestMessenge;
import org.gslab.protocol.HiBAFrameDecoder;
import org.gslab.protocol.MessageCodec;
import org.gslab.sdns.HiBAInitial;

public class Traceroute {

    public static String upperHost = "";

    public static String doTraceroute() {
        String command = "";
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");

        if (isWindows) {
            command = "tracert -4 -h 2 8.8.8.8";
        } else {
            command = "traceroute 8.8.8.8 -m2";
        }

        try {
            Process child = Runtime.getRuntime().exec(command);
            BufferedReader tracertInfo = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = tracertInfo.readLine()) != null) {
                int len = content.length();
                if (len > 0) {
                    String tracertNumber = (content.replace(" ", "")).substring(0, 1);
                    if (tracertNumber.equals("2")) {
                        if (isWindows) {
                            String Comma = content.replace(" ", ",");
                            if (Comma.lastIndexOf(",") == len - 1) { // 判斷最後一位還是","
                                Comma = Comma.substring(0, len - 1);
                            }
                            int index = Comma.lastIndexOf(",") + 1;
                            upperHost = (String) Comma.subSequence(index, Comma.length());
                        } else {
                            String Comma = (content.replace(" ", ",")).substring(4, len);
                            int index = Comma.indexOf(",");
                            upperHost = Comma.substring(0, index);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Get upperHost error.");
            e.printStackTrace();
        }
        return upperHost;
    }

    public static Map<String, String> checkNetworkInterface(String targetIP) {
        Map<String, String> targetInterfaceInfo = new HashMap<>();
        String command = "";
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            command = "ping -4 -n 1 -S ";
        } else if (!isWindows) {
            command = "bash ./scripts/checkNetworkInterface.sh " + HiBAInitial.accountingServer;
        } else {
            System.out.println("Automatically detect network interface, does not support this Operation System.");
            System.exit(0);
        }
        try {
            Enumeration<NetworkInterface> allNetworkInterface = NetworkInterface.getNetworkInterfaces();
            while (allNetworkInterface.hasMoreElements()) {
                NetworkInterface intfacs = allNetworkInterface.nextElement();
                Enumeration<InetAddress> addresses = intfacs.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress inetAddress = addresses.nextElement();

                    if (inetAddress instanceof Inet4Address) {
                        String intfacsAddress = inetAddress.getHostAddress();
                        String intfacsName = intfacs.getName();
                        if (!intfacsAddress.equals("127.0.0.1")) {
                            try {
                                if (isWindows) {
                                    Process child =
                                            Runtime.getRuntime().exec(command + intfacsAddress + " " + targetIP);
                                    BufferedReader info =
                                            new BufferedReader(new InputStreamReader(child.getInputStream()));
                                    String content;
                                    while ((content = info.readLine()) != null) {
                                        if (content.contains("TTL")) {
                                            targetInterfaceInfo.put("interface", intfacsName);
                                            targetInterfaceInfo.put("address", intfacsAddress);
                                        }
                                    }
                                } else {
                                    Process child = Runtime.getRuntime().exec(command);
                                    BufferedReader info =
                                            new BufferedReader(new InputStreamReader(child.getInputStream()));
                                    String content;
                                    while ((content = info.readLine()) != null) {
                                        if (content.contains("online")) {
                                            targetInterfaceInfo.put("interface", (String)
                                                    content.subSequence(0, content.lastIndexOf(" ")));
                                            targetInterfaceInfo.put("address", intfacsAddress);
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                System.err.println("Get network interface error.");
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Get network interface error.");
        }
        return targetInterfaceInfo;
    }

    public static Map<String, String> connectAccountingServerToCheckInterface(String host, int port) {
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        Map<String, String> response = new HashMap<>();
        try {
            bootstrap
                    .group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                    .handler(
                            new ChannelInitializer<>() { // 創建一個通道的初始化對象

                                @Override
                                protected void initChannel(Channel channel) {
                                    channel.pipeline().addLast(new HiBAFrameDecoder());
                                    channel.pipeline().addLast(new MessageCodec());
                                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                        String localIp = "";

                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                            ctx.channel().writeAndFlush(new connectionTestMessenge());
                                            InetSocketAddress socketAddress = (InetSocketAddress)
                                                    ctx.channel().localAddress();
                                            localIp = socketAddress.getAddress().getHostAddress();
                                            System.out.println(localIp);
                                            Enumeration<NetworkInterface> networkInterfaces =
                                                    NetworkInterface.getNetworkInterfaces();
                                            while (networkInterfaces.hasMoreElements()) {
                                                NetworkInterface networkInterface = networkInterfaces.nextElement();
                                                Enumeration<InetAddress> addresses =
                                                        networkInterface.getInetAddresses();
                                                while (addresses.hasMoreElements()) {
                                                    InetAddress address = addresses.nextElement();
                                                    if (address.getHostAddress().equals(localIp)) {
                                                        System.out.println("Network interface name: "
                                                                + networkInterface.getName());
                                                        response.put("interface", networkInterface.getName());
                                                        response.put("address", localIp);
                                                        break;
                                                    }
                                                }
                                            }
                                            workerGroup.shutdownGracefully();
                                        }
                                    });
                                }
                            });
            ChannelFuture future = bootstrap.connect(host, port).sync();
            future.channel().closeFuture().sync();
            return response;
        } catch (Exception e) {
            System.out.println("Connect Accounting Server failed.");
            workerGroup.shutdownGracefully();
            return null;
        }
    }

    public static ArrayList<String> checkUpperNATAddress(String gatewayIP) {
        Process child;
        ArrayList<String> upperLayerAddress = new ArrayList<>();
        String command = "sudo nmap -sP " + gatewayIP + "/24";
        try {
            child = Runtime.getRuntime().exec(command);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                if (content.contains("Nmap scan report")) {
                    String address = content.split("for")[1].trim();
                    upperLayerAddress.add(address);
                }
            }
            return upperLayerAddress;
        } catch (IOException e) {
            return upperLayerAddress;
        }
    }

    public static void main(String[] args) {
        // System.out.println(checkNetworkInterface("163.18.49.26"));
        try {
            System.out.println(connectAccountingServerToCheckInterface("163.18.49.26", 8091));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
