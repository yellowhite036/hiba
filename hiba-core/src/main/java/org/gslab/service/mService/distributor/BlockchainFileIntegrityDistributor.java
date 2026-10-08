package org.gslab.service.mService.distributor;

import io.netty.channel.Channel;
import java.util.Map;
import org.gslab.Distributor;
import org.gslab.configuration.ServiceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockchainFileIntegrityDistributor extends Distributor {

    private static final Logger logger = LoggerFactory.getLogger(BlockchainFileIntegrityDistributor.class);
    private ServiceConfiguration config;

    public BlockchainFileIntegrityDistributor() {
        logger.atDebug().log("BlockchainFileIntegrityDistributor initialized");
    }

    public BlockchainFileIntegrityDistributor(ServiceConfiguration config) {
        this.config = config;
        logger.atDebug().log("BlockchainFileIntegrityDistributor initialized with config");
    }

    @Override
    public Map<String, Object> mapping(
            String name, Map<String, Object> resource, Channel networkChannel, Map<String, Object> resourceInThisTier) {
        return Map.of();
    }

    @Override
    public Map<String, Object> mappingLocal(Map<String, Object> localResource, Map<String, Object> resourceInThisTier) {
        return Map.of();
    }

    @Override
    public void merge(Map<String, Map<String, Object>> allResult) {}

    @Override
    public Map<String, Object> getResult(Channel brokerChannel, Channel requestNodeChannel, Integer lowerBrokerPort) {
        return Map.of();
    }

    @Override
    public void handleTaskFail(String name, Map<String, Object> resource, Map<String, Object> Task) {}

    @Override
    public boolean hasMoreRound() {
        return false;
    }
}
