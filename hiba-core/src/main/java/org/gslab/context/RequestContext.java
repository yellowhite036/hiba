package org.gslab.context;

import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.gslab.model.RequestId;

/**
 * 统一的请求上下文 - 替代3个独立Map + MDC ThreadLocal
 *
 * 这个类拥有一个请求从创建到完成的所有状态。
 * 生命周期简单明确：create -> route -> execute -> complete -> destroy
 *
 * 线程安全：
 * - 所有final字段在构造时初始化，之后不可变
 * - 状态转换使用AtomicReference保证原子性
 * - resultFuture内部保证线程安全
 */
public final class RequestContext {

    // ==================== 核心标识 ====================

    /** 系统唯一标识 - UUID4，永远不为null */
    public final RequestId requestId;

    /** 业务名称 - 用于日志和监控 */
    public final String requestName;

    /** 服务名称 */
    public final String serviceName;

    // ==================== 路由信息 ====================

    /** 发起请求的channel - 结果要回传到这里 */
    public final Channel originChannel;

    /** 上游broker channel - null表示这是leaf节点 */
    public final Channel upstreamBrokerChannel;

    /** Broker路由路径 - 用于结果回传 */
    public final List<String> brokerRoute;

    // ==================== 请求数据 ====================

    /** 原始请求数据 - 不可变视图 */
    public final Map<String, Object> requestData;

    // ==================== 时间信息 ====================

    /** 创建时间戳 */
    public final long createTime;

    /** 截止时间 */
    public final long deadline;

    /** 加入时间 */
    public final long joinTime;

    // ==================== 状态管理 ====================

    /** 当前状态 - 使用AtomicReference保证原子状态转换 */
    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);

    /** 结果Future - 异步等待结果 */
    private final CompletableFuture<Map<String, Object>> resultFuture = new CompletableFuture<>();

    public enum State {
        CREATED, // 刚创建，还未开始处理
        ROUTING, // 正在路由到broker
        EXECUTING, // 正在执行任务
        COMPLETED, // 已完成（成功）
        FAILED, // 失败
        TIMEOUT // 超时
    }

    // ==================== 构造函数 ====================

    /** 私有构造函数 - 强制使用Builder */
    private RequestContext(Builder builder) {
        this.requestId = builder.requestId;
        this.requestName = builder.requestName;
        this.serviceName = builder.serviceName;
        this.originChannel = builder.originChannel;
        this.upstreamBrokerChannel = builder.upstreamBrokerChannel;
        this.brokerRoute = builder.brokerRoute != null ? List.copyOf(builder.brokerRoute) : List.of();
        this.requestData = builder.requestData != null ? Map.copyOf(builder.requestData) : Map.of();
        this.createTime = builder.createTime;
        this.deadline = builder.deadline;
        this.joinTime = builder.joinTime;
    }

    // ==================== 状态转换 ====================

    /**
     * 尝试状态转换 - CAS操作，保证原子性
     *
     * @return true if transition succeeded, false otherwise
     */
    public boolean tryTransition(State expectedCurrent, State newState) {
        return state.compareAndSet(expectedCurrent, newState);
    }

    /**
     * 强制设置状态（用于错误恢复场景）
     */
    public void forceState(State newState) {
        state.set(newState);
    }

    public State getState() {
        return state.get();
    }

    // ==================== 结果处理 ====================

    /**
     * 完成请求（成功）
     */
    public void complete(Map<String, Object> result) {
        if (tryTransition(State.EXECUTING, State.COMPLETED)) {
            resultFuture.complete(result);
        } else {
            // 状态转换失败 - 可能已经完成或失败
            State currentState = state.get();
            if (currentState != State.COMPLETED && currentState != State.FAILED) {
                // 强制完成
                forceState(State.COMPLETED);
                resultFuture.complete(result);
            }
        }
    }

    /**
     * 请求失败
     */
    public void fail(Throwable cause) {
        forceState(State.FAILED);
        resultFuture.completeExceptionally(cause);
    }

    /**
     * 请求超时
     */
    public void timeout() {
        forceState(State.TIMEOUT);
        resultFuture.completeExceptionally(new TimeoutException("Request timeout: " + requestId));
    }

    /**
     * 获取结果Future - 用于异步等待
     */
    public CompletableFuture<Map<String, Object>> getResultFuture() {
        return resultFuture;
    }

    /**
     * 判断是否是broker节点
     */
    public boolean isBrokerNode() {
        return upstreamBrokerChannel != null;
    }

    /**
     * 获取超时时长（毫秒）
     */
    public long getTimeoutMs() {
        return deadline - createTime;
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private RequestId requestId;
        private String requestName;
        private String serviceName;
        private Channel originChannel;
        private Channel upstreamBrokerChannel;
        private List<String> brokerRoute;
        private Map<String, Object> requestData;
        private long createTime = System.currentTimeMillis();
        private long deadline;
        private long joinTime = System.currentTimeMillis();

        public Builder requestId(RequestId requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder requestName(String requestName) {
            this.requestName = requestName;
            return this;
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder originChannel(Channel channel) {
            this.originChannel = channel;
            return this;
        }

        public Builder upstreamBrokerChannel(Channel channel) {
            this.upstreamBrokerChannel = channel;
            return this;
        }

        public Builder brokerRoute(List<String> route) {
            this.brokerRoute = route != null ? new ArrayList<>(route) : null;
            return this;
        }

        public Builder requestData(Map<String, Object> data) {
            this.requestData = data != null ? new HashMap<>(data) : null;
            return this;
        }

        public Builder createTime(long time) {
            this.createTime = time;
            return this;
        }

        public Builder deadline(long deadline) {
            this.deadline = deadline;
            return this;
        }

        public Builder joinTime(long time) {
            this.joinTime = time;
            return this;
        }

        public RequestContext build() {
            // 验证必填字段
            if (requestId == null) {
                throw new IllegalArgumentException("requestId is required and cannot be null");
            }
            if (requestName == null || requestName.isEmpty()) {
                throw new IllegalArgumentException("requestName is required");
            }
            if (serviceName == null || serviceName.isEmpty()) {
                throw new IllegalArgumentException("serviceName is required");
            }
            if (originChannel == null) {
                throw new IllegalArgumentException("originChannel is required");
            }
            if (deadline == 0) {
                // 默认超时：60秒
                this.deadline = createTime + 60_000;
            }

            return new RequestContext(this);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "RequestContext{id=%s, name=%s, service=%s, state=%s, isBroker=%s}",
                requestId, requestName, serviceName, state.get(), isBrokerNode());
    }
}
