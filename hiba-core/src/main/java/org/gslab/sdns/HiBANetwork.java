package org.gslab.sdns;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.gslab.App;
import org.gslab.SdnNetwork;
import org.gslab.util.AESUtil;
import org.gslab.util.CPUUsageRate;
import org.gslab.util.JsonFileUtil;
import org.gslab.util.ResourceCleanupManager;
import org.gslab.util.ReverseSSHTunnelManager;
import org.gslab.configuration.HibaNetworkConfig;
import org.gslab.configuration.MessageVerificationConfig;
import org.gslab.configuration.MobileNetworkConfig;
import org.gslab.configuration.SystemConfigurationLoader;
import org.gslab.configuration.TPMDeviceRegistryConfig;
import org.gslab.handlers.MessageTPMSignHandler;
import org.gslab.handlers.MessageTPMVerifyHandler;
import org.gslab.handlers.MessageTPMVerifyHandler.VerificationMode;
import org.gslab.handlers.NonceValidator;
import org.gslab.message_hiba.AccountingTestMessage;
import org.gslab.message_hiba.AccountingTestResponse;
import org.gslab.message_hiba.FileTransferFormat;
import org.gslab.message_hiba.GetBrokerList;
import org.gslab.message_hiba.GetBrokerListResponse;
import org.gslab.message_hiba.GetBrokerNameMessage;
import org.gslab.message_hiba.GetBrokerNameResponse;
import org.gslab.message_hiba.LoginMessage;
import org.gslab.message_hiba.Message;
import org.gslab.message_hiba.ResourceUpdateMessage;
import org.gslab.message_hiba.ScorePublishMessage;
import org.gslab.message_hiba.ScorePublishResponse;
import org.gslab.message_hiba.SyncMessage;
import org.gslab.message_hiba.TestMessage;
import org.gslab.message_hiba.TestResponse;
import org.gslab.message_hiba.TierCheckMessage;
import org.gslab.message_hiba.TierCheckResponse;
import org.gslab.message_hiba.connectionTestMessenge;
import org.gslab.message_hiba.connectionTestResponse;
import org.gslab.message_hiba.createReverseChannelMessage;
import org.gslab.message_hiba.createReverseChannelResponse;
import org.gslab.message_hiba.lowerBrokerScansMessage;
import org.gslab.message_hiba.lowerBrokerScansResponse;
import org.gslab.protocol.HiBAFrameDecoder;
import org.gslab.protocol.MessageCodec;
import org.gslab.sdnMobileControl.MobileNetworkController;
import org.gslab.sdnMobileControl.multicast.MobileMulticastReceiver;
import org.gslab.mService.multicast.MulticastController;
import org.gslab.mService.multicast.MulticastReceiver;
import org.gslab.mService.multicast.Traceroute;
import org.gslab.tpm.PublicKeyCacheService;
import org.gslab.tpm.blockchain.Web3BlockchainService;
import org.gslab.tpm.exception.TPMConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HiBANetwork implements SdnNetwork {

    private static final Logger logger = LoggerFactory.getLogger(HiBANetwork.class);
    private final ReverseSSHTunnelManager sshTunnelManager = new ReverseSSHTunnelManager();

    public static int HIBA_PORT;

    private static final NioEventLoopGroup clientWorker = new NioEventLoopGroup();
    private static final NioEventLoopGroup sendMessageWorker = new NioEventLoopGroup();
    private String localServerHost = "";
    private String localName = "";

    private String accountingServerHost = "";

    private Channel brokerChannel; // lower layer connection to upper layer broker channel.
    private Channel accountingServerChannel; // Accounting server heartBeat channel.

    private final Map<Channel, Map.Entry<String, String>> clients = new ConcurrentHashMap<>(); // Broker manages the
    // lower
    // layer nodes list.
    private final Map<Channel, Map.Entry<String, String>> acctLowerBroker = new ConcurrentHashMap<>(); // Accounting
    // server
    // heartbeat broker
    // list.
    private final ArrayList<String> brokerList = new ArrayList<>(); // Accounting server returns all broker lists used
    // by
    // lower brokers.

    private Initializer cInitializer = null; // client initializer => sdnNetwork(interface) 用
    private Initializer sInitializer = null; // server initializer

    private final AtomicBoolean isServer = new AtomicBoolean(false); // if node = accounting server, this value = true.
    private static Bootstrap clientBootstrap = null;

    private BrokerKeepAliver brokerKeepAliver = null;
    private ClientKeepAliver clientKeepAliver = null;
    private AcctServerKeepAliver acctServerKeepAliver = null;
    private AcctClientKeepAliver acctClientKeepAliver = null;

    // Multicast
    private MulticastBrokerSelector multicastBrokerSelector = null;
    public static final Map<String, Object> mulitcastMap = new HashMap<>();
    public static final Map<String, Object> brokerInformation = new HashMap<>();
    public static final Map<String, String> blackList = new HashMap<>();
    public static String MULTICAST_INTERFACE;
    public static String LOCAL_IP;
    public static String BROKER_IP;
    final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    public static final Map<String, Object> reverseChannelList = new HashMap<>();
    public static final ArrayList<String> usingPort = new ArrayList<>();

    private final Web3BlockchainService tpmDeviceRegistryService;

    HibaNetworkConfig hibaNetworkConfig;
    MobileNetworkConfig mobileNetworkConfig;

    public HiBANetwork(HibaNetworkConfig hibaNetworkConfig, MobileNetworkConfig mobileNetworkConfig)
            throws TPMConnectionException {
        this.hibaNetworkConfig = hibaNetworkConfig;
        this.mobileNetworkConfig = mobileNetworkConfig;

        localName = this.hibaNetworkConfig.getNodeName();
        accountingServerHost = this.hibaNetworkConfig.getAccountingServerIP();

        this.multicastBrokerSelector = this.new MulticastBrokerSelector();
        this.brokerKeepAliver = this.new BrokerKeepAliver();
        this.clientKeepAliver = this.new ClientKeepAliver();
        this.acctClientKeepAliver = this.new AcctClientKeepAliver();
        this.acctServerKeepAliver = this.new AcctServerKeepAliver();

        TPMDeviceRegistryConfig tpmDeviceRegistryConfig =
                new SystemConfigurationLoader("./env.json").loadTPMDeviceRegistryConfig();

        this.tpmDeviceRegistryService = new Web3BlockchainService.Builder()
                .rpcUrl(tpmDeviceRegistryConfig.getRpcUrl())
                .contractAddress(tpmDeviceRegistryConfig.getContractAddress())
                .privateKey(tpmDeviceRegistryConfig.getPrivateKey())
                .cacheService(new PublicKeyCacheService())
                .build();

        clientBootstrap = new Bootstrap()
                .group(clientWorker)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .handler(
                        new ChannelInitializer<>() { // 創建一個通道的初始化對象
                            @Override
                            protected void initChannel(Channel channel) {
                                channel.pipeline().addLast(new HiBAFrameDecoder());
                                channel.pipeline().addLast(new MessageCodec());

                                // TPM 驗證 Handler（驗證接收到的 SignedMessage）
                                channel.pipeline()
                                        .addLast(
                                                "tpmVerify",
                                                new MessageTPMVerifyHandler(
                                                        VerificationMode.BLOCKCHAIN_PREFERRED,
                                                        tpmDeviceRegistryService,
                                                        MessageVerificationConfig.defaultConfig(),
                                                        NonceValidator.getInstance(),
                                                        HiBAInitial.tpmSignatureVerification));
                                // TPM 簽章 Handler（將 Message 包裝成 SignedMessage）
                                channel.pipeline()
                                        .addLast(
                                                "tpmSign",
                                                new MessageTPMSignHandler(
                                                        HiBAInitial.tpmService, HiBAInitial.tpmSignatureVerification));

                                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelActive(ChannelHandlerContext ctx)
                                            throws Exception { // 通道就緒時執行 =>
                                        // 連線至TopNetwork後,
                                        // 執行以下
                                        localServerHost = LOCAL_IP;
                                        ctx.channel()
                                                .writeAndFlush(new GetBrokerNameMessage())
                                                .await(); // 詢問broker名子, 並等待
                                        ctx.channel()
                                                .writeAndFlush(new LoginMessage(
                                                        localName, localServerHost, HIBA_PORT)); // 登入broker
                                        ctx.channel().writeAndFlush(new FileTransferFormat());
                                        super.channelActive(ctx);
                                    }
                                });

                                channel.pipeline().addLast(new SimpleChannelInboundHandler<GetBrokerNameResponse>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, GetBrokerNameResponse msg) {
                                        brokerKeepAliver.setBrokerName(msg.getName());
                                    }
                                });

                                channel.pipeline().addLast(brokerKeepAliver);
                                channel.pipeline().addLast(acctServerKeepAliver);

                                channel.pipeline().addLast(new ChannelDuplexHandler() {
                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {}
                                });

                                if (cInitializer != null) {
                                    cInitializer.initial(channel);
                                }
                            }
                        });

        isServer.set(false);
    }

    public static Bootstrap useClientBootstrap() {
        return clientBootstrap;
    }

    public HiBANetwork() throws TPMConnectionException {
        localName = "server";
        this.acctClientKeepAliver = this.new AcctClientKeepAliver();
        isServer.set(true);

        TPMDeviceRegistryConfig tpmDeviceRegistryConfig =
                new SystemConfigurationLoader("./env.json").loadTPMDeviceRegistryConfig();

        this.tpmDeviceRegistryService = new Web3BlockchainService.Builder()
                .rpcUrl(tpmDeviceRegistryConfig.getRpcUrl())
                .contractAddress(tpmDeviceRegistryConfig.getContractAddress())
                .privateKey(tpmDeviceRegistryConfig.getPrivateKey())
                .cacheService(new PublicKeyCacheService())
                .build();
    }

    public void start() throws Exception {

        HIBA_PORT = HiBAInitial.HIBA_PORT;
        setChannelPubKey("clear");

        startServer(new InetSocketAddress("0.0.0.0", HIBA_PORT)); // main Reactor
        if (isServer.get()) {
            new Thread(acctClientKeepAliver).start(); // accounting server receives and management the lower layer
            // broker check.
        } else {
            if (HiBAInitial.MobileControllerState || !HiBAInitial.hibaNetworkInterfaceInfo.isEmpty()) {
                if (HiBAInitial.MobileControllerState) {
                    File jsonList = new File("./RSU.json");
                    Map<String, Object> RSUWiFiList = new HashMap<>();
                    RSUWiFiList = JsonFileUtil.ReadFile(jsonList).toMap();
                    new Thread(new MobileNetworkController(RSUWiFiList)).start();
                    if (HiBAInitial.startOBU) {
                        new Thread(new MobileMulticastReceiver()).start();
                    }
                    MULTICAST_INTERFACE = HiBAInitial.wifiInterface;
                    LOCAL_IP = "None";
                } else {
                    MULTICAST_INTERFACE = HiBAInitial.hibaNetworkInterfaceInfo.get("interface");
                    LOCAL_IP = HiBAInitial.hibaNetworkInterfaceInfo.get("address");
                }
                logger.atInfo()
                        .addKeyValue("multicastInterface", MULTICAST_INTERFACE)
                        .log("MULTICAST_INTERFACE: {}", MULTICAST_INTERFACE);
                new Thread(new MulticastController()).start(); // Multicast controller. Multicast main controller.
                new Thread(new MulticastReceiver()).start(); // Multicast receiver.
                new Thread(multicastBrokerSelector).start(); // Multicast broker selector.
                new Thread(brokerKeepAliver).start(); // lower layer to upper layer broker KeepAlive.
                Thread.sleep(500);
                new Thread(clientKeepAliver).start(); // The broker receives and management the lower layer node check.
                Thread.sleep(300);
                new Thread(acctServerKeepAliver).start(); // The broker to accounting server KeepAlive.
                new Thread(new HiBAFileTransferServer()).start(); // HiBA data plane file transfer server.
            } else {
                logger.atError().log("Can't connect to Accounting server.");
                logger.atError().log("Exiting due to inability to connect to Accounting server.");
                System.exit(0);
            }
        }
    }

    private void delay(int time) {
        try {
            TimeUnit.SECONDS.sleep(time);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void startServer(InetSocketAddress address) { // MainReactor
        NioEventLoopGroup computeBossWorker = new NioEventLoopGroup();
        new ServerBootstrap()
                .group(computeBossWorker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel channel) { // Netty服務初始化
                        // 新增編解碼
                        channel.pipeline().addLast(new HiBAFrameDecoder());
                        channel.pipeline().addLast(new MessageCodec());
                        // TPM 驗證 Handler（驗證接收到的 SignedMessage）
                        channel.pipeline()
                                .addLast(
                                        "tpmVerify",
                                        new MessageTPMVerifyHandler(
                                                VerificationMode.BLOCKCHAIN_PREFERRED,
                                                tpmDeviceRegistryService,
                                                MessageVerificationConfig.defaultConfig(),
                                                NonceValidator.getInstance(),
                                                HiBAInitial.tpmSignatureVerification));
                        // TPM 簽章 Handler（將 Message 包裝成 SignedMessage）
                        channel.pipeline()
                                .addLast(
                                        "tpmSign",
                                        new MessageTPMSignHandler(
                                                HiBAInitial.tpmService, HiBAInitial.tpmSignatureVerification));
                        channel.pipeline()
                                .addLast(
                                        new SimpleChannelInboundHandler<LoginMessage>() { // 處理連線進來的Node
                                            @Override
                                            protected void channelRead0(
                                                    ChannelHandlerContext ctx, LoginMessage msg) { // 新節點加入,
                                                // 傳送其他節點資訊給他,
                                                // 讓他判斷是否同層
                                                logger.atInfo()
                                                        .addKeyValue("nodeName", msg.getName())
                                                        .log("{} join group.", msg.getName());
                                                if ("server".equals(localName)) {
                                                    acctLowerBroker.put(
                                                            channel, Map.entry(msg.getName(), msg.getServerIp()));
                                                } else {
                                                    clients.put(channel, Map.entry(msg.getName(), msg.getServerIp()));
                                                    logger.atDebug().log("Current Clients: {}", clients);
                                                }
                                            }
                                        });

                        channel.pipeline().addLast(new SimpleChannelInboundHandler<connectionTestMessenge>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, connectionTestMessenge msg) {
                                ctx.channel().writeAndFlush(new connectionTestResponse());
                            }
                        });

                        channel.pipeline().addLast(new SimpleChannelInboundHandler<lowerBrokerScansMessage>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, lowerBrokerScansMessage msg) {
                                String responseMessage = "";
                                logger.atInfo()
                                        .addKeyValue("multicastMode", MulticastController.getMode())
                                        .addKeyValue("brokerIP", BROKER_IP)
                                        .log("{}  BROKER_IP: {}", MulticastController.getMode(), BROKER_IP);

                                if (MulticastController.getMode().equals("isBroker")) {
                                    logger.atInfo()
                                            .addKeyValue("localIP", LOCAL_IP)
                                            .log(
                                                    "Received lower tier Broker scanning message, reply my address: {}",
                                                    LOCAL_IP);
                                    responseMessage = LOCAL_IP;
                                } else if (MulticastController.getMode().equals("isClient")
                                        || MulticastController.getMode().equals("waiting")) {
                                    logger.atInfo()
                                            .addKeyValue("brokerIP", BROKER_IP)
                                            .log(
                                                    "Received lower tier Broker scanning message, reply to my Broker address: {}",
                                                    BROKER_IP);
                                    responseMessage = BROKER_IP;
                                } else {
                                    responseMessage = "None";
                                }
                                ctx.channel().writeAndFlush(new lowerBrokerScansResponse(responseMessage));
                            }
                        });

                        channel.pipeline().addLast(new SimpleChannelInboundHandler<GetBrokerNameMessage>() {
                            ////// 不太懂這邊為什麼回傳的broker是server自己??
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, GetBrokerNameMessage msg) {
                                ctx.channel().writeAndFlush(new GetBrokerNameResponse(localName));
                            }
                        });

                        channel.pipeline().addLast(new SimpleChannelInboundHandler<GetBrokerList>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, GetBrokerList msg) {
                                ctx.channel().writeAndFlush(new GetBrokerListResponse(brokerList));
                            }
                        });

                        channel.pipeline().addLast(new SimpleChannelInboundHandler<createReverseChannelMessage>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, createReverseChannelMessage msg) {
                                String lowerNodeDeviceName = msg.getDeviceName();
                                String lowerLayerAddress = msg.getLowerLayerAddress();
                                String lowerLayerPubKey = msg.getPubKey();
                                String nodeName = msg.getLowerLayerNodeName();
                                String connectionTime = msg.getConnectionTime();
                                ArrayList<String> nodeUsedPort = msg.getLowerNodeUsedPort();
                                String plaintext = AESUtil.decryptAES(lowerLayerPubKey, connectionTime);
                                logger.atInfo()
                                        .addKeyValue("nodeName", nodeName)
                                        .log(
                                                "Receive the lower tier to create a reverse channel information, node: {}",
                                                nodeName);
                                boolean setChannelState = setChannelPubKey(plaintext);
                                if (setChannelState) {
                                    int port = 0;
                                    // ArrayList<String> usingPort = new ArrayList<>();
                                    // for (String key : reverseChannelList.keySet()) {
                                    // Map<String, Object> ChannelInfo = (Map<String, Object>)
                                    // reverseChannelList.get(key);
                                    // usingPort.add((String) ChannelInfo.get("port"));
                                    // }
                                    // System.out.println("Local使用中的Port: " + usingPort + " / LowerNode使用中的Port" +
                                    // nodeUsedPort);
                                    for (port = 9000; port <= 9399; port++) {
                                        // if (!reverseChannelList.containsValue(port)) {
                                        if (!usingPort.contains(String.valueOf(port))
                                                && !nodeUsedPort.contains(String.valueOf(port))) { //
                                            logger.atInfo()
                                                    .addKeyValue("reverseChannelList", reverseChannelList)
                                                    .log("Reverse channel list: {}", reverseChannelList);
                                            Map<String, String> info = new HashMap<>();
                                            info.put("deviceName", lowerNodeDeviceName);
                                            info.put("port", String.valueOf(port));
                                            info.put("ip", lowerLayerAddress);
                                            reverseChannelList.put(nodeName, info);
                                            logger.atInfo()
                                                    .addKeyValue("nodeName", nodeName)
                                                    .addKeyValue("port", port)
                                                    .log("The {} is using port {}", nodeName, port);
                                            /*
                                             * 該部分usingPort先這樣寫，後續仍需要修改。
                                             * 原因: 每次有下層Broker連線時，都會將其使用的Port add到List中，斷線不會清除。
                                             * 之後可以改成Map格式 -> nodeName: usedPort
                                             * 這樣下層節點離線時，可以從Leave判斷那邊去刪掉以使用的Port。
                                             */
                                            usingPort.add(String.valueOf(port));
                                            break;
                                        }
                                    }
                                    String resConnectionTime = Long.toString(System.currentTimeMillis());
                                    String ciphertext = AESUtil.encryptAES(HiBAInitial.pubKey, resConnectionTime);
                                    ctx.channel()
                                            .writeAndFlush(new createReverseChannelResponse(
                                                    ciphertext,
                                                    HiBAInitial.sysName,
                                                    LOCAL_IP,
                                                    port,
                                                    resConnectionTime));
                                } else {
                                    logger.atInfo().log("Set channel PubKey error.");
                                }
                            }
                        });

                        channel.pipeline()
                                .addLast(
                                        new SimpleChannelInboundHandler<TierCheckMessage>() { // Tier check
                                            // response
                                            @Override
                                            protected void channelRead0(
                                                    ChannelHandlerContext ctx, TierCheckMessage msg) {
                                                logger.atInfo().log("Receive TierCheck Message");
                                                AtomicBoolean checkSuccess = new AtomicBoolean(true);
                                                if (!msg.isChecked()) {
                                                    String host = ((InetSocketAddress) ctx.channel()
                                                                    .remoteAddress())
                                                            .getAddress()
                                                            .getHostAddress();
                                                    try {
                                                        sendMessage(host, new TierCheckMessage(true));
                                                    } catch (Exception e) {
                                                        checkSuccess.set(false);
                                                    }
                                                }
                                                logger.atInfo().log("Send TierCheck Response");
                                                ctx.channel().writeAndFlush(new TierCheckResponse(checkSuccess.get()));
                                            }
                                        });
                        if (!isServer.get()) {
                            channel.pipeline()
                                    .addLast(multicastBrokerSelector); // channel.pipeline().addLast(brokerSelector);
                        }

                        if ("server".equals(localName)) {
                            channel.pipeline().addLast(acctClientKeepAliver);
                        } else {
                            channel.pipeline().addLast(clientKeepAliver);
                        }

                        if (sInitializer != null) {
                            sInitializer.initial(channel);
                        }

                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                Channel clientChannel = ctx.channel();

                                if ("server".equals(localName)) {
                                    if (acctLowerBroker.containsKey(clientChannel)) {
                                        String nodeName = acctLowerBroker
                                                .get(clientChannel)
                                                .getKey();
                                        logger.atInfo().log("{} Leave Group.", nodeName);
                                        acctClientKeepAliver.counter.remove(clientChannel);
                                        acctLowerBroker.remove(clientChannel);
                                    }
                                } else {
                                    if (clients.containsKey(clientChannel)) {
                                        String nodeName =
                                                clients.get(clientChannel).getKey();
                                        logger.atInfo()
                                                .addKeyValue("nodeName", nodeName)
                                                .log("{} Leave Group.", nodeName);
                                        // LOGGER.info("now All Resource : " + App.getResource());
                                        clientKeepAliver.counter.remove(clientChannel);
                                        reverseChannelList.remove(nodeName);
                                        if (!isServer.get()) {
                                            multicastBrokerSelector.sameTierNodes.remove(nodeName);
                                        }

                                        clients.remove(clientChannel);
                                        if (clients.isEmpty() && !isServer.get()) {
                                            logger.atInfo().log("Local is not Broker now.");
                                            // multicastBrokerSelector.setLocalBroker(false);
                                            multicastBrokerSelector.sameTierNodes.clear();
                                            MulticastController.startMulticastTime = 0;
                                            MulticastController.setMode("init");
                                        }
                                        logger.atInfo()
                                                .addKeyValue("clients", clients)
                                                .log("Alive Clients : {}", clients);
                                    }
                                }

                                super.channelInactive(ctx);
                            }
                        });

                        channel.pipeline().addLast(new ChannelDuplexHandler() {
                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {}
                        });
                    }
                })
                .bind(new InetSocketAddress("0.0.0.0", HIBA_PORT));
    }

    public ChannelFuture connectToBroker(Bootstrap bootstrap, String brokerHost) throws InterruptedException {
        ChannelFuture connectFuture = bootstrap.connect(new InetSocketAddress(brokerHost, HIBA_PORT));
        connectFuture.await();
        return connectFuture;
    }

    public Object sendMessage(String host, Message message) throws Exception {
        AtomicReference<Object> response = new AtomicReference<>();
        ChannelFuture channelFuture = new Bootstrap()
                .group(sendMessageWorker)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new HiBAFrameDecoder());
                        channel.pipeline().addLast(new MessageCodec());
                        // TPM 驗證 Handler（驗證接收到的 SignedMessage）
                        channel.pipeline()
                                .addLast(
                                        "tpmVerify",
                                        new MessageTPMVerifyHandler(
                                                VerificationMode.BLOCKCHAIN_PREFERRED,
                                                tpmDeviceRegistryService,
                                                MessageVerificationConfig.defaultConfig(),
                                                NonceValidator.getInstance(),
                                                HiBAInitial.tpmSignatureVerification));
                        // TPM 簽章 Handler（將 Message 包裝成 SignedMessage）
                        channel.pipeline()
                                .addLast(
                                        "tpmSign",
                                        new MessageTPMSignHandler(
                                                HiBAInitial.tpmService, HiBAInitial.tpmSignatureVerification));
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                ctx.channel().writeAndFlush(message);
                                super.channelActive(ctx);
                            }

                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                response.set(msg);
                                super.channelRead(ctx, msg);
                            }

                            @Override
                            public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
                                ctx.channel().close();
                                super.channelReadComplete(ctx);
                            }
                        });
                    }
                })
                .connect(new InetSocketAddress(host, HIBA_PORT));

        channelFuture.await();

        if (!channelFuture.isSuccess()) {
            throw new Exception(channelFuture.cause());
        }

        channelFuture.channel().closeFuture().await();

        return response.get();
    }

    @Override
    public Map<Channel, Entry<String, String>> getClients() {
        return clients;
    }

    @Override
    public Entry<String, String> getLocal() {
        return Map.entry(localName, localServerHost);
    }

    @Override
    public Channel getBrokerChannel() {
        return brokerChannel;
    }

    @Override
    public void clientInitialHandler(Initializer initializer) {
        this.cInitializer = initializer;
    }

    @Override
    public void serverInitialHandler(Initializer initializer) {
        this.sInitializer = initializer;
    }

    public boolean setChannelPubKey(String pubKey) {
        try {
            if ("clear".equals(pubKey)) {
                FileWriter authFile = new FileWriter("./auth_ch");
                authFile.write("");
                authFile.flush();
                authFile.close();
            } else {
                FileWriter authFile = new FileWriter("./auth_ch", true);
                authFile.write(pubKey + "\n");
                authFile.flush();
                authFile.close();
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void sendShortConnectMessage(String hostName, Message message, Initializer initializer) throws Exception {
        sendConnectMessage(hostName, message, initializer, true); // 預設為短連線
    } // 新增支援長連線的方法

    @Override
    public void sendConnectMessage(String hostName, Message message, Initializer initializer, boolean closeAfterSend)
            throws Exception {
        NioEventLoopGroup bossWorker = new NioEventLoopGroup();
        ChannelFuture channelFuture = new Bootstrap()
                .group(bossWorker)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new HiBAFrameDecoder());
                        channel.pipeline().addLast(new MessageCodec()); // 先設置用戶定義的 handlers，再設置發送邏輯
                        // TPM 驗證 Handler（驗證接收到的 SignedMessage）
                        channel.pipeline()
                                .addLast(
                                        "tpmVerify",
                                        new MessageTPMVerifyHandler(
                                                VerificationMode.BLOCKCHAIN_PREFERRED,
                                                tpmDeviceRegistryService,
                                                MessageVerificationConfig.defaultConfig(),
                                                NonceValidator.getInstance(),
                                                HiBAInitial.tpmSignatureVerification));
                        // TPM 簽章 Handler（將 Message 包裝成 SignedMessage）
                        channel.pipeline()
                                .addLast(
                                        "tpmSign",
                                        new MessageTPMSignHandler(
                                                HiBAInitial.tpmService, HiBAInitial.tpmSignatureVerification));
                        if (initializer != null) {
                            initializer.initial(channel);
                        }

                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                // 在連線建立且所有 handlers 設置完成後才發送訊息
                                ctx.channel().writeAndFlush(message);
                                super.channelActive(ctx);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                // 當連線關閉時，釋放 EventLoopGroup
                                if (!closeAfterSend) {
                                    bossWorker.shutdownGracefully();
                                }
                                super.channelInactive(ctx);
                            }
                        });
                    }
                })
                .connect(new InetSocketAddress(hostName, HIBA_PORT));

        channelFuture.await();

        if (!channelFuture.isSuccess()) {
            throw new Exception(channelFuture.cause());
        }

        // 等待 channel 真正變為 active 狀態，確保 pipeline 完全設置
        Channel channel = channelFuture.channel();

        // 等待 channel active，最多等待 5 秒
        long startTime = System.currentTimeMillis();
        while (!channel.isActive() && (System.currentTimeMillis() - startTime) < 5000) {
            Thread.sleep(10);
        }

        if (!channel.isActive()) {
            throw new Exception("Channel failed to become active within 5 seconds");
        }

        // 再給一點時間讓 pipeline 處理完成
        Thread.sleep(50);

        if (closeAfterSend) {
            // 短連線模式：等待發送完成後關閉
            // 注意：訊息已經在 channelActive 中發送了，這裡只需要等待關閉
            try {
                channelFuture.channel().closeFuture().await();
            } finally {
                bossWorker.shutdownGracefully();
            }
        }
        // 長連線模式：不在這裡等待，讓連線保持開啟
        // EventLoopGroup 的釋放交給 channelInactive 處理
        // 或者由接收到 RequestFinishMessage 的 handler 負責關閉 channel
    }

    @Sharable
    private class MulticastBrokerSelector extends ChannelInboundHandlerAdapter implements Runnable {

        private final Map<String, Float> scoreMap = new ConcurrentHashMap<>();
        private final Map<String, Float> sameTierNodesCheck = new ConcurrentHashMap<>();
        // private boolean localBroker = false;
        private final boolean tcpScanning = HiBAInitial.offlineBrokerScan;
        private float score = 0;
        private int sameTierNodesCheckTimeout = 0;
        private int isClientAndNoBrokerBroadcastTimeout = 0;
        private int RecheckAcctBrokerListTimeIntervals = 0;
        private int msgCount = 0;
        private int msgCount1 = 0;
        private int clientIdleCount = 0;
        public final Map<String, String> sameTierNodes = new ConcurrentHashMap<>();

        public MulticastBrokerSelector() {
            File scoreFile = new File("./device/score.json");
            Map<String, Object> map = JsonFileUtil.ReadFile(scoreFile).toMap();
            logger.atInfo().addKeyValue("score", map.get("score")).log("CPU score from file: {}", map.get("score"));
            score = Float.parseFloat((String) map.get("score"));
            // score = ThreadLocalRandom.current().nextLong(100);
        }

        public float getScore() {
            return score;
        }

        // public void setLocalBroker(boolean localBroker) {
        // this.localBroker = localBroker;
        // }

        @Override
        public void run() {
            logger.atInfo().log("Broker Selector Start");

            AtomicBoolean isFindBroker = new AtomicBoolean(false);
            int blackListClearCount = 0;
            boolean resetEvent = true;

            /*
             * 清除反向通道背景程序 與 清除反向ssh PubKey。
             * 該部分這邊先這樣寫是因為，若節點設定為固定broker模式，不會執行init，進而導致不會清除這些設定。
             * 之後要把這部分改到HiBAInitial內。
             */
            clearReverseChannel();
            setChannelPubKey("clear");

            while (true) {
                delay(1);

                String currentMode = MulticastController.getMode();

                if (currentMode.equals("init")) {
                    isFindBroker.set(false);
                }

                if (isFindBroker.get()) {
                    continue;
                }

                switch (MulticastController.getMode()) {
                    case "init":
                        blackListClearCount += 1;
                        if (blackListClearCount >= 10) {
                            blackListClearCount = 0;
                            blackList.clear();
                        }
                        if (resetEvent) {
                            clearReverseChannel();
                            setChannelPubKey("clear");
                            resetEvent = false;
                        }
                        scoreMap.clear();
                        break;

                    case "connection":
                        for (Map.Entry<String, Object> pair : mulitcastMap.entrySet()) {
                            sameTierNodes.put(pair.getKey(), (String) pair.getValue());
                        }
                        scoreMap.put(localName, getScore());
                        logger.atInfo().log("Start Broker Election.");
                        logger.atInfo().addKeyValue("localScore", score).log("Local Score : {}", score);

                        sameTierNodes.forEach((name, host) -> { // TCP send local score.
                            try {
                                ScorePublishResponse response = (ScorePublishResponse) sendMessage(
                                        host, new ScorePublishMessage(name, score)); // API -> ScorePublishMessage:
                                // Broker選舉時，向其他節點發出自身分數的訊息
                                if (response != null) {
                                    scoreMap.put(name, response.getScore());
                                    sameTierNodesCheck.put(name, response.getScore()); // return score received.
                                }
                            } catch (Exception e) {
                                logger.atInfo().log("Can't Send Score Message To {}", name);
                            }
                        });
                        logger.atInfo().log("Score list : {}", scoreMap);
                        if (!(sameTierNodes.keySet()).equals(sameTierNodesCheck.keySet())) { // Check all node scores
                            // received.
                            sameTierNodesCheckTimeout += 1;
                            if (sameTierNodesCheckTimeout >= 3) { // timeout sec = 3s
                                sameTierNodes.clear();
                                sameTierNodesCheck.clear();
                                mulitcastMap.clear();
                                MulticastController.startMulticastTime = 0;
                                MulticastController.setMode("init");
                                logger.atInfo().log("SameTier nodes check timeout.");
                            }
                            break;
                        }
                        String highestScoreNodeName = compareMap(scoreMap);
                        MulticastController.setScoreMap(scoreMap);
                        MulticastController.setHighestScoreNodeName(highestScoreNodeName);
                        logger.atInfo().log("Highest Score Node Name : {}", highestScoreNodeName);
                        break;

                    case "highestScoreBroadcast":
                        break;

                    case "isBroker":
                        ArrayList<String> acctResponseBrokerList = new ArrayList<>(); // accounting server response all
                        // broker host list.
                        ArrayList<String> mightUpperBroker = new ArrayList<>(); // Filter acctResponseBrokerList by
                        // gateway.
                        // setLocalBroker(true);
                        /*
                         * 1. Get the broker list of accounting server
                         * 2. Set accounting server heartbeat channel.
                         * ** If localNode is a broker, parameter: 'brokerInformation' will be set to
                         * 'Accounting server'.
                         */
                        if (accountingServerChannel == null) {
                            brokerInformation.put("ip", accountingServerHost);
                            try {
                                GetBrokerListResponse response =
                                        (GetBrokerListResponse) sendMessage(accountingServerHost, new GetBrokerList());
                                if (response != null) {
                                    acctResponseBrokerList = response.getBrokerList();
                                    logger.atInfo().log("Received broker list: {}", acctResponseBrokerList);
                                    ChannelFuture connectFuture =
                                            connectToBroker(clientBootstrap, accountingServerHost);
                                    if (accountingServerChannel != null) {
                                        accountingServerChannel.close();
                                    }
                                    accountingServerChannel = connectFuture.channel();
                                } else {
                                    msgCount += 1;
                                    if (msgCount >= 5) {
                                        logger.atInfo().log(
                                                "Can't connect to Accounting Server {}", accountingServerHost);
                                        msgCount = 0;
                                    }
                                    brokerInformation.clear();
                                }
                            } catch (Exception e) {
                                msgCount += 1;
                                if (msgCount >= 5) {
                                    logger.atInfo().log("Can't connect to Accounting Server {}", accountingServerHost);
                                    msgCount = 0;
                                }
                                brokerInformation.clear();
                            }
                        }
                        if (accountingServerChannel == null & brokerChannel == null) {
                            if (tcpScanning) {
                                logger.atInfo().log(
                                        "Can't connect to Accounting Server {}, Start the upper layer scanning program.",
                                        accountingServerHost);
                                String upperGateway = Traceroute.doTraceroute();
                                ArrayList<String> upperLayerAddress = Traceroute.checkUpperNATAddress(upperGateway);
                                logger.atInfo().log("upperGateway: {} live host: {}", upperGateway, upperLayerAddress);
                                if (!upperLayerAddress.isEmpty()) {
                                    // watchdogTimer watchdog = new watchdogTimer();
                                    for (String targetIP : upperLayerAddress) {
                                        try {
                                            lowerBrokerScansResponse upperResponse = (lowerBrokerScansResponse)
                                                    sendMessage(targetIP, new lowerBrokerScansMessage());
                                            if (upperResponse != null) {
                                                logger.atInfo().log(
                                                        "Find the upper layer broker {}",
                                                        upperResponse.getBrokerAddress());
                                                // 這部份剛測試完, 與下面正常連線到ACCT的程式碼一樣, 之後整理
                                                String mightBroker = upperResponse.getBrokerAddress();
                                                ChannelFuture connectFuture =
                                                        connectToBroker(clientBootstrap, mightBroker);
                                                String connectionTime = Long.toString(System.currentTimeMillis());
                                                String ciphertext =
                                                        AESUtil.encryptAES(HiBAInitial.pubKey, connectionTime);
                                                // watchdog.start();
                                                createReverseChannelResponse response =
                                                        (createReverseChannelResponse) sendMessage(
                                                                mightBroker,
                                                                new createReverseChannelMessage(
                                                                        ciphertext,
                                                                        HiBAInitial.sysName,
                                                                        LOCAL_IP,
                                                                        HiBAInitial.nodeName,
                                                                        connectionTime,
                                                                        usingPort));
                                                String brokerDeviceName = response.getUpperLayerDeviceName();
                                                String brokerIP = response.getUpperLayerAddress();
                                                String pubKey = response.getPubKey();
                                                String reConnectionTime = response.getConnectionTime();
                                                Integer port = response.getPort();
                                                String plaintext = AESUtil.decryptAES(pubKey, reConnectionTime);
                                                if (setChannelPubKey(plaintext)) {
                                                    if (createReverseChannel(brokerDeviceName, brokerIP, port)) {
                                                        logger.atInfo().log(
                                                                "Reverse channel establishment. -> {} {}:{}",
                                                                brokerDeviceName,
                                                                brokerIP,
                                                                port);
                                                        usingPort.add(String.valueOf(port));
                                                    }
                                                }
                                                if (connectFuture.isSuccess() && response != null) {
                                                    // watchdog.stopRunning();
                                                    logger.atInfo().log(
                                                            "Connect to Upper Broker Success: {}", mightBroker);
                                                    MulticastController.endMulticastTime = System.currentTimeMillis();
                                                    logger.atInfo().log(
                                                            "> 連線成功時間: {}",
                                                            dateFormat.format(MulticastController.endMulticastTime));
                                                    long timeConsuming = MulticastController.endMulticastTime
                                                            - MulticastController.startMulticastTime;
                                                    logger.atInfo().log("> 連線總計耗時: {} sec.", timeConsuming / 1000.00);

                                                    if (brokerChannel != null) {
                                                        brokerChannel.close();
                                                    }
                                                    brokerChannel = connectFuture.channel();
                                                    resetEvent = true;
                                                    break;
                                                }
                                                break;
                                            }
                                        } catch (Exception e) {
                                            // e.printStackTrace();
                                            // LOGGER.info("Can't Connect to Upper layer Broker, reverse channel message
                                            // error. ");
                                            logger.atInfo().log("Can't Connect to Upper layer node: {}", targetIP);
                                            // watchdog.stopRunning();
                                        }
                                    }
                                }
                                // tcpScanning = false;
                            } else {
                                continue;
                            }
                        } else if (brokerChannel != null) {
                            continue;
                        }
                        /*
                         * Try to associate the upper layer broker from accounting server responded
                         * broker list.
                         * 1. Traceroute to get upper layer gateway address.
                         * 2. Filter broker list with upper layer gateway from accounting server
                         * responded broker list.
                         * 3. Associate upper broker.
                         *
                         * If upper layer broker is not found.
                         * 1. Periodically get the broker list from the accounting server.
                         */
                        if (acctResponseBrokerList.isEmpty() && RecheckAcctBrokerListTimeIntervals >= 5) {
                            try {
                                RecheckAcctBrokerListTimeIntervals = 0;
                                GetBrokerListResponse response =
                                        (GetBrokerListResponse) sendMessage(accountingServerHost, new GetBrokerList());
                                if (response != null) {
                                    acctResponseBrokerList = response.getBrokerList();
                                    logger.atInfo().log("Received broker list: {}", acctResponseBrokerList);
                                    acctResponseBrokerList.remove(LOCAL_IP);
                                }
                            } catch (Exception e) {
                            }
                        } else {
                            RecheckAcctBrokerListTimeIntervals += 1; // set timeout. For periodic judgment.
                            break;
                        }
                        if (!acctResponseBrokerList.isEmpty()) { // Traceroute -> Filter list -> Get might brokers
                            String upperGateway = Traceroute.doTraceroute();
                            // System.out.println("upperGateway: " + upperGateway);
                            if (upperGateway.contains(".")) {
                                String gatewayMask = upperGateway.substring(0, upperGateway.lastIndexOf(".")); // ex:
                                // 192.168.0.X
                                // ->
                                // 192.168.0,
                                // (mask
                                // ipv4
                                // host
                                // portion)
                                for (String address : acctResponseBrokerList) {
                                    String addressMask = address.substring(0, address.lastIndexOf("."));
                                    if (gatewayMask.equals(addressMask)) {
                                        mightUpperBroker.add(address);
                                    }
                                }
                            }

                            // Associate to upper layer broker.
                            // watchdogTimer watchdog = new watchdogTimer();
                            try {
                                for (String mightBroker : mightUpperBroker) {
                                    ChannelFuture connectFuture = connectToBroker(clientBootstrap, mightBroker);
                                    String connectionTime = Long.toString(System.currentTimeMillis());
                                    String ciphertext = AESUtil.encryptAES(HiBAInitial.pubKey, connectionTime);
                                    logger.atInfo().log(
                                            "Associate the upper layer node to confirm the upper layer broker, current node: {}",
                                            mightBroker);
                                    // watchdog.start();
                                    createReverseChannelResponse response = (createReverseChannelResponse) sendMessage(
                                            mightBroker,
                                            new createReverseChannelMessage(
                                                    ciphertext,
                                                    HiBAInitial.sysName,
                                                    LOCAL_IP,
                                                    HiBAInitial.nodeName,
                                                    connectionTime,
                                                    usingPort));
                                    String brokerDeviceName = response.getUpperLayerDeviceName();
                                    String brokerIP = response.getUpperLayerAddress();
                                    String pubKey = response.getPubKey();
                                    String reConnectionTime = response.getConnectionTime();
                                    Integer port = response.getPort();
                                    String plaintext = AESUtil.decryptAES(pubKey, reConnectionTime);
                                    if (setChannelPubKey(plaintext)) {
                                        logger.atDebug().log("Set channel PubKey success.");
                                        logger.atDebug().log(
                                                "Create reverse channel for {} at {}:{}",
                                                brokerDeviceName,
                                                brokerIP,
                                                port);
                                        if (createReverseChannel(brokerDeviceName, brokerIP, port)) {
                                            logger.atInfo().log(
                                                    "Reverse channel establishment. -> {} {}:{}",
                                                    brokerDeviceName,
                                                    brokerIP,
                                                    port);
                                            usingPort.add(String.valueOf(port));
                                        }
                                    }
                                    if (connectFuture.isSuccess() && response != null) {
                                        // watchdog.stopRunning();
                                        logger.atInfo().log("Connect to Upper Broker Success: {}", mightBroker);
                                        MulticastController.endMulticastTime = System.currentTimeMillis();
                                        logger.atInfo().log(
                                                "> 連線成功時間: {}",
                                                dateFormat.format(MulticastController.endMulticastTime));
                                        long timeConsuming = MulticastController.endMulticastTime
                                                - MulticastController.startMulticastTime;
                                        logger.atInfo().log("> 連線總計耗時: {} sec.", timeConsuming / 1000.00);

                                        if (brokerChannel != null) {
                                            brokerChannel.close();
                                        }
                                        brokerChannel = connectFuture.channel();
                                        resetEvent = true;
                                        break;
                                    }
                                }
                                if (brokerChannel == null) {
                                    msgCount1 += 1;
                                    if (msgCount1 >= 10) {
                                        logger.atInfo().log("Could not find the Upper layer Broker.");
                                        msgCount1 = 0;
                                    }
                                }
                            } catch (Exception e) {
                                // e.printStackTrace();
                                // LOGGER.info("Can't Connect to Upper layer Broker.1");
                                logger.atInfo().log(
                                        "Can't Connect to Upper layer Broker, reverse channel message error.");
                                // watchdog.stopRunning();
                            }
                            // } catch (Exception e) {
                            // e.printStackTrace();
                            // LOGGER.info("Can't Connect to Upper layer Broker, reverse channel message
                            // error.");
                            // }
                        } else {
                            logger.atInfo().log("Upper layer broker is not found.");
                        }
                        break;

                    case "isClient":
                        if (brokerInformation.isEmpty()) {
                            /*
                             * 該部份先寫成跟MulticastController的timeout一樣
                             * 因為長時間測試時，有發生疑似該部份卡住，使系統無法正常運行
                             */
                            isClientAndNoBrokerBroadcastTimeout += 1;
                            if (isClientAndNoBrokerBroadcastTimeout >= 10) {
                                isClientAndNoBrokerBroadcastTimeout = 0;
                                logger.atInfo().log("Waiting for broker broadcast timeout.");
                                MulticastController.setMode("init");
                            }
                            break;
                        }
                        String brokerHost = (String) brokerInformation.get("ip");
                        logger.atInfo().log("Broker Election Result is {}", brokerInformation);
                        try {
                            try {
                                ChannelFuture connectFuture = connectToBroker(clientBootstrap, brokerHost);
                                if (connectFuture.isSuccess()) {
                                    BROKER_IP = brokerHost;
                                    logger.atInfo().log("Connect Broker Success, broker host is {}", brokerHost);
                                    MulticastController.endMulticastTime = System.currentTimeMillis();
                                    logger.atInfo().log(
                                            "> 連線成功時間: {}", dateFormat.format(MulticastController.endMulticastTime));
                                    long timeConsuming = MulticastController.endMulticastTime
                                            - MulticastController.startMulticastTime;
                                    logger.atInfo().log("> 連線總計耗時: {} sec.", timeConsuming / 1000.00);

                                    if (brokerChannel != null) {
                                        brokerChannel.close();
                                    }
                                    brokerChannel = connectFuture.channel();
                                    isFindBroker.set(true);
                                } else {
                                    logger.atInfo().log("Can't connect Broker : {}", brokerInformation);
                                }
                            } catch (InterruptedException e) {
                                logger.atInfo().log("Can't Connect Broker : {}", brokerInformation);
                            }
                        } catch (Exception e) {
                            logger.atInfo().log("Can't Connect Broker : {}", brokerInformation);
                        }
                        if (brokerChannel != null) {
                            MulticastController.setMode("waiting");
                        } else {
                            clientIdleCount += 1;
                            if (clientIdleCount >= 3) { // Unable to connect to current broker timeout.
                                String UnableConnectBroker = (String) brokerInformation.get("nodeName");
                                if (!brokerInformation.get("ip").equals(HiBAInitial.accountingServer)) {
                                    logger.atInfo().log("{} add to block list.", UnableConnectBroker);
                                    blackList.put(UnableConnectBroker, (String) brokerInformation.get("ip"));
                                }
                                clientIdleCount = 0;
                                brokerInformation.clear();
                                MulticastController.startMulticastTime = 0;
                                MulticastController.setMode("init");
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof SyncMessage) {

            } else if (msg instanceof ScorePublishMessage) {
                ctx.writeAndFlush(buildScorePublishResponse((ScorePublishMessage) msg));
            } else {
                super.channelRead(ctx, msg);
            }
        }

        private ScorePublishResponse buildScorePublishResponse(ScorePublishMessage msg) {
            scoreMap.put(msg.getName(), msg.getScore());
            return new ScorePublishResponse(score);
        }

        private String compareMap(Map<String, Float> map) {
            Map.Entry<String, Float> maxEntry = null;
            for (Map.Entry<String, Float> entry : map.entrySet()) {
                if (maxEntry == null || entry.getValue().compareTo(maxEntry.getValue()) > 0) {
                    maxEntry = entry;
                }
            }
            return maxEntry.getKey();
        }

        private boolean createReverseChannel(String clientDeviceName, String clientAddress, Integer port) {
            // 使用ReverseSSHTunnelManager建立SSH隧道
            logger.atInfo().log(
                    "Creating SSH tunnel to {} ({}:{}) for device: {}",
                    clientAddress,
                    clientAddress,
                    port,
                    clientDeviceName);

            boolean result = sshTunnelManager.createTunnel(
                    String.valueOf(HiBAInitial.DATA_PORT), // local port
                    String.valueOf(port), // remote port
                    "user", // SSH user
                    clientAddress // SSH host
                    );

            if (!result) {
                logger.atError().log("Failed to create SSH tunnel to {}:{}", clientAddress, port);
                return false;
            }

            ResourceCleanupManager.getManager().addNetworkConnectionResource(sshTunnelManager, "Reverse SSH Manager");
            return true;
        }

        private void clearReverseChannel() {
            logger.atInfo().log("Closing SSH tunnel");
            sshTunnelManager.close();
        }

        private boolean runCommand(String cmd) {
            try {
                boolean state = false;
                logger.atInfo().log("Executing command: {}", cmd);

                Runtime runtime = Runtime.getRuntime();
                Process proc = runtime.exec(cmd);

                // Read stdout
                InputStream inputStream = proc.getInputStream();
                InputStreamReader readStream = new InputStreamReader(inputStream);
                BufferedReader buffReader = new BufferedReader(readStream);

                // Read stderr
                InputStream errorStream = proc.getErrorStream();
                InputStreamReader errorReadStream = new InputStreamReader(errorStream);
                BufferedReader errorBuffReader = new BufferedReader(errorReadStream);

                StringBuilder output = new StringBuilder();
                StringBuilder errorOutput = new StringBuilder();
                String line = null;

                // Collect all stdout
                while ((line = buffReader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.atDebug().log("STDOUT: {}", line);
                    if (line.contains("true")) {
                        state = true;
                    } else if (line.contains("false")) {
                        state = false;
                    }
                }

                // Collect all stderr
                while ((line = errorBuffReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                    logger.atWarn().log("STDERR: {}", line);
                }

                // Wait for process completion and get exit code
                int exitCode = proc.waitFor();
                logger.atDebug().log("Command '{}' finished with exit code: {}", cmd, exitCode);

                // Log summary
                if (output.length() > 0) {
                    logger.atDebug().log(
                            "Command output:\n{}", output.toString().trim());
                }
                if (errorOutput.length() > 0) {
                    logger.atWarn()
                            .log(
                                    "Command error output:\n{}",
                                    errorOutput.toString().trim());
                }

                if (state) {
                    logger.atInfo().log("Command executed successfully: {}", cmd);
                } else if (exitCode == 0 && errorOutput.length() == 0) {
                    // Command succeeded but didn't output "true"
                    state = true;
                    logger.atInfo().log("Command executed successfully (exit code 0): {}", cmd);
                } else {
                    logger.atWarn().log("Command failed or returned false: {}", cmd);
                }

                return state;
            } catch (Exception e) {
                logger.atError().setCause(e).log("Error running command '{}': {}", cmd, e.getMessage());
                return false;
            }
        }
    }

    @Sharable
    private class AcctServerKeepAliver extends SimpleChannelInboundHandler<AccountingTestResponse>
            implements Runnable { // broker
        // Associate
        // to
        // Accounting
        // server
        // heartbeat.

        private final AtomicInteger responseTimeoutSec = new AtomicInteger(0);
        private final AccountingTestMessage accountingTestMessage = new AccountingTestMessage();
        private int msgCount = 0;
        private int msgCount1 = 0;

        @Override
        public void run() {
            logger.atInfo().log("Accounting Server heartbeat Start");
            while (true) {
                delay(1);

                if (accountingServerChannel == null) {
                    continue;
                }

                try {
                    ChannelFuture testFuture = accountingServerChannel.writeAndFlush(accountingTestMessage);
                    testFuture.await();
                } catch (InterruptedException e) {
                }

                int times = responseTimeoutSec.getAndIncrement();

                if (times >= 3) {
                    msgCount += 1;
                    msgCount1 += 1;
                    try {
                        if (msgCount >= 3) {
                            logger.atInfo().log(
                                    "Accounting Server Lost Connect, try to reconnecting to Accounting server.");
                            msgCount = 0;
                        }
                        accountingServerChannel.close();
                        accountingServerChannel = null;
                        ChannelFuture connectFuture = connectToBroker(clientBootstrap, accountingServerHost);
                        if (connectFuture.isSuccess()) {
                            accountingServerChannel = connectFuture.channel();
                            responseTimeoutSec.set(0);
                            times = 0;
                            logger.atInfo().log("Connected success.");
                        } else {
                            if (msgCount1 >= 5) {
                                logger.atInfo().log("Connection failed.");
                                msgCount1 = 0;
                            }
                        }
                    } catch (InterruptedException | NullPointerException e) {
                    }
                }
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, AccountingTestResponse msg) {
            responseTimeoutSec.set(0);
        }
    }

    @Sharable
    private class AcctClientKeepAliver extends SimpleChannelInboundHandler<AccountingTestMessage> implements Runnable {

        public final Map<Channel, AtomicInteger> counter = new ConcurrentHashMap<>();
        private final AccountingTestResponse response = new AccountingTestResponse();

        @Override
        public void run() {
            logger.atInfo().log("Lower Broker heartbeat.");
            while (true) {
                delay(1);

                // clear die channel
                Set<Channel> counterSet = new HashSet<>(counter.keySet());
                Set<Channel> clientSet = new HashSet<>(acctLowerBroker.keySet());
                counterSet.removeAll(clientSet);
                counter.keySet().removeAll(counterSet);
                brokerList.clear();

                // keep alive count
                acctLowerBroker.forEach((channel, info) -> {
                    if (counter.containsKey(channel)) {
                        int lostConnectSec = counter.get(channel).incrementAndGet();
                        if (lostConnectSec >= 3) {
                            try {
                                channel.close().sync();
                            } catch (InterruptedException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                        } else {
                            String address = info.toString().split("=")[1];
                            brokerList.add(address);
                        }
                    } else {
                        counter.put(channel, new AtomicInteger(0));
                    }
                });
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, AccountingTestMessage msg) {
            Channel clientChannel = ctx.channel();
            if (counter.containsKey(ctx.channel())) {
                counter.get(clientChannel).set(0);
            }
            clientChannel.writeAndFlush(response);
        }
    }

    @Sharable
    private class BrokerKeepAliver extends SimpleChannelInboundHandler<TestResponse>
            implements Runnable { // 下層對Broker連線
        // => 心跳檢測

        private String brokerName = "";
        private final AtomicInteger responseTimeoutSec = new AtomicInteger(0);
        private final TestMessage testMessage = new TestMessage();
        private int updateDeviceInfoCount = 0;

        public void setBrokerName(String brokerName) {
            this.brokerName = brokerName;
        }

        @Override
        public void run() {
            logger.atInfo().log("Broker Keep Alive Start");
            while (true) {
                delay(1);

                if (brokerChannel == null) {
                    continue;
                }

                try {
                    ChannelFuture testFuture = brokerChannel.writeAndFlush(testMessage);
                    testFuture.await();
                } catch (InterruptedException e) {
                }

                int times = responseTimeoutSec.getAndIncrement();

                // HiBA v3實驗用, 向Broker傳遞當前設備硬體資訊
                if (HiBAInitial.updateDeviceInfo & updateDeviceInfoCount >= HiBAInitial.updateDeviceInfoFrequency) {
                    // ArrayList<ArrayList<Object>> deviceInfo = new ArrayList<>();
                    // ArrayList<Object> getRateInfo = new ArrayList<>();
                    // Map<String, Object> getRate = CPUUsageRate.getCPURate();
                    // getRate.put("nodeName", HiBAInitial.nodeName);
                    // getRateInfo.add(getRate);
                    // deviceInfo.add(getRateInfo);
                    ArrayList<Object> deviceInfo = new ArrayList<>();
                    Map<String, Object> getRate = CPUUsageRate.getCPURate();
                    getRate.put("nodeName", HiBAInitial.nodeName);
                    deviceInfo.add(getRate.toString());

                    App.nodeResource.put("deviceInfo", deviceInfo);

                    logger.atInfo()
                            .addKeyValue("nodeResource", App.nodeResource)
                            .log("App.nodeResource: {}", App.nodeResource);
                    brokerChannel.writeAndFlush(new ResourceUpdateMessage(App.nodeResource));
                    updateDeviceInfoCount = 0;
                } else {
                    updateDeviceInfoCount += 1;
                }

                if (times >= 3) {
                    logger.atInfo().log("Broker Lost Connect: {}", brokerName);
                    logger.atInfo().log("Find the Broker again.");
                    multicastBrokerSelector.sameTierNodes.remove(brokerName); // 這部份要改成 重啟broker選擇器,
                    // 而後還需要設計收到init廣播的群組回覆機制
                    brokerChannel = null;
                    responseTimeoutSec.set(0);
                    brokerInformation.clear();
                    MulticastController.startMulticastTime = 0;
                    if (!MulticastController.getMode().equals("isBroker")) {
                        MulticastController.setMode("init");
                    }
                }
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TestResponse msg) {
            responseTimeoutSec.set(0);
        }
    }

    @Sharable
    private class ClientKeepAliver extends SimpleChannelInboundHandler<TestMessage> implements Runnable {

        public final Map<Channel, AtomicInteger> counter = new ConcurrentHashMap<>();
        private final TestResponse response = new TestResponse();

        @Override
        public void run() {
            logger.atInfo().log("Client Keep Alive Start");
            while (true) {
                delay(1);

                // clear die channel
                Set<Channel> counterSet = new HashSet<>(counter.keySet());
                Set<Channel> clientSet = new HashSet<>(clients.keySet());
                counterSet.removeAll(clientSet);
                counter.keySet().removeAll(counterSet);
                // brokerList.clear();

                // keep alive count
                clients.forEach((channel, info) -> {
                    if (counter.containsKey(channel)) {
                        int lostConnectSec = counter.get(channel).incrementAndGet();
                        if (lostConnectSec >= 3) {
                            try {
                                channel.close().sync();
                            } catch (InterruptedException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                            // } else {
                            // String address = info.toString().split("=")[1];
                            // brokerList.add(address);
                        }
                    } else {
                        counter.put(channel, new AtomicInteger(0));
                    }
                });
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TestMessage msg) {
            Channel clientChannel = ctx.channel();
            if (counter.containsKey(ctx.channel())) {
                counter.get(clientChannel).set(0);
            }
            clientChannel.writeAndFlush(response);
        }
    }
}
