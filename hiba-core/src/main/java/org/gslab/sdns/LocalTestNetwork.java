package org.gslab.sdns;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.gslab.SdnNetwork;
import org.gslab.message_hiba.LoginMessage;
import org.gslab.message_hiba.Message;
import org.gslab.protocol.HiBAFrameDecoder;
import org.gslab.protocol.MessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalTestNetwork implements SdnNetwork {

    private static final Logger logger = LoggerFactory.getLogger(LocalTestNetwork.class);

    private final Map<Channel, Entry<String, String>> clients = new HashMap<>();

    private String localName = "";
    private String localHost = "";

    private String brokerHost = "";

    private Initializer clientInitializer = null;
    private Initializer serverInitializer = null;
    private final int defaultPort = 8091;

    private Channel brokerChannel = null;
    private boolean isServer = false;

    public LocalTestNetwork(String brokerHost, String bindHost, String Name) {
        this.brokerHost = brokerHost;
        this.localHost = bindHost;
        this.localName = Name;
    }

    public LocalTestNetwork(String bindHost) {
        localName = "server";
        localHost = bindHost;
        isServer = true;
    }

    private void serverBoot(String bindHost) {
        NioEventLoopGroup computeBossWorker = new NioEventLoopGroup();
        new ServerBootstrap()
                .group(computeBossWorker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel channel) {
                        channel.pipeline().addLast(new HiBAFrameDecoder());
                        channel.pipeline().addLast(new MessageCodec());

                        channel.pipeline().addLast("clientHandler", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                if (clients.containsKey(channel)) {
                                    clients.remove(channel);
                                }
                                super.channelInactive(ctx);
                            }
                        });

                        channel.pipeline().addLast("loginHandler", new SimpleChannelInboundHandler<LoginMessage>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, LoginMessage msg) {
                                logger.atInfo()
                                        .addKeyValue("channel", ctx.channel())
                                        .log("{} : login", ctx.channel());
                                clients.put(ctx.channel(), Map.entry(msg.getName(), msg.getServerIp()));
                            }
                        });

                        if (serverInitializer != null) {
                            serverInitializer.initial(channel);
                        }
                    }
                })
                .bind(new InetSocketAddress(bindHost, defaultPort));
    }

    private void clientBoot(String brokerHost, String bindHost, String Name) throws Exception {
        NioEventLoopGroup bossWorker = new NioEventLoopGroup();
        ChannelFuture channelFuture = new Bootstrap()
                .group(bossWorker)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new HiBAFrameDecoder());
                        channel.pipeline().addLast(new MessageCodec());

                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                ctx.channel().writeAndFlush(new LoginMessage(localName, localHost, defaultPort));
                                super.channelActive(ctx);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                logger.atInfo().log("disconnect");
                                super.channelInactive(ctx);
                            }
                        });

                        if (clientInitializer != null) {
                            clientInitializer.initial(channel);
                        }
                    }
                })
                .connect(new InetSocketAddress(brokerHost, defaultPort));
        channelFuture.sync();

        if (!channelFuture.isSuccess()) {
            throw new Exception(channelFuture.cause());
        }
        Channel channel = channelFuture.channel();
        brokerChannel = channel;
        channel.closeFuture().addListener(listener -> bossWorker.shutdownGracefully());
    }

    @Override
    public Map<Channel, Entry<String, String>> getClients() {
        return clients;
    }

    @Override
    public Entry<String, String> getLocal() {
        return Map.entry(localName, localHost);
    }

    @Override
    public Channel getBrokerChannel() {
        return brokerChannel;
    }

    @Override
    public void clientInitialHandler(Initializer initializer) {
        this.clientInitializer = initializer;
    }

    @Override
    public void serverInitialHandler(Initializer initializer) {
        this.serverInitializer = initializer;
    }

    @Override
    public void sendShortConnectMessage(String hostName, Message message, Initializer initializer) throws Exception {

        NioEventLoopGroup bossWorker = new NioEventLoopGroup();
        ChannelFuture channelFuture = new Bootstrap()
                .group(bossWorker)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new HiBAFrameDecoder());
                        channel.pipeline().addLast(new MessageCodec());
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                ctx.channel().writeAndFlush(message);
                                super.channelActive(ctx);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                logger.atInfo()
                                        .addKeyValue("channel", ctx.channel())
                                        .log("{} : disconnect", ctx.channel());
                                super.channelInactive(ctx);
                            }
                        });

                        if (initializer != null) {
                            initializer.initial(channel);
                        }
                    }
                })
                .connect(new InetSocketAddress(hostName, defaultPort));

        channelFuture.sync();

        if (!channelFuture.isSuccess()) {
            throw new Exception(channelFuture.cause());
        }

        Channel channel = channelFuture.channel();
        channel.closeFuture().addListener(listener -> bossWorker.shutdownGracefully());
        channel.closeFuture().sync();
    }

    @Override
    public void sendConnectMessage(String hostName, Message message, Initializer initializer, boolean closeAfterSend)
            throws Exception {
        sendShortConnectMessage(hostName, message, initializer); // LocalTestNetwork 使用相同實現
    }

    @Override
    public void start() throws Exception {
        serverBoot(localHost);

        if (!isServer) {
            clientBoot(brokerHost, brokerHost, localName);
        }
    }
}
