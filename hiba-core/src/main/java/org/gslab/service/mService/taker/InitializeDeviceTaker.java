package org.gslab.service.mService.taker;

import java.util.Map;
import org.gslab.Taker;

public class InitializeDeviceTaker extends Taker {

    @Override
    public boolean checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier) {

        return true;
    }
}
