// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

/**
 * @title TPMDeviceRegistry
 * @dev 智慧合約用於儲存 TPM 設備的 EK Fingerprint 與 PublicKey 對應關係
 * @notice 此合約用於設備身份驗證，支援設備註冊、查詢和撤銷功能
 */
contract TPMDeviceRegistry {

    // ============ 結構定義 ============

    /**
     * @dev 設備資訊結構
     * @param publicKey 設備的公鑰（Base64 編碼字串）
     * @param registeredAt 註冊時間戳
     * @param isActive 設備是否啟用
     * @param metadata 額外的設備元資料（可選）
     */
    struct DeviceInfo {
        string publicKey;
        uint256 registeredAt;
        bool isActive;
        string metadata;
    }

    // ============ 狀態變數 ============

    /// @dev 合約擁有者
    address public owner;

    /// @dev 授權的管理員列表
    mapping(address => bool) public admins;

    /// @dev EK Fingerprint => DeviceInfo 的對應
    mapping(string => DeviceInfo) private devices;

    /// @dev 已註冊的 EK Fingerprint 列表
    string[] private registeredFingerprints;

    /// @dev EK Fingerprint 是否存在的快速查詢
    mapping(string => bool) private fingerprintExists;

    /// @dev 已註冊設備總數
    uint256 public deviceCount;

    // ============ 事件定義 ============

    /// @dev 設備註冊事件
    event DeviceRegistered(
        string indexed ekFingerprintHash,
        string ekFingerprint,
        uint256 timestamp
    );

    /// @dev 設備撤銷事件
    event DeviceRevoked(
        string indexed ekFingerprintHash,
        string ekFingerprint,
        uint256 timestamp
    );

    /// @dev 設備重新啟用事件
    event DeviceReactivated(
        string indexed ekFingerprintHash,
        string ekFingerprint,
        uint256 timestamp
    );

    /// @dev 設備公鑰更新事件
    event DevicePublicKeyUpdated(
        string indexed ekFingerprintHash,
        string ekFingerprint,
        uint256 timestamp
    );

    /// @dev 管理員新增事件
    event AdminAdded(address indexed admin);

    /// @dev 管理員移除事件
    event AdminRemoved(address indexed admin);

    // ============ 修飾符 ============

    /// @dev 僅合約擁有者可執行
    modifier onlyOwner() {
        require(msg.sender == owner, "Only owner can call this function");
        _;
    }

    /// @dev 僅管理員或擁有者可執行
    modifier onlyAdmin() {
        require(
            msg.sender == owner || admins[msg.sender],
            "Only admin can call this function"
        );
        _;
    }

    // ============ 建構函數 ============

    /**
     * @dev 部署合約並設定擁有者
     */
    constructor() {
        owner = msg.sender;
        admins[msg.sender] = true;
    }

    // ============ 管理功能 ============

    /**
     * @dev 新增管理員
     * @param admin 管理員地址
     */
    function addAdmin(address admin) external onlyOwner {
        require(admin != address(0), "Invalid admin address");
        require(!admins[admin], "Already an admin");

        admins[admin] = true;
        emit AdminAdded(admin);
    }

    /**
     * @dev 移除管理員
     * @param admin 管理員地址
     */
    function removeAdmin(address admin) external onlyOwner {
        require(admin != owner, "Cannot remove owner from admins");
        require(admins[admin], "Not an admin");

        admins[admin] = false;
        emit AdminRemoved(admin);
    }

    /**
     * @dev 轉移合約擁有權
     * @param newOwner 新擁有者地址
     */
    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "Invalid new owner address");

        admins[owner] = false;
        owner = newOwner;
        admins[newOwner] = true;
    }

    // ============ 設備註冊功能 ============

    /**
     * @dev 註冊新設備
     * @param ekFingerprint TPM EK 指紋（設備唯一識別碼）
     * @param publicKey 設備公鑰（Base64 編碼）
     */
    function registerDevice(
        string calldata ekFingerprint,
        string calldata publicKey
    ) external onlyAdmin {
        require(bytes(ekFingerprint).length > 0, "EK fingerprint cannot be empty");
        require(bytes(publicKey).length > 0, "Public key cannot be empty");
        require(!fingerprintExists[ekFingerprint], "Device already registered");

        devices[ekFingerprint] = DeviceInfo({
            publicKey: publicKey,
            registeredAt: block.timestamp,
            isActive: true,
            metadata: ""
        });

        registeredFingerprints.push(ekFingerprint);
        fingerprintExists[ekFingerprint] = true;
        deviceCount++;

        emit DeviceRegistered(ekFingerprint, ekFingerprint, block.timestamp);
    }

    /**
     * @dev 註冊新設備（含元資料）
     * @param ekFingerprint TPM EK 指紋
     * @param publicKey 設備公鑰（Base64 編碼）
     * @param metadata 設備元資料（JSON 格式）
     */
    function registerDeviceWithMetadata(
        string calldata ekFingerprint,
        string calldata publicKey,
        string calldata metadata
    ) external onlyAdmin {
        require(bytes(ekFingerprint).length > 0, "EK fingerprint cannot be empty");
        require(bytes(publicKey).length > 0, "Public key cannot be empty");
        require(!fingerprintExists[ekFingerprint], "Device already registered");

        devices[ekFingerprint] = DeviceInfo({
            publicKey: publicKey,
            registeredAt: block.timestamp,
            isActive: true,
            metadata: metadata
        });

        registeredFingerprints.push(ekFingerprint);
        fingerprintExists[ekFingerprint] = true;
        deviceCount++;

        emit DeviceRegistered(ekFingerprint, ekFingerprint, block.timestamp);
    }

    // ============ 設備查詢功能 ============

    /**
     * @dev 根據 EK Fingerprint 查詢公鑰
     * @param ekFingerprint TPM EK 指紋
     * @return publicKey 設備公鑰（Base64 編碼），若不存在或已撤銷則返回空字串
     */
    function getPublicKey(string calldata ekFingerprint)
        external
        view
        returns (string memory publicKey)
    {
        if (!fingerprintExists[ekFingerprint]) {
            return "";
        }

        DeviceInfo storage device = devices[ekFingerprint];
        if (!device.isActive) {
            return "";
        }

        return device.publicKey;
    }

    /**
     * @dev 查詢完整設備資訊
     * @param ekFingerprint TPM EK 指紋
     * @return publicKey 公鑰
     * @return registeredAt 註冊時間
     * @return isActive 是否啟用
     * @return metadata 元資料
     */
    function getDeviceInfo(string calldata ekFingerprint)
        external
        view
        returns (
            string memory publicKey,
            uint256 registeredAt,
            bool isActive,
            string memory metadata
        )
    {
        require(fingerprintExists[ekFingerprint], "Device not found");

        DeviceInfo storage device = devices[ekFingerprint];
        return (
            device.publicKey,
            device.registeredAt,
            device.isActive,
            device.metadata
        );
    }

    /**
     * @dev 檢查設備是否已註冊
     * @param ekFingerprint TPM EK 指紋
     * @return 是否已註冊
     */
    function isDeviceRegistered(string calldata ekFingerprint)
        external
        view
        returns (bool)
    {
        return fingerprintExists[ekFingerprint];
    }

    /**
     * @dev 檢查設備是否啟用中
     * @param ekFingerprint TPM EK 指紋
     * @return 是否啟用
     */
    function isDeviceActive(string calldata ekFingerprint)
        external
        view
        returns (bool)
    {
        if (!fingerprintExists[ekFingerprint]) {
            return false;
        }
        return devices[ekFingerprint].isActive;
    }

    // ============ 設備管理功能 ============

    /**
     * @dev 撤銷設備（軟刪除）
     * @param ekFingerprint TPM EK 指紋
     */
    function revokeDevice(string calldata ekFingerprint) external onlyAdmin {
        require(fingerprintExists[ekFingerprint], "Device not found");
        require(devices[ekFingerprint].isActive, "Device already revoked");

        devices[ekFingerprint].isActive = false;

        emit DeviceRevoked(ekFingerprint, ekFingerprint, block.timestamp);
    }

    /**
     * @dev 重新啟用設備
     * @param ekFingerprint TPM EK 指紋
     */
    function reactivateDevice(string calldata ekFingerprint) external onlyAdmin {
        require(fingerprintExists[ekFingerprint], "Device not found");
        require(!devices[ekFingerprint].isActive, "Device already active");

        devices[ekFingerprint].isActive = true;

        emit DeviceReactivated(ekFingerprint, ekFingerprint, block.timestamp);
    }

    /**
     * @dev 更新設備公鑰
     * @param ekFingerprint TPM EK 指紋
     * @param newPublicKey 新公鑰（Base64 編碼）
     */
    function updatePublicKey(
        string calldata ekFingerprint,
        string calldata newPublicKey
    ) external onlyAdmin {
        require(fingerprintExists[ekFingerprint], "Device not found");
        require(bytes(newPublicKey).length > 0, "Public key cannot be empty");

        devices[ekFingerprint].publicKey = newPublicKey;

        emit DevicePublicKeyUpdated(ekFingerprint, ekFingerprint, block.timestamp);
    }

    /**
     * @dev 更新設備元資料
     * @param ekFingerprint TPM EK 指紋
     * @param newMetadata 新元資料
     */
    function updateMetadata(
        string calldata ekFingerprint,
        string calldata newMetadata
    ) external onlyAdmin {
        require(fingerprintExists[ekFingerprint], "Device not found");

        devices[ekFingerprint].metadata = newMetadata;
    }

    // ============ 批次操作功能 ============

    /**
     * @dev 批次註冊設備
     * @param ekFingerprints EK 指紋陣列
     * @param publicKeys 公鑰陣列
     */
    function batchRegisterDevices(
        string[] calldata ekFingerprints,
        string[] calldata publicKeys
    ) external onlyAdmin {
        require(
            ekFingerprints.length == publicKeys.length,
            "Arrays length mismatch"
        );
        require(ekFingerprints.length <= 100, "Batch size too large");

        for (uint256 i = 0; i < ekFingerprints.length; i++) {
            if (
                bytes(ekFingerprints[i]).length > 0 &&
                bytes(publicKeys[i]).length > 0 &&
                !fingerprintExists[ekFingerprints[i]]
            ) {
                devices[ekFingerprints[i]] = DeviceInfo({
                    publicKey: publicKeys[i],
                    registeredAt: block.timestamp,
                    isActive: true,
                    metadata: ""
                });

                registeredFingerprints.push(ekFingerprints[i]);
                fingerprintExists[ekFingerprints[i]] = true;
                deviceCount++;

                emit DeviceRegistered(
                    ekFingerprints[i],
                    ekFingerprints[i],
                    block.timestamp
                );
            }
        }
    }

    /**
     * @dev 批次撤銷設備
     * @param ekFingerprints EK 指紋陣列
     */
    function batchRevokeDevices(string[] calldata ekFingerprints)
        external
        onlyAdmin
    {
        require(ekFingerprints.length <= 100, "Batch size too large");

        for (uint256 i = 0; i < ekFingerprints.length; i++) {
            if (
                fingerprintExists[ekFingerprints[i]] &&
                devices[ekFingerprints[i]].isActive
            ) {
                devices[ekFingerprints[i]].isActive = false;

                emit DeviceRevoked(
                    ekFingerprints[i],
                    ekFingerprints[i],
                    block.timestamp
                );
            }
        }
    }

    // ============ 統計功能 ============

    /**
     * @dev 取得已註冊設備總數
     * @return 設備總數
     */
    function getDeviceCount() external view returns (uint256) {
        return deviceCount;
    }

    /**
     * @dev 取得啟用中設備數量
     * @return 啟用設備數
     */
    function getActiveDeviceCount() external view returns (uint256) {
        uint256 activeCount = 0;
        for (uint256 i = 0; i < registeredFingerprints.length; i++) {
            if (devices[registeredFingerprints[i]].isActive) {
                activeCount++;
            }
        }
        return activeCount;
    }

    /**
     * @dev 分頁取得已註冊設備指紋列表
     * @param offset 起始索引
     * @param limit 取得數量
     * @return fingerprints 指紋陣列
     * @return total 總數
     */
    function getRegisteredDevices(uint256 offset, uint256 limit)
        external
        view
        returns (string[] memory fingerprints, uint256 total)
    {
        total = registeredFingerprints.length;

        if (offset >= total) {
            return (new string[](0), total);
        }

        uint256 end = offset + limit;
        if (end > total) {
            end = total;
        }

        uint256 resultLength = end - offset;
        fingerprints = new string[](resultLength);

        for (uint256 i = 0; i < resultLength; i++) {
            fingerprints[i] = registeredFingerprints[offset + i];
        }

        return (fingerprints, total);
    }
}
