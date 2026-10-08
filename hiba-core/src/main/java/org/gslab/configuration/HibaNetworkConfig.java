package org.gslab.configuration;

public class HibaNetworkConfig {

    private Boolean isAccountingServer;
    private String nodeName;
    private int hibaPort;
    private int dataPort;
    private int reverseDataPort;
    private int httpRequestPort;
    private Boolean canReverseChannel;
    private int multicastPort;
    private String multicastIp;
    private String accountingServerIP;
    private Boolean canAutoGetNetworkInterface;
    private String multicastInterfaceName;
    private String fileTransferDirectory;
    private String fileUploadDirectory;
    private String sslDirectory;
    private Boolean canEvaluateDevicePerf;
    private Boolean isMandatoryBroker;

    private String pubKey;
    private final String sysName;

    // TODO - below names need better naming
    private Boolean offlineBrokerScan;
    private String interfaceIP; // interfaceAddress
    private Boolean tpmSignatureVerification;
    private Boolean blockchainFileProtect;

    public HibaNetworkConfig() {
        this.sysName = System.getProperty("user.name");
    }

    public Boolean isAccountingServer() {
        return isAccountingServer;
    }

    public void setAccountingServer(Boolean accountingServer) {
        isAccountingServer = accountingServer;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public int getHibaPort() {
        return hibaPort;
    }

    public void setHibaPort(int hibaPort) {
        this.hibaPort = hibaPort;
    }

    public int getDataPort() {
        return dataPort;
    }

    public void setDataPort(int dataPort) {
        this.dataPort = dataPort;
    }

    public int getReverseDataPort() {
        return reverseDataPort;
    }

    public void setReverseDataPort(int reverseDataPort) {
        this.reverseDataPort = reverseDataPort;
    }

    public int getHttpRequestPort() {
        return httpRequestPort;
    }

    public void setHttpRequestPort(int httpRequestPort) {
        this.httpRequestPort = httpRequestPort;
    }

    public Boolean getCanReverseChannel() {
        return canReverseChannel;
    }

    public void setCanReverseChannel(Boolean canReverseChannel) {
        this.canReverseChannel = canReverseChannel;
    }

    public int getMulticastPort() {
        return multicastPort;
    }

    public void setMulticastPort(int multicastPort) {
        this.multicastPort = multicastPort;
    }

    public String getMulticastIp() {
        return multicastIp;
    }

    public void setMulticastIp(String multicastIp) {
        this.multicastIp = multicastIp;
    }

    public String getAccountingServerIP() {
        return accountingServerIP;
    }

    public void setAccountingServerIP(String accountingServerIP) {
        this.accountingServerIP = accountingServerIP;
    }

    public Boolean getCanAutoGetNetworkInterface() {
        return canAutoGetNetworkInterface;
    }

    public void setCanAutoGetNetworkInterface(Boolean canAutoGetNetworkInterface) {
        this.canAutoGetNetworkInterface = canAutoGetNetworkInterface;
    }

    public String getMulticastInterfaceName() {
        return multicastInterfaceName;
    }

    public void setMulticastInterfaceName(String multicastInterfaceName) {
        this.multicastInterfaceName = multicastInterfaceName;
    }

    public String getFileTransferDirectory() {
        return fileTransferDirectory;
    }

    public void setFileTransferDirectory(String fileTransferDirectory) {
        this.fileTransferDirectory = fileTransferDirectory;
    }

    public String getFileUploadDirectory() {
        return fileUploadDirectory;
    }

    public void setFileUploadDirectory(String fileUploadDirectory) {
        this.fileUploadDirectory = fileUploadDirectory;
    }

    public String getSslDirectory() {
        return sslDirectory;
    }

    public void setSslDirectory(String sslDirectory) {
        this.sslDirectory = sslDirectory;
    }

    public Boolean getCanEvaluateDevicePerf() {
        return canEvaluateDevicePerf;
    }

    public void setCanEvaluateDevicePerf(Boolean canEvaluateDevicePerf) {
        this.canEvaluateDevicePerf = canEvaluateDevicePerf;
    }

    public Boolean getMandatoryBroker() {
        return isMandatoryBroker;
    }

    public void setMandatoryBroker(Boolean mandatoryBroker) {
        isMandatoryBroker = mandatoryBroker;
    }

    public Boolean getOfflineBrokerScan() {
        return offlineBrokerScan;
    }

    public void setOfflineBrokerScan(Boolean offlineBrokerScan) {
        this.offlineBrokerScan = offlineBrokerScan;
    }

    public String getInterfaceIP() {
        return interfaceIP;
    }

    public void setInterfaceIP(String interfaceIP) {
        this.interfaceIP = interfaceIP;
    }

    public String getPubKey() {
        return pubKey;
    }

    public void setPubKey(String pubKey) {
        this.pubKey = pubKey;
    }

    public String getSysName() {
        return sysName;
    }

    public Boolean getTpmSignatureVerification() {
        return tpmSignatureVerification;
    }

    public void setTpmSignatureVerification(Boolean tpmSignatureVerification) {
        this.tpmSignatureVerification = tpmSignatureVerification;
    }

    public Boolean getBlockchainFileProtect() {
        return blockchainFileProtect;
    }

    public void setBlockchainFileProtect(Boolean blockchainFileProtect) {
        this.blockchainFileProtect = blockchainFileProtect;
    }
}
