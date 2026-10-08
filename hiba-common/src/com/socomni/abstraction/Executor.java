package com.socomni.abstraction;

import java.util.Map;
import com.rabbitmq.client.Channel;

public interface Executor {

    void setTier(int tier);

    int getTier();

    void setTaskInformation(Map<String, Object> taskInformation);

    Map<String, Object> getTaskInformation();

    Executor getNewInstance();

    Map<String, Object> execute(Map<String, Object> information, Channel brokerChannel);

    Map<String, Object> brokerExecute(Map<String, Object> information);
}
