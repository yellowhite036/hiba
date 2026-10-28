package org.gslab.mService.taker;

import java.util.Map;
import org.gslab.Taker;
import org.gslab.configuration.ServiceConfiguration;
import org.gslab.sdns.HiBAInitial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Taker for blockchain file integrity verification service.
 *
 * Determines if this node can handle file integrity verification requests.
 * Always returns true since verification is a local operation that:
 * - Uses local FileProtectionService
 * - Queries blockchain (network accessible from any node)
 * - Files are either uploaded or searched in local resource folder
 */
public class BlockchainFileIntegrityTaker extends Taker {

    private static final Logger logger = LoggerFactory.getLogger(BlockchainFileIntegrityTaker.class);
    private ServiceConfiguration config;

    public BlockchainFileIntegrityTaker() {
        logger.atDebug().log("BlockchainFileIntegrityTaker initialized");
    }

    public BlockchainFileIntegrityTaker(ServiceConfiguration config) {
        this.config = config;
        logger.atDebug().log("BlockchainFileIntegrityTaker initialized with config: {}", config.getServiceName());
    }

    @Override
    public boolean checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier) {
        // Check if FileProtectionService is available
        if (HiBAInitial.fileProtectionService == null) {
            logger.atWarn().log("FileProtectionService not available, cannot handle request");
            return false;
        }

        // File integrity verification can always be handled locally
        // - For uploaded files: file is already on this node
        // - For fileName search: search in local resource folder
        // - For fileHash query: blockchain query works from any node
        logger.atDebug().log("BlockchainFileIntegrity request can be handled locally");
        return true;
    }

    public void setServiceConfiguration(ServiceConfiguration config) {
        this.config = config;
    }
}
