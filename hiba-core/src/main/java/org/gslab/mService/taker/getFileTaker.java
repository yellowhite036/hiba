package org.gslab.mService.taker;

import java.util.List;
import java.util.Map;
import org.gslab.Taker;
import org.gslab.configuration.ServiceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class getFileTaker extends Taker {

    private static final Logger logger = LoggerFactory.getLogger(getFileTaker.class);
    private ServiceConfiguration config;

    @Override
    @SuppressWarnings("unchecked")
    public boolean checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier) {
        boolean response = false;
        try {
            // 取得此層級的檔案資源
            List<String> availableFiles = (List<String>) resourceInThisTier.get("fileName");
            logger.atInfo().log("Available files are {}", availableFiles);

            // 取得請求的檔案名稱
            String requestedFileName = (String) getRequestInformation().get("fileName");
            logger.atInfo().log("Requested filename is {}", requestedFileName);

            if (availableFiles == null) {
                logger.atInfo().log("No available files , return false.");
                return false;
            }

            if (requestedFileName == null || requestedFileName.isEmpty()) {
                logger.atInfo().log("No requested fileName, return false.");
                return false;
            }

            logger.atInfo().log("Checking if broker can handle file request: {}", requestedFileName);
            logger.atInfo().log("Available files in this tier: {}", availableFiles);

            // 檢查是否有請求的檔案
            if (availableFiles.contains(requestedFileName)) {
                response = true;
            }

            if (response) {
                logger.atInfo().log("Can handle this file request, return true.");
            } else {
                logger.atInfo().log("Can't handle this file request, return false.");

                // 創建接收目錄
                String receiveTargetPath = (String) getRequestInformation().get("receiveTargetPath");
                if (receiveTargetPath != null) {
                    java.io.File requestDir = new java.io.File(receiveTargetPath);
                    requestDir.mkdirs();
                }
            }

        } catch (Exception e) {
            logger.atError().log("Error in fileResourceTaker: {}", e);
            response = false;
        }

        return response;
    }

    /**
     * Constructor with ServiceConfiguration
     */
    public getFileTaker(ServiceConfiguration config) {
        this.config = config;
        logger.atInfo().log("getFileTaker initialized with ServiceConfiguration: {}", config.getServiceName());
    }

    /**
     * Default constructor for backward compatibility
     */
    public getFileTaker() {
        logger.atInfo().log("getFileTaker initialized with default constructor");
    }

    /**
     * Set service configuration (for backward compatibility)
     */
    public void setServiceConfiguration(ServiceConfiguration config) {
        this.config = config;
        logger.atInfo().log("Service configuration set for getFileTaker: {}", config.getServiceName());
    }
}
