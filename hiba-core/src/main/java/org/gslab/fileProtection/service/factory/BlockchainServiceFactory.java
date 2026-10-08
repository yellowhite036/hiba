package org.gslab.fileProtection.service.factory;

import lombok.extern.slf4j.Slf4j;
import org.gslab.fileProtection.config.BlockchainFileProtectManagerConfig;
import org.gslab.fileProtection.config.BlockchainServiceConfig;
import org.gslab.fileProtection.config.BlockchainServiceConfig.ServiceType;
import org.gslab.fileProtection.service.BlockchainService;
import org.gslab.fileProtection.service.impl.MockBlockchainServiceImpl;
import org.gslab.fileProtection.service.impl.Web3jBlockchainServiceImpl;
import org.gslab.fileProtection.util.ConfigUtil;

/**
 * Factory for creating blockchain service instances
 */
@Slf4j
public class BlockchainServiceFactory {

    /**
     * Create blockchain service based on type with configuration
     */
    public static BlockchainService createService(ServiceType type, BlockchainServiceConfig config) {
        try {
            switch (type) {
                case MOCK:
                    log.info("Creating Mock blockchain service");
                    if (config != null && config.isMockSimulateFailures()) {
                        return MockBlockchainServiceImpl.createInstanceWithFailures();
                    }
                    return MockBlockchainServiceImpl.createInstanceWithoutFailures();

                case WEB3J:
                    log.info("Creating Web3j blockchain service");
                    if (config == null) {
                        throw new IllegalArgumentException("BlockchainServiceConfig is required for Web3j service");
                    }
                    return new Web3jBlockchainServiceImpl(config);

                default:
                    throw new IllegalArgumentException("Unsupported service type: " + type);
            }
        } catch (Exception e) {
            log.error("Failed to create blockchain service of type: {}", type, e);
            throw new RuntimeException("Failed to create blockchain service: " + e.getMessage(), e);
        }
    }

    /**
     * Create blockchain service based on type
     */
    public static BlockchainService createService(ServiceType type) {
        return createService(type, null);
    }

    /**
     * Create service from unified configuration
     */
    public static BlockchainService createFromConfig(BlockchainFileProtectManagerConfig config) {
        BlockchainServiceConfig serviceConfig = config.getBlockchainServiceConfig();
        return createService(serviceConfig.getType(), serviceConfig);
    }

    /**
     * Create service from system properties or environment variables
     */
    public static BlockchainService createFromEnvironment() {
        // Use ConfigUtil to get service type
        String serviceType = ConfigUtil.getBlockchainServiceType();
        BlockchainServiceConfig config = ConfigUtil.createBlockchainServiceConfig();

        return createService(ServiceType.fromString(serviceType), config);
    }
}
