package org.gslab.service.mService.taker;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import org.gslab.Taker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class resourceCheckTaker extends Taker {

    private static final Logger logger = LoggerFactory.getLogger(resourceCheckTaker.class);
    // private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    /*
     * The broker checks the resource list to confirm whether it can handle.
     * If broker can't handle, return false. and it has upper layer broker, send to upper layer.
     */

    @Override
    @SuppressWarnings("unchecked")
    public boolean checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier) {
        boolean response = false;
        try {
            // node has resource in this tier
            // System.out.println("resourceInThisTier: " + resourceInThisTier);
            ArrayList<String> hasRoadType = (ArrayList<String>) resourceInThisTier.get("roadType");
            ArrayList<ArrayList<String>> hasCategory =
                    (ArrayList<ArrayList<String>>) resourceInThisTier.get("recognitionCategory");

            // required by request
            ArrayList<String> requestRoadType =
                    (ArrayList<String>) getRequestInformation().get("roadType");
            ArrayList<ArrayList<String>> requestCategory =
                    (ArrayList<ArrayList<String>>) getRequestInformation().get("recognitionCategory");

            // LOGGER.info("broker has resource, roadType -> " + hasRoadType + "  recognition category -> " +
            // hasCategory);
            // LOGGER.info("request requirements, roadType -> " + requestRoadType + "
            // recognition category -> " + requestCategory);

            if (hasRoadType.containsAll(requestRoadType) && hasCategory.containsAll(requestCategory)) {
                logger.atInfo().log("Broker can handle this task, return true.");
                response = true;
            } else {
                String receiveTargetPath = (String) getRequestInformation().get("receiveTargetPath");
                File requestDir = new File(receiveTargetPath);
                requestDir.mkdir();
                logger.atInfo().log("Broker can't handle, return false.");
                response = false;
            }

        } catch (Exception e) {
            logger.atInfo().log("Exception: {}", e.getMessage());
            response = false;
        }
        return response;
    }
}
