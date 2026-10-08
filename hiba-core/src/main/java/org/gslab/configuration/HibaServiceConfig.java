package org.gslab.configuration;

public class HibaServiceConfig {

    private String requestListenerServiceDirectory;
    private Boolean updateDeviceInfo;
    private int updateDeviceInfoFrequency;

    public String getRequestListenerServiceDirectory() {
        return requestListenerServiceDirectory;
    }

    public void setRequestListenerServiceDirectory(String requestListenerServiceDirectory) {
        this.requestListenerServiceDirectory = requestListenerServiceDirectory;
    }

    public Boolean getUpdateDeviceInfo() {
        return updateDeviceInfo;
    }

    public void setUpdateDeviceInfo(Boolean updateDeviceInfo) {
        this.updateDeviceInfo = updateDeviceInfo;
    }

    public int getUpdateDeviceInfoFrequency() {
        return updateDeviceInfoFrequency;
    }

    public void setUpdateDeviceInfoFrequency(int updateDeviceInfoFrequency) {
        this.updateDeviceInfoFrequency = updateDeviceInfoFrequency;
    }
}
