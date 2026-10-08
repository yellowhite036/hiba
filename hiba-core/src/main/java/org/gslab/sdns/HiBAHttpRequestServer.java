package org.gslab.sdns;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.gslab.App;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HiBAHttpRequestServer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HiBAHttpRequestServer.class);

    private final int httpPort;
    private final App app;
    private final ServerBootstrap httpServerBootstrap = new ServerBootstrap();
    private final EventLoopGroup bossGroup = new NioEventLoopGroup();
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final TempFileCleanupTask cleanupTask = new TempFileCleanupTask();

    public HiBAHttpRequestServer(App app) {
        this.app = app;
        this.httpPort = HiBAInitial.HTTP_REQUEST_PORT;
    }

    @Override
    public void run() {
        try {
            logger.atInfo()
                    .addKeyValue("httpPort", httpPort)
                    .log("Starting HiBA HTTP Request Server on port {}", httpPort);

            // Start temp file cleanup task
            cleanupTask.start();

            httpServerBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .handler(new LoggingHandler(LogLevel.DEBUG))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast("codec", new HttpServerCodec());

                            // Increase aggregator size to support larger requests (up to MAX_REQUEST_SIZE)
                            // Note: HttpObjectAggregator takes int parameter, max value is Integer.MAX_VALUE (2GB)
                            // For file uploads > this size, they will spill to disk via HttpPostRequestDecoder
                            long maxSize = FileUploadConfig.MAX_REQUEST_SIZE;
                            int aggregatorSize = (int) Math.min(maxSize, Integer.MAX_VALUE);

                            if (maxSize > Integer.MAX_VALUE) {
                                logger.atWarn()
                                        .addKeyValue("maxSize", maxSize)
                                        .log(
                                                "MAX_REQUEST_SIZE ({} bytes) exceeds Integer.MAX_VALUE, "
                                                        + "capping aggregator at 2GB",
                                                maxSize);
                            }

                            ch.pipeline().addLast("aggregator", new HttpObjectAggregator(aggregatorSize));
                            ch.pipeline().addLast("handler", new HttpRequestHandler(app));
                        }
                    });

            ChannelFuture future = httpServerBootstrap.bind(httpPort).sync();
            logger.atInfo()
                    .addKeyValue("httpPort", httpPort)
                    .log("HiBA HTTP Request Server started successfully on port {}", httpPort);
            future.channel().closeFuture().sync();

        } catch (InterruptedException e) {
            logger.atError().setCause(e).log("HTTP Request Server interrupted");
        } catch (Exception e) {
            logger.atError().setCause(e).log("Failed to start HTTP Request Server");
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        logger.atInfo().log("Shutting down HiBA HTTP Request Server");
        cleanupTask.stop();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
