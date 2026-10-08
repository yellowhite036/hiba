package com.socomni.abstraction;
import org.gslab.model.RequestId;

import java.util.Map;
import com.rabbitmq.client.Channel;

public interface Distributor {
    void setRequestID(RequestId requestId);

    void setWorkInformation(Map<String, Object> workInformation);

    void setBrokerTier(int brokerTier);

    void setClientSize(int size);

    int getClientSize();

    Map<String, Object> getWorkInformation();

    int getBrokerTier();

    String getRequestID();

    void sendFileToRequestNode(Channel requestNodeChannel);

    Distributor getNewInstance();

    void initial();

    Map<String, Object> mapping(String name, Map<String, Object> resource, Channel networkChannel,
            Map<String, Object> resourceInThisTier);

    Map<String, Object> mappingLocal(Map<String, Object> localResource, Map<String, Object> resourceInThisTier);

    void merge(Map<String, Map<String, Object>> allResult);

    Map<String, Object> getResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort);

    void handleTaskFail(String name, Map<String, Object> resource, Map<String, Object> task);

    boolean hasMoreRound();
}
