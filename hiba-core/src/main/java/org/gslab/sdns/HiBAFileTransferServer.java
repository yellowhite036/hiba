package org.gslab.sdns;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import org.gslab.util.PathUtil;
import org.gslab.message_hiba.FileTransferFormat;
import org.gslab.model.RequestId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HiBAFileTransferServer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HiBAFileTransferServer.class);
    private final int DATA_PORT = HiBAInitial.DATA_PORT;
    private String directory = HiBAInitial.fileTransferDirectory;
    private final ServerBootstrap transferServerBootstrap = new ServerBootstrap();
    private final EventLoopGroup bossGroup = new NioEventLoopGroup();
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();

    public HiBAFileTransferServer() {}

    public void FileTransferServer() {}

    public void run() {
        try {
            transferServerBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024) // Maximum number of connections
                    .childHandler(new ChannelInitializer<>() {

                        @Override
                        protected void initChannel(Channel channel) {
                            // String cChatPath = HiBAInitial.SSLPath + "/serverStore.jks";
                            // SSLEngine engine = SslContextFactory.getServerContext(cChatPath).createSSLEngine(); //
                            // 創建SSLEngine
                            // engine.setUseClientMode(false);
                            // channel.pipeline().addLast("ssl", new SslHandler(engine));

                            channel.pipeline().addLast(new ObjectEncoder());
                            channel.pipeline()
                                    .addLast(new ObjectDecoder(
                                            Integer.MAX_VALUE, ClassResolvers.weakCachingConcurrentResolver(null)));
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                                private int endPoint;
                                private volatile int start = 0;

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof FileTransferFormat transferFile) {
                                        RequestId requestId = transferFile.getRequestId();

                                        byte[] bytes = transferFile.getBytes();
                                        endPoint = transferFile.getEndPoint();
                                        String fileName = transferFile.getFileName();
                                        String targetPath = transferFile.getTargetPath();

                                        if (targetPath != null) {
                                            directory = targetPath;
                                        }

                                        Path dirPath = PathUtil.normalizePath(directory);
                                        Path receiveFilePath = PathUtil.buildPath(dirPath, fileName);
                                        File dir = dirPath.toFile();

                                        logger.atInfo()
                                                .addKeyValue("requestId", requestId)
                                                .addKeyValue("directoryPath", dirPath)
                                                .log("received -> {}", dirPath);
                                        if (!dir.exists()) {
                                            dir.mkdirs();
                                        }

                                        File file = receiveFilePath.toFile();
                                        RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
                                        randomAccessFile.seek(start);
                                        randomAccessFile.write(bytes);
                                        start = start + endPoint;

                                        if (endPoint > 0) {
                                            ctx.writeAndFlush(start);
                                        } else {
                                            logger.atInfo()
                                                    .addKeyValue("requestId", requestId)
                                                    .addKeyValue("receivedFilePath", receiveFilePath)
                                                    .log("receive: {}", receiveFilePath);
                                            randomAccessFile.close();

                                            ctx.close();
                                        }
                                    }
                                }
                            });
                        }
                    });

            ChannelFuture future = transferServerBootstrap.bind(DATA_PORT).sync();
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            logger.atError().setCause(e).log("File transfer server interrupted: {}", e.getMessage());
            e.printStackTrace();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) {
        if (!HiBAInitial.start()) {
            logger.atError().log("System environment initialization failed.");
            System.exit(0);
        }
        new Thread(new HiBAFileTransferServer()).start();
    }
}
