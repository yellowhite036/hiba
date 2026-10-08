package org.gslab;

import io.netty.channel.Channel;
import java.util.Map;
import org.gslab.message_hiba.Message;

public interface SdnNetwork {

    Map<Channel, Map.Entry<String, String>> getClients();

    Map.Entry<String, String> getLocal();

    Channel getBrokerChannel();

    void clientInitialHandler(Initializer initializer);

    void serverInitialHandler(Initializer initializer);

    void sendShortConnectMessage(String hostName, Message message, Initializer initializer) throws Exception;

    void sendConnectMessage(String hostName, Message message, Initializer initializer, boolean closeAfterSend)
            throws Exception;

    void start() throws Exception;

    interface Initializer {

        void initial(Channel channel);
    }
}
