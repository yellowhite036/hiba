package org.gslab.task;

import io.netty.channel.Channel;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.gslab.Distributor;
import org.gslab.model.RequestId;
import org.gslab.sdns.HiBAInitial;
import org.gslab.sdns.HiBANetwork;
import org.gslab.service.mService.multicast.MulticastController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 處理任務轉發邏輯的專用類別 將原本在 App.java 中複雜的轉發邏輯分離出來
 */
public class TaskForwardingHandler {

    private static final Logger logger = LoggerFactory.getLogger(TaskForwardingHandler.class);

    /**
     * 處理任務轉發邏輯
     *
     * @param distributor                        分發器實例
     * @param requestDistributeTier              請求分發層級
     * @param requestId                        請求名稱
     * @param requestResultWaiterList            請求結果等待列表
     * @param upperBrokerRequestResultWaiterList 上層broker請求結果等待列表
     * @return 任務執行結果
     */
    public static Map<String, Object> handleTaskForwarding(
            Distributor distributor,
            AtomicInteger requestDistributeTier,
            RequestId requestId,
            Map<RequestId, Channel> requestResultWaiterList,
            Map<RequestId, Channel> upperBrokerRequestResultWaiterList) {

        logger.atInfo().log("---------- Forwarding message ----------");

        String currentBrokerRoute = extractCurrentBrokerRoute(distributor);

        // 1. 向下層broker轉發
        if (shouldForwardToLowerBroker(
                requestDistributeTier, currentBrokerRoute, upperBrokerRequestResultWaiterList, requestId)) {
            return forwardToLowerBroker(distributor, requestDistributeTier, requestId, requestResultWaiterList);
        }

        // 2. 向上層broker轉發
        if (shouldForwardToUpperBroker(upperBrokerRequestResultWaiterList, requestId)) {
            return forwardToUpperBroker(distributor, requestId, upperBrokerRequestResultWaiterList);
        }

        // 3. 同層轉發
        return forwardToSameLayer(distributor, requestId, requestResultWaiterList);
    }

    private static String extractCurrentBrokerRoute(Distributor distributor) {
        try {
            @SuppressWarnings("unchecked")
            java.util.ArrayList<String> brokerRouteList = (java.util.ArrayList<String>)
                    distributor.getWorkInformation().get("brokerRoute");
            return brokerRouteList != null ? brokerRouteList.getLast() : null;
        } catch (Exception e) {
            logger.atWarn().setCause(e).log("Failed to extract broker route");
            return null;
        }
    }

    private static boolean shouldForwardToLowerBroker(
            AtomicInteger requestDistributeTier,
            String currentBrokerRoute,
            Map<RequestId, Channel> upperBrokerRequestResultWaiterList,
            RequestId requestId) {
        return requestDistributeTier.get() != 0
                && !HiBAInitial.nodeName.equals(currentBrokerRoute)
                && currentBrokerRoute != null
                && upperBrokerRequestResultWaiterList.get(requestId) == null;
    }

    private static boolean shouldForwardToUpperBroker(
            Map<RequestId, Channel> upperBrokerRequestResultWaiterList, RequestId requestId) {
        // requestId is guaranteed non-null after refactoring (generated at entry points)
        return MulticastController.getMode().equals("isBroker")
                && upperBrokerRequestResultWaiterList.containsKey(requestId);
    }

    private static Map<String, Object> forwardToLowerBroker(
            Distributor distributor,
            AtomicInteger requestDistributeTier,
            RequestId requestId,
            Map<RequestId, Channel> requestResultWaiterList) {

        try {
            requestDistributeTier.decrementAndGet();
            int currentDistributeTier = requestDistributeTier.get();

            Map<String, Object> currentRequest = distributor.getWorkInformation();
            currentRequest.put("tier", currentDistributeTier);
            distributor.setWorkInformation(currentRequest);

            @SuppressWarnings("unchecked")
            java.util.ArrayList<String> brokerRouteList = (java.util.ArrayList<String>)
                    distributor.getWorkInformation().get("brokerRoute");
            String lowerBroker = brokerRouteList.getLast();

            @SuppressWarnings("unchecked")
            Map<String, Object> lowerBrokerInfo = (Map<String, Object>) HiBANetwork.reverseChannelList.get(lowerBroker);
            String lowerBrokerPort = (String) lowerBrokerInfo.get("port");

            logger.atInfo()
                    .addKeyValue("brokerName", lowerBroker)
                    .addKeyValue("port", lowerBrokerPort)
                    .log(
                            "Forwarding to the lower layer broker, broker name is {}, port {}",
                            lowerBroker,
                            lowerBrokerPort);

            return distributor.getResult(
                    null, requestResultWaiterList.get(requestId), Integer.valueOf(lowerBrokerPort));

        } catch (Exception e) {
            logger.atError().setCause(e).log("Failed to forward to lower broker");
            throw new RuntimeException("Lower broker forwarding failed", e);
        }
    }

    private static Map<String, Object> forwardToUpperBroker(
            Distributor distributor, RequestId requestId, Map<RequestId, Channel> upperBrokerRequestResultWaiterList) {

        logger.atInfo().log("Forwarding to the upper layer broker");
        return distributor.getResult(null, upperBrokerRequestResultWaiterList.get(requestId), null);
    }

    private static Map<String, Object> forwardToSameLayer(
            Distributor distributor, RequestId requestId, Map<RequestId, Channel> requestResultWaiterList) {

        logger.atInfo().log("Forwarding to the same layer node");
        return distributor.getResult(null, requestResultWaiterList.get(requestId), null);
    }
}
