package org.gslab.sdns;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import java.nio.file.Path;
import org.gslab.security.ReceivePathPolicy;
import org.gslab.security.TransferSecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 檔案傳輸接收服務（DATA_PORT，預設 8088）。
 * <p>
 * 每條連線傳一個檔案，由 {@link FileTransferServerHandler} 處理：驗證傳送端 TPM 簽章、
 * 限制存檔目錄、比對檔案雜湊（env.json TransferSecurity.fileTransferAuth 決定只記錄或拒絕）。
 * 單一物件大小上限為 TransferSecurity.maxChunkBytes（原本是 Integer.MAX_VALUE，可被用來耗盡記憶體）。
 * </p>
 */
public class HiBAFileTransferServer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HiBAFileTransferServer.class);
    private final int DATA_PORT = HiBAInitial.DATA_PORT;
    private final ServerBootstrap transferServerBootstrap = new ServerBootstrap();
    private final EventLoopGroup bossGroup = new NioEventLoopGroup();
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();

    public HiBAFileTransferServer() {}

    public void FileTransferServer() {}

    public void run() {
        TransferSecurityConfig cfg = TransferSecurityConfig.get();
        ReceivePathPolicy policy = new ReceivePathPolicy(Path.of(""), cfg.allowedReceiveDirectories());
        logger.atInfo().log("File transfer server: allowed receive directories {}", policy.roots());
        try {
            transferServerBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024) // Maximum number of connections
                    .childHandler(new ChannelInitializer<>() {

                        @Override
                        protected void initChannel(Channel channel) {
                            channel.pipeline().addLast(new ObjectEncoder());
                            channel.pipeline()
                                    .addLast(new ObjectDecoder(
                                            cfg.maxChunkBytes(), ClassResolvers.weakCachingConcurrentResolver(null)));
                            channel.pipeline().addLast(new FileTransferServerHandler(cfg, policy));
                        }
                    });

            ChannelFuture future = transferServerBootstrap.bind(DATA_PORT).sync();
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            logger.atError().setCause(e).log("File transfer server interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
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
