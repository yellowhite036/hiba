package org.gslab.nettyhandlers_hiba;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.io.File;
import java.io.RandomAccessFile;
import org.gslab.message_hiba.FileTransferMessage;

@Sharable
public class FileTransferHandler extends SimpleChannelInboundHandler<FileTransferMessage> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FileTransferMessage msg) throws Exception {
        File path = new File(msg.getTargetPath());
        File file = new File(msg.getTargetPath() + "/" + msg.getFileName());

        if (!path.exists()) {
            path.mkdirs();
        }

        if (!file.exists()) {
            file.createNewFile();
        }

        RandomAccessFile StreamFW = new RandomAccessFile(file, "rw");
        StreamFW.seek(msg.getFileSeek());
        StreamFW.write(msg.getFileBuf(), 0, msg.getLen());
        StreamFW.close();
    }
}
