package org.gslab.configuration;

import java.io.File;
import java.math.BigInteger;
import org.gslab.util.JsonFileUtil;
import org.gslab.fileProtection.config.BlockchainFileProtectManagerConfig;
import org.gslab.fileProtection.config.BlockchainServiceConfig;
import org.gslab.fileProtection.config.DatabaseConfig;
import org.gslab.fileProtection.config.FileProtectionConfig;
import org.gslab.fileProtection.config.RetryConfig;
import org.gslab.sdns.HiBAInitial;
import org.json.JSONObject;

public class SystemConfigurationLoader {

    private final JSONObject json;

    /**
     * Constructor to load the system configuration from the specified env.json file.
     *
     * @param envFile the path to the env.json file
     */
    public SystemConfigurationLoader(String envFile) {
        // Constructor can be used for initialization if needed
        File serviceFile = new File(envFile);

        if (!serviceFile.exists()) {
            throw new IllegalStateException("System configuration error occurred. env.json file could not be found");
        }

        this.json = JsonFileUtil.ReadFile(serviceFile);

        if (json == null) {
            throw new IllegalStateException("System configuration error occurred. env.json file is empty");
        }
    }

    public HibaNetworkConfig loadNetworkConfig() throws IllegalStateException {
        // read env.json and capture the node of HiBANetwork and read all key value pairs and assign them to properties
        HibaNetworkConfig hibaNetworkConfig = new HibaNetworkConfig();

        JSONObject json = this.json.optJSONObject("HiBANetwork");

        if (json == null) {
            throw new IllegalStateException(
                    "System configuration error occurred. Field HiBANetwork could not be found in env.json file");
        }

        // read json object and assign properties
        hibaNetworkConfig.setAccountingServer(json.optBoolean("isAccountingServer", false));
        hibaNetworkConfig.setCanAutoGetNetworkInterface(json.optBoolean("autoGetNetworkInterface", false));
        hibaNetworkConfig.setDataPort(json.optInt("DATA_PORT", 8088));
        hibaNetworkConfig.setCanEvaluateDevicePerf(json.optBoolean("devicePerformanceEvaluation", false));
        hibaNetworkConfig.setFileTransferDirectory(json.optString("FileTransferDirectory", "receiveFile"));
        hibaNetworkConfig.setFileUploadDirectory(json.optString("FileUploadDirectory", "uploadFile"));
        hibaNetworkConfig.setHibaPort(json.optInt("HiBA_PORT", 8091));
        hibaNetworkConfig.setMandatoryBroker(json.optBoolean("mandatoryBroker", false));
        hibaNetworkConfig.setMulticastIp(json.optString("multicastIP", "230.0.0.0"));
        hibaNetworkConfig.setMulticastPort(json.optInt("multicastPort", 4321));
        hibaNetworkConfig.setNodeName(json.optString("nodeName", ""));
        hibaNetworkConfig.setOfflineBrokerScan(json.optBoolean("offlineBrokerScan", false));
        hibaNetworkConfig.setReverseDataPort(json.optInt("REVERSE_DATA_PORT", 8089));
        hibaNetworkConfig.setCanReverseChannel(json.optBoolean("reverseChannel", true));
        hibaNetworkConfig.setSslDirectory(json.optString("SSL", ""));
        hibaNetworkConfig.setAccountingServerIP(json.optString("accountingServer", ""));
        hibaNetworkConfig.setMulticastInterfaceName(json.optString("multicastInterfaceName", ""));
        hibaNetworkConfig.setInterfaceIP(json.optString("interfaceAddress", ""));
        hibaNetworkConfig.setTpmSignatureVerification(json.optBoolean("tpmSignatureVerification", true));
        hibaNetworkConfig.setBlockchainFileProtect(json.optBoolean("BlockchainFileProtect", true));

        // Verify accounting server is false, then verify accounting server ip is not empty string
        if (!hibaNetworkConfig.isAccountingServer()
                && hibaNetworkConfig.getAccountingServerIP().isEmpty()) {
            throw new IllegalStateException(
                    "System configuration error occurred. This node is not an accounting server, accounting server ip cannot be an empty string");
        }

        // Verify can auto get network interface is false, then verify multicast interface name and interface ip is not
        // empty string
        if (!hibaNetworkConfig.getCanAutoGetNetworkInterface()
                && (hibaNetworkConfig.getMulticastInterfaceName().isEmpty()
                        || hibaNetworkConfig.getInterfaceIP().isEmpty())) {
            throw new IllegalStateException(
                    "System configuration error occurred. This node cannot auto get network interface, multicast interface name and interface ip cannot be empty strings");
        }

        // Verify accounting server ip , node name and interface ip. they cannot be empty strings; if they are empty,
        // throw exception
        if (hibaNetworkConfig.getNodeName().isEmpty()) {
            throw new IllegalStateException("System configuration error occurred. Node name cannot be empty strings");
        }

        if (hibaNetworkConfig.getCanReverseChannel() && !hibaNetworkConfig.isAccountingServer()) {
            hibaNetworkConfig.setPubKey(HiBAInitial.getSSHPubKey(hibaNetworkConfig.getSysName()));
        }

        return hibaNetworkConfig;
    }

    public HibaServiceConfig loadServiceConfig() {
        HibaServiceConfig hibaServiceConfig = new HibaServiceConfig();
        JSONObject json = this.json.optJSONObject("HiBAService");

        if (json == null) {
            throw new IllegalStateException(
                    "System configuration error occurred. HiBAService could not be found in env.json file");
        }

        hibaServiceConfig.setRequestListenerServiceDirectory(json.optString("requestListenerServiceDirectory", ""));
        hibaServiceConfig.setUpdateDeviceInfo(json.optBoolean("updateDeviceInfo", false));
        hibaServiceConfig.setUpdateDeviceInfoFrequency(json.optInt("updateDeviceInfoFrequency", 60));

        if (hibaServiceConfig.getRequestListenerServiceDirectory().isEmpty()) {
            throw new IllegalStateException(
                    "System configuration error occurred. Request listener service directory cannot be an empty string");
        }

        return hibaServiceConfig;
    }

    public MulticastConfig loadMulticastConfig() {
        MulticastConfig multicastConfig = new MulticastConfig();
        JSONObject json = this.json.optJSONObject("multicastController");

        if (json == null) {
            throw new IllegalStateException(
                    "System configuration error occurred. multicastController could not be found in env.json file");
        }

        /*
        The abbreviation for Multicast is MCAST.
        The Frequency of broadcasts in time.
        The time interval for MCAST is in milliseconds.
        */

        multicastConfig.setInitMCASTFrequency(json.optInt("initMCASTFrequency", 5));
        multicastConfig.setInitMCASTTimeInterval(json.optInt("initMCASTTimeInterval", 500));
        multicastConfig.setHighestScoreMCASTFrequency(json.optInt("highestScoreMCASTFrequency", 5));
        multicastConfig.setHighestScoreMCASTTimeInterval(json.optInt("highestScoreMCASTTimeInterval", 5));
        multicastConfig.setIsBrokerMCASTFrequency(json.optInt("isBrokerMCASTFrequency", 5));
        multicastConfig.setIsBrokerMCASTTimeInterval(json.optInt("isBrokerMCASTTimeInterval", 500));

        return multicastConfig;
    }

    public MobileNetworkConfig loadMobileNetworkConfig() {
        MobileNetworkConfig mobileNetworkConfig = new MobileNetworkConfig();
        JSONObject json = this.json.optJSONObject("MobileNetwork");

        if (json == null) {
            throw new IllegalStateException(
                    "System configuration error occurred. MobileNetwork could not be found in env.json file");
        }

        mobileNetworkConfig.setState(json.optBoolean("state", false));
        mobileNetworkConfig.setConnectionSignalQuality(json.optInt("connectionSignalQuality", 0));
        mobileNetworkConfig.setDisconnectSignalQuality(json.optInt("disconnectSignalQuality", 0));
        mobileNetworkConfig.setWifiInterface(json.optString("wifiInterface", ""));
        mobileNetworkConfig.setObu(json.optBoolean("OBU", false));
        mobileNetworkConfig.setObuRouterInterface(json.optString("OBURouterInterface", ""));
        mobileNetworkConfig.setObuRouterAddress(json.optString("OBURouterAddress", ""));
        mobileNetworkConfig.setObuRouterGateway(json.optString("OBURouterGateway", ""));
        mobileNetworkConfig.setObuMulticastPort(json.optInt("mobileNetworkInterface", 4321));

        // verify state is true, then verify wifi interface is not empty string
        if (mobileNetworkConfig.getState()
                && mobileNetworkConfig.getWifiInterface().isEmpty()) {
            throw new IllegalStateException(
                    "System configuration error occurred. The state of mobile network is false, wifi interface cannot be an empty string");
        }

        // verify obu is true, then verify obu router interface, obu router address and obu router gateway is not empty
        // string
        if (mobileNetworkConfig.getObu()
                && (mobileNetworkConfig.getObuRouterInterface().isEmpty()
                        || mobileNetworkConfig.getObuRouterAddress().isEmpty()
                        || mobileNetworkConfig.getObuRouterGateway().isEmpty())) {
            throw new IllegalStateException(
                    "System configuration error occurred. The OBU is false, obu router interface, obu router address and obu router gateway cannot be empty strings");
        }

        return mobileNetworkConfig;
    }

    public BlockchainFileProtectManagerConfig loadBlockchainFileProtectManagerConfig() {
        var blockchainJson = this.json.optJSONObject("Blockchain");
        var fileProtectJson = this.json.optJSONObject("BlockchainFileProtect");

        if (blockchainJson == null) {
            throw new IllegalStateException(
                    "System configuration error occurred. Blockchain could not be found in env.json file");
        }

        if (fileProtectJson == null) {
            throw new IllegalStateException(
                    "System configuration error occurred. BlockchainFileProtect could not be found in env.json file");
        }

        String rpcURL = blockchainJson.optString("rpcURL", "http://127.0.0.1:8545");
        String networkID = blockchainJson.optString("networkID", "1337");
        long gasPrice = blockchainJson.optLong("gasPrice", 20_000_000_000L);
        long gasLimit = blockchainJson.optLong("gasLimit", 6_721_975L);
        int pollingIntervalMs = blockchainJson.optInt("pollingIntervalMs", 5000);
        int pollingAttempts = blockchainJson.optInt("pollingAttempts", 20);

        String serviceType = fileProtectJson.optString("serviceType", "web3j");
        String databaseURL = fileProtectJson.optString("databaseURL", "jdbc:sqlite:blockchain_manager.db");
        String databaseUserName = fileProtectJson.optString("databaseUserName", "root");
        String databasePassword = fileProtectJson.optString("databasePassword", "password");
        String contractAddress = fileProtectJson.optString("contractAddress", "");
        String privateKey = fileProtectJson.optString("privateKey", "");

        boolean enableDatabaseRetry = fileProtectJson.optBoolean("enableDatabaseRetry", false);
        int retryMaxAttempts = fileProtectJson.optInt("retryMaxAttempts", 3);
        int retryBaseDelayMs = fileProtectJson.optInt("retryBaseDelayMs", 500);
        int retryAsyncAttempts = fileProtectJson.optInt("retryAsyncAttempts", 5);
        int retryAsyncBaseDelayMs = fileProtectJson.optInt("retryAsyncBaseDelayMs", 1000);

        String envPrivateKey = getEnv("BLOCKCHAIN_PRIVATE_KEY");
        if (envPrivateKey != null) {
            privateKey = envPrivateKey;
        }

        String envContractAddress = getEnv("BLOCKCHAIN_CONTRACT_ADDRESS");
        if (envContractAddress != null) {
            contractAddress = envContractAddress;
        }

        String envRpcUrl = getEnv("BLOCKCHAIN_RPC_URL");
        if (envRpcUrl != null) {
            rpcURL = envRpcUrl;
        }

        String envNetworkId = getEnv("BLOCKCHAIN_NETWORK_ID");
        if (envNetworkId != null) {
            networkID = envNetworkId;
        }

        if (privateKey.isEmpty()) {
            throw new IllegalStateException(
                    "System configuration error occurred. privateKey cannot be an empty string");
        }

        if (contractAddress.isEmpty()) {
            throw new IllegalStateException(
                    "System configuration error occurred. contractAddress cannot be an empty string");
        }

        DatabaseConfig databaseConfig = new DatabaseConfig(databaseURL, databaseUserName, databasePassword);
        BlockchainServiceConfig blockchainServiceConfig = new BlockchainServiceConfig();
        blockchainServiceConfig.setType(BlockchainServiceConfig.ServiceType.fromString(serviceType));
        blockchainServiceConfig.setRpcUrl(rpcURL);
        blockchainServiceConfig.setNetworkId(networkID);
        blockchainServiceConfig.setPrivateKey(privateKey);
        blockchainServiceConfig.setContractAddress(contractAddress);
        blockchainServiceConfig.setGasPrice(gasPrice);
        blockchainServiceConfig.setGasLimit(gasLimit);
        blockchainServiceConfig.setPollingInterval(pollingIntervalMs);
        blockchainServiceConfig.setPollingAttempts(pollingAttempts);

        FileProtectionConfig fileProtectionConfig = new FileProtectionConfig();
        fileProtectionConfig.setEnableDatabaseRetry(enableDatabaseRetry);

        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setMaxAttempts(retryMaxAttempts);
        retryConfig.setBaseDelayMs(retryBaseDelayMs);
        retryConfig.setAsyncMaxAttempts(retryAsyncAttempts);
        retryConfig.setAsyncBaseDelayMs(retryAsyncBaseDelayMs);

        return BlockchainFileProtectManagerConfig.builder()
                .withDatabaseConfig(databaseConfig)
                .withBlockchainServiceConfig(blockchainServiceConfig)
                .withFileProtectionConfig(fileProtectionConfig)
                .withRetryConfig(retryConfig)
                .build();
    }

    public TPMDeviceRegistryConfig loadTPMDeviceRegistryConfig() {
        var blockchainJson = this.json.optJSONObject("Blockchain");
        var tpmJson = this.json.optJSONObject("BlockchainTPMRegistry");

        if (blockchainJson == null) {
            throw new IllegalStateException(
                    "System configuration error occurred. Blockchain could not be found in env.json file");
        }

        if (tpmJson == null) {
            throw new IllegalStateException(
                    "System configuration error occurred. BlockchainTPMRegistry could not be found in env.json file");
        }

        String rpcUrl = blockchainJson.optString("rpcURL", "http://localhost:8545");
        String networkId = blockchainJson.optString("networkID", "1337");
        long gasPrice = blockchainJson.optLong("gasPrice", 20_000_000_000L);
        long gasLimit = blockchainJson.optLong("gasLimit", 500_000L);
        int pollingIntervalMs = blockchainJson.optInt("pollingIntervalMs", 1500);
        int pollingAttempts = blockchainJson.optInt("pollingAttempts", 40);

        String contractAddress = tpmJson.optString("contractAddress", "");
        String privateKey = tpmJson.optString("privateKey", "");

        String envPrivateKey = getEnv("TPM_DEVICE_REGISTRY_PRIVATE_KEY");
        if (envPrivateKey != null) {
            privateKey = envPrivateKey;
        }

        String envContractAddress = getEnv("TPM_DEVICE_REGISTRY_CONTRACT_ADDRESS");
        if (envContractAddress != null) {
            contractAddress = envContractAddress;
        }

        String envRpcUrl = getEnv("BLOCKCHAIN_RPC_URL");
        if (envRpcUrl != null) {
            rpcUrl = envRpcUrl;
        }

        String envChainId = getEnv("BLOCKCHAIN_CHAIN_ID");
        if (envChainId != null) {
            networkId = envChainId;
        }

        String envGasPrice = getEnv("BLOCKCHAIN_GAS_PRICE");
        if (envGasPrice != null) {
            gasPrice = Long.parseLong(envGasPrice);
        }

        String envGasLimit = getEnv("BLOCKCHAIN_GAS_LIMIT");
        if (envGasLimit != null) {
            gasLimit = Long.parseLong(envGasLimit);
        }

        String envNetworkName = getEnv("BLOCKCHAIN_NETWORK_NAME");

        long chainId = parseLongOrDefault(networkId, 1337L);

        TPMDeviceRegistryConfig config = TPMDeviceRegistryConfig.builder()
                .rpcUrl(rpcUrl)
                .contractAddress(contractAddress)
                .privateKey(privateKey)
                .chainId(chainId)
                .gasPrice(BigInteger.valueOf(gasPrice))
                .gasLimit(BigInteger.valueOf(gasLimit))
                .pollingIntervalMs(pollingIntervalMs)
                .pollingAttempts(pollingAttempts)
                .networkName(envNetworkName != null ? envNetworkName : "localhost")
                .build();

        config.logConfig();
        return config;
    }

    private static String getEnv(String key) {
        String value = System.getenv(key);
        return (value != null && !value.trim().isEmpty()) ? value : null;
    }

    private static long parseLongOrDefault(String value, long defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
