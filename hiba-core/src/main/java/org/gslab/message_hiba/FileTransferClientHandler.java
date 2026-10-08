package org.gslab.message_hiba;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.IOException;
import java.io.RandomAccessFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileTransferClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(FileTransferClientHandler.class);

    private int byteRead;
    private volatile int start = 0;
    private volatile int lastLength = 0;
    public RandomAccessFile randomAccessFile;
    private FileTransferFormat tranferFile;

    public FileTransferClientHandler(FileTransferFormat transferFile) {
        if (transferFile.getFile().exists()) {
            if (!transferFile.getFile().isFile()) {
                logger.atWarn()
                        .addKeyValue("filePath", transferFile.getFile())
                        .log("Not a file: {}", transferFile.getFile());
                return;
            }
        }
        this.tranferFile = transferFile;
    }

    public void channelActive(ChannelHandlerContext ctx) {
        try {
            // System.out.println("----- Transmission Start -----");
            randomAccessFile = new RandomAccessFile(tranferFile.getFile(), "r");
            randomAccessFile.seek(tranferFile.getStartingPoint());
            int dynamicControl = ((int) randomAccessFile.length() / 1024 / 1024) / 125;
            int dynamicValue = 0;
            if (dynamicControl == 0) {
                dynamicValue = 10;
            } else {
                dynamicValue = 10 * dynamicControl;
            }
            lastLength = (int) randomAccessFile.length() / dynamicValue;
            byte[] bytes = new byte[lastLength];
            if ((byteRead = randomAccessFile.read(bytes)) != -1) {
                tranferFile.setEndPoint(byteRead);
                tranferFile.setBytes(bytes);
                ctx.writeAndFlush(tranferFile);
            } else {
                logger.atInfo().log("File read finish.");
            }
        } catch (IOException i) {
            i.printStackTrace();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Integer) {
            start = (Integer) msg;
            if (start != -1) {
                randomAccessFile = new RandomAccessFile(tranferFile.getFile(), "r");
                // System.out.println("檔案總長度:" + (randomAccessFile.length() / 1024 / 1024));
                randomAccessFile.seek(start);
                // System.out.println("檔案長度:" + (randomAccessFile.length() / 10) / 1024 / 1024 + " MB");
                int remainingLength = (int) randomAccessFile.length() - start;
                /*
                if (remainingLength > 1048576) {
                    System.out.println("Transfer remaining: " + remainingLength / 1024 / 1024 + " MB");
                }
                */
                // int a = (int) (randomAccessFile.length() - start);
                int progress = (int) (randomAccessFile.length() / 10);
                if (remainingLength < progress) {
                    lastLength = remainingLength;
                }
                byte[] bytes = new byte[lastLength];
                if ((byteRead = randomAccessFile.read(bytes)) != -1 && (randomAccessFile.length() - start) > 0) {
                    tranferFile.setEndPoint(byteRead);
                    tranferFile.setBytes(bytes);
                    try {
                        ctx.writeAndFlush(tranferFile);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    randomAccessFile.close();
                    ctx.close();
                    // System.out.println("----- Transmission Finish -----");
                }
            }
        }
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
