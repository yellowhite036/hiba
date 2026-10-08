package org.gslab.sdns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.message_hiba.FileTransferClientHandler;
import org.gslab.message_hiba.FileTransferFormat;
import org.gslab.model.RequestId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileTransferClient {

    private static final Logger logger = LoggerFactory.getLogger(FileTransferClient.class);

    public static boolean doTransfer(
            String FilePath,
            String targetPath,
            String nodeIP,
            Channel nodeChannel,
            Integer customPort,
            RequestId requestId) {
        return doTransfer(FilePath, targetPath, nodeIP, nodeChannel, customPort, null, requestId);
    }

    /**
     * Enhanced file transfer with blockchain metadata support (TODO 8)
     *
     * @param FilePath         Source file path
     * @param targetPath       Target directory path
     * @param nodeIP           Target node IP
     * @param nodeChannel      Network channel
     * @param customPort       Custom port number
     * @param metadata         FileMetadata for blockchain verification
     * @param requestId        Request ID for tracking
     * @return true if transfer successful, false otherwise
     */
    public static boolean doTransfer(
            String FilePath,
            String targetPath,
            String nodeIP,
            Channel nodeChannel,
            Integer customPort,
            FileMetadata metadata,
            RequestId requestId) {
        String targetAddress = null;
        int port;
        try {
            if (nodeChannel != null) {
                String addressFinish = nodeChannel.remoteAddress().toString().split(":")[0];
                int size = Arrays.asList(addressFinish.split("/")).size();
                if (size > 1) {
                    targetAddress = addressFinish.split("/")[size - 1];
                } else {
                    targetAddress = addressFinish.split("/")[1];
                }
            } else {
                targetAddress = nodeIP;
            }
            if (customPort != null) {
                port = customPort;
            } else {
                port = HiBAInitial.DATA_PORT;
            }
            FileTransferFormat transferFile = new FileTransferFormat();
            File file = new File(FilePath);
            String fileName = file.getName();
            transferFile.setFile(file);
            transferFile.setFileName(fileName);
            transferFile.setTargetPath(targetPath);
            transferFile.setStartingPoint(0);

            // Set requestId for server-side logging (explicit parameter, no MDC)
            if (requestId != null) {
                transferFile.setRequestId(requestId);
            }

            // TODO 8: Set blockchain metadata if provided
            if (metadata != null) {
                transferFile.setMetadata(metadata);
            }

            logger.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("targetAddress", targetAddress)
                    .addKeyValue("port", port)
                    .addKeyValue("filePath", FilePath)
                    .log(
                            "[DataPlane] Send to {}:{}: {}",
                            targetAddress,
                            port,
                            FilePath); //  + ":" + port + " ,target: " + targetPath
            new FileTransferClient().connect(port, targetAddress, transferFile);
            return true;
        } catch (Exception e) {
            logger.atError().setCause(e).log("An exception occurred in FileTransferClient: {}", e.getMessage());
            return false;
        }
    }

    public void connect(int port, String host, final FileTransferFormat transferFile) throws Exception {
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        try {
            bootstrap
                    .group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<>() {

                        @Override
                        protected void initChannel(Channel channel) {
                            // String cChatPath = HiBAInitial.SSLPath + "/clientStore.jks";
                            // SSLEngine engine = SslContextFactory.getClientContext(cChatPath).createSSLEngine();
                            // engine.setUseClientMode(true);
                            // channel.pipeline().addLast("ssl", new SslHandler(engine));

                            channel.pipeline().addLast(new ObjectEncoder());
                            channel.pipeline()
                                    .addLast(new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(null)));
                            channel.pipeline().addLast(new FileTransferClientHandler(transferFile));
                        }
                    });
            ChannelFuture future = bootstrap.connect(host, port).sync();
            future.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
        }
    }

    public static boolean checkConnection(String targetIP) {
        Process child;
        Boolean state = false;
        try {
            child = Runtime.getRuntime().exec("ping -I " + HiBAInitial.multicastInterface + " -c 1 -W 1 " + targetIP);
            BufferedReader info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = info.readLine()) != null) {
                if (content.contains("ttl")) {
                    state = true;
                }
            }
            return state;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void main(String[] args) {
        String inputFile =
                "D:/00-NKFUST/01-GSLab/HierarchicalBrokeringArchitecture/G01-SourceCode/hiba/t437k901/model/t1437k901_2022_08_12_22_14_24-useHog-useRatio/root-1.xml"; // args[0];
        String nodeIP = "127.0.0.1"; // args[1];
        String port = "8088"; // args[2];
        logger.atInfo()
                .addKeyValue("inputFile", inputFile)
                .addKeyValue("nodeIP", nodeIP)
                .log("input file: {} node IP: {}", inputFile, nodeIP);
        doTransfer(inputFile, "./receiveFile/", nodeIP, null, Integer.valueOf(port), null);
    }
}
