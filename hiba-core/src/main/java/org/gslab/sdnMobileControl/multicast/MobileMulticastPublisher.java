package org.gslab.sdnMobileControl.multicast;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Map;
import org.gslab.sdns.HiBAInitial;

class MobileMulticastPublisher {

    public static boolean sendMulticast(Map<String, Object> map) {
        String MULTICAST_INTERFACE = HiBAInitial.OBURouterInterface;
        DatagramChannel datagramChannel;
        try {
            NetworkInterface networkInterface = DatagramChannelBuilder.ifaceName(MULTICAST_INTERFACE);

            datagramChannel = DatagramChannelBuilder.bindChannel(null)
                    .setOption(StandardSocketOptions.IP_MULTICAST_IF, networkInterface);
            ByteBuffer byteBuffer = ByteBuffer.wrap(map.toString().getBytes());
            datagramChannel.send(byteBuffer, DatagramChannelBuilder.inetSocketAddr());
            return true;
        } catch (IOException | IllegalArgumentException e) {
            /*
             * 這邊如果print例外的話, 會跳IllegalArgumentException is Null
             * 原因是因為Wi-Fi 換手的時候有時間差, 導致LOCAL_IP還停留在上一個station的IP
             * 然後就用上一個station的IP發廣播
             */
            return false;
        }
    }
}
