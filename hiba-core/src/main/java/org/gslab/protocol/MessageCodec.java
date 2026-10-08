package org.gslab.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import org.gslab.message_hiba.Message;

public class MessageCodec extends ByteToMessageCodec<Message> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        byte[] magicNumber = new byte[5];
        in.readBytes(magicNumber);

        // byte version = in.readByte();
        // byte serialtype = in.readByte();
        // byte messgeType = in.readByte();
        // int sequenceId = in.readInt();
        // int length = in.readInt();

        in.readByte();
        in.readByte();
        in.readByte();
        in.readInt();
        int length = in.readInt();

        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
        Message message = (Message) ois.readObject();
        out.add(message);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, ByteBuf out) throws Exception {
        // magic number 5 bytes
        out.writeBytes(new byte[] {'G', 'S', 'L', 'A', 'B'});
        // version 1 byte
        out.writeByte(1);
        // Serializer 1 byte
        out.writeByte(1);
        // MessageType 1 byte
        out.writeByte(1);
        // SequenceId 4 byte
        out.writeInt(msg.getSequenceId());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(msg);
        byte[] bytes = bos.toByteArray();

        // data length 4 byte
        out.writeInt(bytes.length);
        // header total 16 byte

        out.writeBytes(bytes);
    }
}
