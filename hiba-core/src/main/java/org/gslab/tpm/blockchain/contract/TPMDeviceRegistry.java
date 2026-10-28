package org.gslab.tpm.blockchain.contract;

import io.reactivex.Flowable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.BaseEventResponse;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Contract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

/**
 * TPMDeviceRegistry 智慧合約的 Java Wrapper
 * <p>
 * 此類別由 Web3j 風格手動建立，提供與 Solidity 合約互動的方法。
 * 在實際專案中，可使用 web3j generate 命令從 ABI 自動生成。
 * </p>
 */
@SuppressWarnings("rawtypes")
public class TPMDeviceRegistry extends Contract {

    // 合約 Binary（部署時使用，這裡省略實際內容）
    public static final String BINARY = ""; // 從編譯後的合約取得

    // ============ 事件定義 ============

    public static final Event DEVICE_REGISTERED_EVENT = new Event(
            "DeviceRegistered",
            Arrays.asList(
                    new TypeReference<Utf8String>(true) {}, // indexed ekFingerprintHash
                    new TypeReference<Utf8String>() {}, // ekFingerprint
                    new TypeReference<Uint256>() {} // timestamp
                    ));

    public static final Event DEVICE_REVOKED_EVENT = new Event(
            "DeviceRevoked",
            Arrays.asList(
                    new TypeReference<Utf8String>(true) {}, // indexed ekFingerprintHash
                    new TypeReference<Utf8String>() {}, // ekFingerprint
                    new TypeReference<Uint256>() {} // timestamp
                    ));

    public static final Event DEVICE_REACTIVATED_EVENT = new Event(
            "DeviceReactivated",
            Arrays.asList(
                    new TypeReference<Utf8String>(true) {}, // indexed ekFingerprintHash
                    new TypeReference<Utf8String>() {}, // ekFingerprint
                    new TypeReference<Uint256>() {} // timestamp
                    ));

    public static final Event DEVICE_PUBLIC_KEY_UPDATED_EVENT = new Event(
            "DevicePublicKeyUpdated",
            Arrays.asList(
                    new TypeReference<Utf8String>(true) {}, // indexed ekFingerprintHash
                    new TypeReference<Utf8String>() {}, // ekFingerprint
                    new TypeReference<Uint256>() {} // timestamp
                    ));

    public static final Event ADMIN_ADDED_EVENT =
            new Event("AdminAdded", Collections.singletonList(new TypeReference<Address>(true) {}));

    public static final Event ADMIN_REMOVED_EVENT =
            new Event("AdminRemoved", Collections.singletonList(new TypeReference<Address>(true) {}));

    // ============ 建構函數 ============

    protected TPMDeviceRegistry(
            String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    protected TPMDeviceRegistry(
            String contractAddress,
            Web3j web3j,
            TransactionManager transactionManager,
            ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
    }

    // ============ 載入合約 ============

    public static TPMDeviceRegistry load(
            String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return new TPMDeviceRegistry(contractAddress, web3j, credentials, contractGasProvider);
    }

    public static TPMDeviceRegistry load(
            String contractAddress,
            Web3j web3j,
            TransactionManager transactionManager,
            ContractGasProvider contractGasProvider) {
        return new TPMDeviceRegistry(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    // ============ 設備註冊功能 ============

    /**
     * 註冊新設備
     */
    public RemoteFunctionCall<TransactionReceipt> registerDevice(String ekFingerprint, String publicKey) {
        final Function function = new Function(
                "registerDevice",
                Arrays.asList(new Utf8String(ekFingerprint), new Utf8String(publicKey)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * 註冊新設備（含元資料）
     */
    public RemoteFunctionCall<TransactionReceipt> registerDeviceWithMetadata(
            String ekFingerprint, String publicKey, String metadata) {
        final Function function = new Function(
                "registerDeviceWithMetadata",
                Arrays.asList(new Utf8String(ekFingerprint), new Utf8String(publicKey), new Utf8String(metadata)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    // ============ 設備查詢功能 ============

    /**
     * 根據 EK Fingerprint 查詢公鑰
     */
    public RemoteFunctionCall<String> getPublicKey(String ekFingerprint) {
        final Function function = new Function(
                "getPublicKey",
                Collections.singletonList(new Utf8String(ekFingerprint)),
                Collections.singletonList(new TypeReference<Utf8String>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    /**
     * 檢查設備是否已註冊
     */
    public RemoteFunctionCall<Boolean> isDeviceRegistered(String ekFingerprint) {
        final Function function = new Function(
                "isDeviceRegistered",
                Collections.singletonList(new Utf8String(ekFingerprint)),
                Collections.singletonList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    /**
     * 檢查設備是否啟用中
     */
    public RemoteFunctionCall<Boolean> isDeviceActive(String ekFingerprint) {
        final Function function = new Function(
                "isDeviceActive",
                Collections.singletonList(new Utf8String(ekFingerprint)),
                Collections.singletonList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    /**
     * 取得設備總數
     */
    public RemoteFunctionCall<BigInteger> getDeviceCount() {
        final Function function = new Function(
                "getDeviceCount", Collections.emptyList(), Collections.singletonList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    /**
     * 取得啟用中設備數量
     */
    public RemoteFunctionCall<BigInteger> getActiveDeviceCount() {
        final Function function = new Function(
                "getActiveDeviceCount",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    // ============ 設備管理功能 ============

    /**
     * 撤銷設備
     */
    public RemoteFunctionCall<TransactionReceipt> revokeDevice(String ekFingerprint) {
        final Function function = new Function(
                "revokeDevice", Collections.singletonList(new Utf8String(ekFingerprint)), Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * 重新啟用設備
     */
    public RemoteFunctionCall<TransactionReceipt> reactivateDevice(String ekFingerprint) {
        final Function function = new Function(
                "reactivateDevice", Collections.singletonList(new Utf8String(ekFingerprint)), Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * 更新設備公鑰
     */
    public RemoteFunctionCall<TransactionReceipt> updatePublicKey(String ekFingerprint, String newPublicKey) {
        final Function function = new Function(
                "updatePublicKey",
                Arrays.asList(new Utf8String(ekFingerprint), new Utf8String(newPublicKey)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * 更新設備元資料
     */
    public RemoteFunctionCall<TransactionReceipt> updateMetadata(String ekFingerprint, String newMetadata) {
        final Function function = new Function(
                "updateMetadata",
                Arrays.asList(new Utf8String(ekFingerprint), new Utf8String(newMetadata)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    // ============ 批次操作 ============

    /**
     * 批次註冊設備
     */
    public RemoteFunctionCall<TransactionReceipt> batchRegisterDevices(
            List<String> ekFingerprints, List<String> publicKeys) {
        final Function function = new Function(
                "batchRegisterDevices",
                Arrays.asList(
                        new DynamicArray<>(
                                Utf8String.class,
                                ekFingerprints.stream().map(Utf8String::new).toList()),
                        new DynamicArray<>(
                                Utf8String.class,
                                publicKeys.stream().map(Utf8String::new).toList())),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * 批次撤銷設備
     */
    public RemoteFunctionCall<TransactionReceipt> batchRevokeDevices(List<String> ekFingerprints) {
        final Function function = new Function(
                "batchRevokeDevices",
                Collections.singletonList(new DynamicArray<>(
                        Utf8String.class,
                        ekFingerprints.stream().map(Utf8String::new).toList())),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    // ============ 管理功能 ============

    /**
     * 新增管理員
     */
    public RemoteFunctionCall<TransactionReceipt> addAdmin(String admin) {
        final Function function =
                new Function("addAdmin", Collections.singletonList(new Address(admin)), Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * 移除管理員
     */
    public RemoteFunctionCall<TransactionReceipt> removeAdmin(String admin) {
        final Function function =
                new Function("removeAdmin", Collections.singletonList(new Address(admin)), Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    /**
     * 查詢合約擁有者
     */
    public RemoteFunctionCall<String> owner() {
        final Function function = new Function(
                "owner", Collections.emptyList(), Collections.singletonList(new TypeReference<Address>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    // ============ 事件響應類別 ============

    public static class DeviceRegisteredEventResponse extends BaseEventResponse {
        public String ekFingerprintHash;
        public String ekFingerprint;
        public BigInteger timestamp;
    }

    public static class DeviceRevokedEventResponse extends BaseEventResponse {
        public String ekFingerprintHash;
        public String ekFingerprint;
        public BigInteger timestamp;
    }

    public static class DeviceReactivatedEventResponse extends BaseEventResponse {
        public String ekFingerprintHash;
        public String ekFingerprint;
        public BigInteger timestamp;
    }

    public static class DevicePublicKeyUpdatedEventResponse extends BaseEventResponse {
        public String ekFingerprintHash;
        public String ekFingerprint;
        public BigInteger timestamp;
    }

    // ============ 事件訂閱 ============

    public Flowable<DeviceRegisteredEventResponse> deviceRegisteredEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(DEVICE_REGISTERED_EVENT));
        return deviceRegisteredEventFlowable(filter);
    }

    public Flowable<DeviceRegisteredEventResponse> deviceRegisteredEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> {
            EventValuesWithLog eventValues = extractEventParametersWithLog(DEVICE_REGISTERED_EVENT, log);
            DeviceRegisteredEventResponse response = new DeviceRegisteredEventResponse();
            response.log = log;
            response.ekFingerprintHash =
                    (String) eventValues.getIndexedValues().getFirst().getValue();
            response.ekFingerprint =
                    (String) eventValues.getNonIndexedValues().get(0).getValue();
            response.timestamp =
                    (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
            return response;
        });
    }

    public Flowable<DeviceRevokedEventResponse> deviceRevokedEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(DEVICE_REVOKED_EVENT));
        return deviceRevokedEventFlowable(filter);
    }

    public Flowable<DeviceRevokedEventResponse> deviceRevokedEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> {
            EventValuesWithLog eventValues = extractEventParametersWithLog(DEVICE_REVOKED_EVENT, log);
            DeviceRevokedEventResponse response = new DeviceRevokedEventResponse();
            response.log = log;
            response.ekFingerprintHash =
                    (String) eventValues.getIndexedValues().getFirst().getValue();
            response.ekFingerprint =
                    (String) eventValues.getNonIndexedValues().get(0).getValue();
            response.timestamp =
                    (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
            return response;
        });
    }

    public Flowable<DeviceReactivatedEventResponse> deviceReactivatedEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(DEVICE_REACTIVATED_EVENT));
        return deviceReactivatedEventFlowable(filter);
    }

    public Flowable<DeviceReactivatedEventResponse> deviceReactivatedEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> {
            EventValuesWithLog eventValues = extractEventParametersWithLog(DEVICE_REACTIVATED_EVENT, log);
            DeviceReactivatedEventResponse response = new DeviceReactivatedEventResponse();
            response.log = log;
            response.ekFingerprintHash =
                    (String) eventValues.getIndexedValues().getFirst().getValue();
            response.ekFingerprint =
                    (String) eventValues.getNonIndexedValues().get(0).getValue();
            response.timestamp =
                    (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
            return response;
        });
    }

    // ============ 從交易回執取得事件 ============

    public List<DeviceRegisteredEventResponse> getDeviceRegisteredEvents(TransactionReceipt receipt) {
        List<EventValuesWithLog> valueList = extractEventParametersWithLog(DEVICE_REGISTERED_EVENT, receipt);
        List<DeviceRegisteredEventResponse> responses = new ArrayList<>(valueList.size());
        for (EventValuesWithLog eventValues : valueList) {
            DeviceRegisteredEventResponse response = new DeviceRegisteredEventResponse();
            response.log = eventValues.getLog();
            response.ekFingerprintHash =
                    (String) eventValues.getIndexedValues().getFirst().getValue();
            response.ekFingerprint =
                    (String) eventValues.getNonIndexedValues().get(0).getValue();
            response.timestamp =
                    (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
            responses.add(response);
        }
        return responses;
    }

    public List<DeviceRevokedEventResponse> getDeviceRevokedEvents(TransactionReceipt receipt) {
        List<EventValuesWithLog> valueList = extractEventParametersWithLog(DEVICE_REVOKED_EVENT, receipt);
        List<DeviceRevokedEventResponse> responses = new ArrayList<>(valueList.size());
        for (EventValuesWithLog eventValues : valueList) {
            DeviceRevokedEventResponse response = new DeviceRevokedEventResponse();
            response.log = eventValues.getLog();
            response.ekFingerprintHash =
                    (String) eventValues.getIndexedValues().getFirst().getValue();
            response.ekFingerprint =
                    (String) eventValues.getNonIndexedValues().get(0).getValue();
            response.timestamp =
                    (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
            responses.add(response);
        }
        return responses;
    }
}
