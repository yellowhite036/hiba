package org.gslab.context;

import io.netty.channel.Channel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.gslab.model.RequestId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 请求上下文管理器 - 替代ChannelRegistry
 *
 * 职责：
 * 1. 管理所有活跃请求的生命周期
 * 2. 自动超时清理（防止内存泄漏）
 * 3. 提供查询接口
 *
 * 线程安全：使用ConcurrentHashMap
 */
public class RequestManager {

    private static final Logger logger = LoggerFactory.getLogger(RequestManager.class);

    /** 活跃请求Map - requestId -> RequestContext */
    private final Map<RequestId, RequestContext> activeRequests = new ConcurrentHashMap<>();

    /**
     * 请求等待者Map - requestId -> waiter Channel
     * 独立于RequestContext，允许动态更新
     * 替代旧的ChannelRegistry.requestResultWaiters模式
     */
    private final Map<RequestId, Channel> requestWaiters = new ConcurrentHashMap<>();

    /** 超时清理定时器 */
    private final ScheduledExecutorService timeoutCleaner = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "RequestManager-TimeoutCleaner");
        t.setDaemon(true);
        return t;
    });

    public RequestManager() {
        // 启动定期清理任务（每10秒检查一次）
        timeoutCleaner.scheduleAtFixedRate(this::cleanupTimeoutRequests, 10, 10, TimeUnit.SECONDS);
        logger.atInfo().log("RequestManager initialized with periodic timeout cleanup (10s interval)");
    }

    // ==================== 请求生命周期 ====================

    /**
     * 注册新请求
     *
     * @param ctx RequestContext to register
     * @return 注册的RequestContext
     */
    public RequestContext registerRequest(RequestContext ctx) {
        // 注册到活跃请求Map
        activeRequests.put(ctx.requestId, ctx);

        logger.atDebug().addKeyValue("requestId", ctx.requestId).log("Registered request: {}", ctx);

        // 调度超时清理
        long timeoutMs = ctx.getTimeoutMs();
        timeoutCleaner.schedule(() -> handleTimeout(ctx.requestId), timeoutMs, TimeUnit.MILLISECONDS);

        return ctx;
    }

    /**
     * 完成请求（成功）
     */
    public void completeRequest(RequestId requestId, Map<String, Object> result) {
        RequestContext ctx = activeRequests.remove(requestId);
        requestWaiters.remove(requestId); // 同时清理waiter
        if (ctx != null) {
            ctx.complete(result);
            logger.atDebug().addKeyValue("requestId", requestId).log("Request completed: {}", requestId);
        } else {
            logger.atWarn()
                    .addKeyValue("requestId", requestId)
                    .log("Attempted to complete unknown request: {}", requestId);
        }
    }

    /**
     * 请求失败
     */
    public void failRequest(RequestId requestId, Throwable cause) {
        RequestContext ctx = activeRequests.remove(requestId);
        requestWaiters.remove(requestId); // 同时清理waiter
        if (ctx != null) {
            ctx.fail(cause);
            logger.atError().addKeyValue("requestId", requestId).log("Request {} failed: {}", requestId, cause);
        } else {
            logger.atWarn().addKeyValue("requestId", requestId).log("Attempted to fail unknown request: {}", requestId);
        }
    }

    /**
     * 处理请求超时
     */
    private void handleTimeout(RequestId requestId) {
        RequestContext ctx = activeRequests.remove(requestId);
        requestWaiters.remove(requestId); // 同时清理waiter
        if (ctx != null && !ctx.getResultFuture().isDone()) {
            ctx.timeout();
            logger.atWarn()
                    .addKeyValue("requestId", requestId)
                    .log("Request timeout: {} ({}ms)", requestId, ctx.getTimeoutMs());
        }
    }

    /**
     * 定期清理超时请求
     */
    private void cleanupTimeoutRequests() {
        long now = System.currentTimeMillis();
        int cleanedCount = 0;

        for (Map.Entry<RequestId, RequestContext> entry : activeRequests.entrySet()) {
            RequestContext ctx = entry.getValue();
            if (now > ctx.deadline && !ctx.getResultFuture().isDone()) {
                RequestId requestId = entry.getKey();
                activeRequests.remove(requestId);
                requestWaiters.remove(requestId); // 同时清理waiter
                ctx.timeout();
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            logger.atWarn().log("Cleaned up {} timeout requests", cleanedCount);
        }
    }

    // ==================== 查询接口 ====================

    /**
     * 获取请求上下文
     */
    public RequestContext getRequest(RequestId requestId) {
        return activeRequests.get(requestId);
    }

    /**
     * 检查请求是否存在
     */
    public boolean hasRequest(RequestId requestId) {
        return activeRequests.containsKey(requestId);
    }

    /**
     * 获取所有活跃请求ID
     */
    public Set<RequestId> getActiveRequestIds() {
        return activeRequests.keySet();
    }

    /**
     * 获取活跃请求数量
     */
    public int getActiveRequestCount() {
        return activeRequests.size();
    }

    // ==================== Channel管理接口 (替代ChannelRegistry) ====================

    /**
     * 注册请求等待者的Channel
     * 替代 ChannelRegistry.registerRequestWaiter()
     *
     * 这个方法总是覆盖已存在的waiter，确保最新的等待通道被使用
     */
    public void registerWaiter(RequestId requestId, Channel channel) {
        requestWaiters.put(requestId, channel);
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("channel", channel)
                .log("Registered waiter for request {}, channel: {}", requestId, channel);
    }

    /**
     * 获取请求等待者的Channel
     * 替代从ChannelRegistry.getRequestResultWaiterList()查询
     *
     * @return waiter channel, 如果不存在返回null
     */
    public Channel getWaiterChannel(RequestId requestId) {
        return requestWaiters.get(requestId);
    }

    /**
     * 移除请求等待者
     * 替代 ChannelRegistry.removeRequestResultWaiter()
     *
     * @return 被移除的channel，如果不存在返回null
     */
    public Channel removeWaiter(RequestId requestId) {
        Channel removed = requestWaiters.remove(requestId);
        if (removed != null) {
            logger.atDebug().addKeyValue("requestId", requestId).log("Removed waiter for request {}", requestId);
        }
        return removed;
    }

    /**
     * 获取所有请求等待者的Channel Map
     * 替代 ChannelRegistry.getRequestResultWaiterList()
     *
     * 现在直接返回独立的requestWaiters map，不再从RequestContext提取
     */
    public Map<RequestId, Channel> getAllRequestWaiters() {
        return new ConcurrentHashMap<>(requestWaiters);
    }

    /**
     * 获取所有上游Broker等待者的Channel Map
     * 替代 ChannelRegistry.getUpperBrokerRequestResultWaiterList()
     */
    public Map<RequestId, Channel> getAllUpperBrokerWaiters() {
        Map<RequestId, Channel> waiters = new ConcurrentHashMap<>();
        for (Map.Entry<RequestId, RequestContext> entry : activeRequests.entrySet()) {
            if (entry.getValue().upstreamBrokerChannel != null) {
                waiters.put(entry.getKey(), entry.getValue().upstreamBrokerChannel);
            }
        }
        return waiters;
    }

    /**
     * 获取请求的原始数据
     * 替代 ChannelRegistry.getOriginalRequestInfo()
     */
    public Map<String, Object> getOriginalRequestInfo(RequestId requestId) {
        RequestContext ctx = activeRequests.get(requestId);
        return ctx != null ? ctx.requestData : null;
    }

    /**
     * 移除请求（替代ChannelRegistry的remove操作）
     * 与completeRequest类似，但不设置结果
     */
    public RequestContext removeRequest(RequestId requestId) {
        return activeRequests.remove(requestId);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭RequestManager
     */
    public void shutdown() {
        logger.atInfo().log("Shutting down RequestManager. Active requests: {}", activeRequests.size());

        // 停止定时器
        timeoutCleaner.shutdown();
        try {
            if (!timeoutCleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutCleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutCleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 取消所有未完成的请求
        for (RequestContext ctx : activeRequests.values()) {
            if (!ctx.getResultFuture().isDone()) {
                ctx.fail(new IllegalStateException("RequestManager shutdown"));
            }
        }

        activeRequests.clear();
        logger.atInfo().log("RequestManager shutdown complete");
    }
}
