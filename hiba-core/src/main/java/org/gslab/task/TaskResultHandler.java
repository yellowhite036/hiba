package org.gslab.task;

import io.netty.channel.Channel;
import java.util.Map;
import org.gslab.Distributor;
import org.gslab.message_hiba.RequestFinishMessage;
import org.gslab.message_hiba.TaskFinishMessage;
import org.gslab.model.RequestId;
import org.gslab.sdns.HiBAInitial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 處理任務結果返回的專用類別 將原本在 App.java 中複雜的結果處理邏輯分離出來
 */
public class TaskResultHandler {

    private static final Logger logger = LoggerFactory.getLogger(TaskResultHandler.class);

    /**
     * 處理任務完成後的結果返回邏輯
     *
     * @param result                             任務執行結果
     * @param distributor                        分發器實例
     * @param requestId                        請求名稱
     * @param requestDistributeTier              請求分發層級
     * @param requestResultWaiterList            請求結果等待列表
     * @param upperBrokerRequestResultWaiterList 上層broker請求結果等待列表
     * @param network                            網路實例
     * @return 處理結果狀態
     */
    public static TaskHandleResult handleTaskResult(
            Map<String, Object> result,
            Distributor distributor,
            RequestId requestId,
            int requestDistributeTier,
            Map<RequestId, Channel> requestResultWaiterList,
            Map<RequestId, Channel> upperBrokerRequestResultWaiterList,
            Object network) {

        result.put("state", true);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> taskDetails = (Map<String, Object>) result.get("allResult");
            result.put("taskDetails", taskDetails);
        } catch (Exception e) {
            logger.atWarn().setCause(e).log("Failed to extract task details");
        }

        logger.atInfo().addKeyValue("requestId", requestId).log("Processing task result for: {}", requestId);

        // 1. 向上層broker返回
        if (requestDistributeTier != 0) {
            return handleUpperBrokerReturn(result, network);
        }

        // 2. 向請求節點返回
        boolean hasRequestNodeWaiter = requestResultWaiterList.containsKey(requestId);

        if (hasRequestNodeWaiter) {
            return handleRequestNodeReturn(result, distributor, requestId, requestResultWaiterList);
        }

        // 3. 向上層broker請求返回
        if (upperBrokerRequestResultWaiterList.containsKey(requestId)) {
            return handleUpperBrokerRequestReturn(result, requestId, upperBrokerRequestResultWaiterList);
        }

        // 4. 本地請求處理
        return handleLocalRequest(result);
    }

    private static TaskHandleResult handleUpperBrokerReturn(Map<String, Object> brokerResult, Object network) {
        logger.atInfo().log("Task Return To Broker");

        try {
            // 使用反射來調用 network.getBrokerChannel().writeAndFlush()
            // 這樣可以避免直接依賴具體的網路實現
            Object brokerChannel =
                    network.getClass().getMethod("getBrokerChannel").invoke(network);
            brokerChannel
                    .getClass()
                    .getMethod("writeAndFlush", Object.class)
                    .invoke(brokerChannel, new TaskFinishMessage(brokerResult));

            return TaskHandleResult.CONTINUE;
        } catch (Exception e) {
            logger.atError().setCause(e).log("Failed to send result to upper broker");
            return TaskHandleResult.ERROR;
        }
    }

    private static TaskHandleResult handleRequestNodeReturn(
            Map<String, Object> brokerResult,
            Distributor distributor,
            RequestId requestId,
            Map<RequestId, Channel> requestResultWaiterList) {

        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("availableWaiterKeys", requestResultWaiterList.keySet())
                .log(
                        "handleRequestNodeReturn: RequestId: {}, Available waiter keys: {}",
                        requestId,
                        requestResultWaiterList.keySet());

        // 首先嘗試直接使用 requestId 查找
        Channel requestNodeChannel = requestResultWaiterList.get(requestId);
        logger.atDebug()
                .addKeyValue("channel", requestNodeChannel)
                .addKeyValue("key", requestId)
                .log("Direct lookup result: channel={}, key={}", requestNodeChannel, requestId);

        if (requestNodeChannel != null) {
            distributor.sendFileToRequestNode(requestNodeChannel);
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .log("Return result to origin request node: {}", requestId);
            logger.atDebug().addKeyValue("channel", requestNodeChannel).log("Channel: {}", requestNodeChannel);
            logger.atDebug().addKeyValue("brokerResult", brokerResult).log("Result: {}", brokerResult);
            logger.atInfo()
                    .addKeyValue("channel", requestNodeChannel)
                    .addKeyValue("requestId", requestId)
                    .log(
                            "TaskResultHandler: About to send RequestFinishMessage to channel: {}. RequestId: {}",
                            requestNodeChannel,
                            requestId);
            // 檢查 channel 是否還活躍
            if (requestNodeChannel.isActive()) {
                logger.atInfo()
                        .addKeyValue("requestId", requestId)
                        .log(
                                "TaskResultHandler: Channel is active, sending RequestFinishMessage. RequestId: {}",
                                requestId);

                try {
                    // 給一點時間讓 TCP 連線穩定，避免消息在傳輸過程中丟失
                    Thread.sleep(100);

                    // 發送 RequestFinishMessage 並等待確認
                    io.netty.channel.ChannelFuture future =
                            requestNodeChannel.writeAndFlush(new RequestFinishMessage(brokerResult));
                    future.await(1000); // 等待最多1秒

                    if (future.isSuccess()) {
                        logger.atInfo()
                                .addKeyValue("requestId", requestId)
                                .log(
                                        "TaskResultHandler: RequestFinishMessage sent successfully. RequestId: {}",
                                        requestId);
                    } else {
                        logger.atError()
                                .addKeyValue("requestId", requestId)
                                .setCause(future.cause())
                                .log(
                                        "TaskResultHandler: Failed to send RequestFinishMessage. RequestId: {}, Cause: {}",
                                        requestId,
                                        future.cause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.atError()
                            .addKeyValue("requestId", requestId)
                            .setCause(e)
                            .log(
                                    "TaskResultHandler: Interrupted while sending RequestFinishMessage. RequestId: {}",
                                    requestId);
                }
            } else {
                logger.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("channel", requestNodeChannel)
                        .log(
                                "TaskResultHandler: Channel is NOT active! Cannot send RequestFinishMessage. RequestId: {}, Channel: {}",
                                requestId,
                                requestNodeChannel);
            }

            requestResultWaiterList.remove(requestId);
        } else {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("availableKeys", requestResultWaiterList.keySet())
                    .log(
                            "No channel found for requestId: {}. Available keys: {}",
                            requestId,
                            requestResultWaiterList.keySet());
        }

        logger.atInfo().addKeyValue("requestId", requestId).log("Remote Request Finish: {}", requestId);

        return TaskHandleResult.CONTINUE;
    }

    private static TaskHandleResult handleUpperBrokerRequestReturn(
            Map<String, Object> brokerResult,
            RequestId requestId,
            Map<RequestId, Channel> upperBrokerRequestResultWaiterList) {

        Channel upperBrokerChannel = upperBrokerRequestResultWaiterList.get(requestId);
        if (upperBrokerChannel != null) {
            upperBrokerChannel.writeAndFlush(new TaskFinishMessage(brokerResult));
        }
        upperBrokerRequestResultWaiterList.remove(requestId);
        logger.atInfo().addKeyValue("requestId", requestId).log("Remote Upper Broker Request Finish: {}", requestId);

        return TaskHandleResult.CONTINUE;
    }

    private static TaskHandleResult handleLocalRequest(Map<String, Object> brokerResult) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultDetail = (Map<String, Object>) brokerResult.get("result");
            if (resultDetail != null) {
                Object nodeName = resultDetail.get("nodeName");
                if (HiBAInitial.nodeName.equals(nodeName)) {
                    logger.atInfo().log("Local Request Finish");
                    // TODO: 這裡可以加入 outputResponse 的邏輯

                    logger.atInfo().addKeyValue("resultDetail", resultDetail).log("Result: {}", resultDetail);
                }
            }
        } catch (Exception e) {
            logger.atWarn().setCause(e).log("Failed to process local request result");
        }

        return TaskHandleResult.FINISH;
    }

    /**
     * 任務處理結果枚舉
     */
    public enum TaskHandleResult {
        CONTINUE, // 繼續處理下一個任務
        FINISH, // 任務處理完成
        ERROR // 處理出錯
    }
}
