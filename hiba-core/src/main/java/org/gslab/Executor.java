package org.gslab;

import io.netty.channel.Channel;
import java.util.Map;

public abstract class Executor {

    private Map<String, Object> taskInformation;
    private int tier = 0;

    public Executor() {}

    public void setTier(int tier) {
        this.tier = tier;
    }

    public int getTier() {
        return tier;
    }

    public void setTaskInformation(Map<String, Object> taskInformation) {
        this.taskInformation = taskInformation;
    }

    public Map<String, Object> getTaskInformation() {
        return taskInformation;
    }

    public Executor getNewInstance() {
        return this;
    }

    public abstract Map<String, Object> execute(Map<String, Object> information, Channel brokerChannel);

    public abstract Map<String, Object> brokerExecute(Map<String, Object> information);
}
