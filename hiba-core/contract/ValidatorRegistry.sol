// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

interface ITPMDeviceRegistry {
    function isDeviceActive(string calldata ekFingerprint) external view returns (bool);
}

/**
 * @title ValidatorRegistry
 * @notice 記錄哪些 TPM 設備有「授權簽章」資格（M1：由管理員維護名單）。
 *
 * - Validator 用 TPM EK fingerprint 識別，公鑰沿用 TPMDeviceRegistry 裡登記的那把，
 *   新增時要求該設備在 TPMDeviceRegistry 是 active。
 * - 狀態：Active(1)、Suspended(2)、Revoked(3)。Revoked 不能再恢復。
 * - 名單或門檻有任何變動，setVersion 就加 1；授權憑證要寫明它依據的版本，版本不同即失效。
 * - 門檻 threshold = 0 時自動取 ceil(2n/3)（n = Active 數）；手動設定時超過 n 以 n 計算。
 * - 變動紀錄看事件（ValidatorAdded / ValidatorStatusChanged / ThresholdChanged）。
 */
contract ValidatorRegistry {
    uint8 public constant STATUS_NONE = 0;
    uint8 public constant STATUS_ACTIVE = 1;
    uint8 public constant STATUS_SUSPENDED = 2;
    uint8 public constant STATUS_REVOKED = 3;

    struct Validator {
        string domainId;
        string endpoint; // 簽章服務位址 host:port
        uint8 status;
        uint64 registeredAt;
        uint64 updatedAt;
    }

    address public owner;
    mapping(address => bool) public admins;
    ITPMDeviceRegistry public deviceRegistry;

    mapping(string => Validator) private validators;
    string[] private validatorIds;

    uint256 public setVersion;
    uint256 public threshold; // 0 = 自動

    event ValidatorAdded(string ekFingerprint, string domainId, string endpoint, uint256 setVersion);
    event ValidatorStatusChanged(string ekFingerprint, uint8 oldStatus, uint8 newStatus, uint256 setVersion);
    event ValidatorEndpointChanged(string ekFingerprint, string endpoint);
    event ThresholdChanged(uint256 oldThreshold, uint256 newThreshold, uint256 setVersion);
    event AdminAdded(address indexed admin);
    event AdminRemoved(address indexed admin);
    event OwnershipTransferred(address indexed oldOwner, address indexed newOwner);

    modifier onlyOwner() {
        require(msg.sender == owner, "Only owner");
        _;
    }

    modifier onlyAdmin() {
        require(admins[msg.sender], "Only admin");
        _;
    }

    constructor(address deviceRegistry_) {
        require(deviceRegistry_ != address(0), "Invalid device registry");
        owner = msg.sender;
        admins[msg.sender] = true;
        deviceRegistry = ITPMDeviceRegistry(deviceRegistry_);
        setVersion = 1;
    }

    // ---------------- 管理權 ----------------

    function addAdmin(address admin) external onlyOwner {
        require(admin != address(0) && !admins[admin], "Invalid admin");
        admins[admin] = true;
        emit AdminAdded(admin);
    }

    function removeAdmin(address admin) external onlyOwner {
        require(admin != owner && admins[admin], "Invalid admin");
        admins[admin] = false;
        emit AdminRemoved(admin);
    }

    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "Invalid owner");
        admins[owner] = false;
        emit OwnershipTransferred(owner, newOwner);
        owner = newOwner;
        admins[newOwner] = true;
    }

    // ---------------- 名單維護 ----------------

    function addValidator(string calldata ekFingerprint, string calldata domainId, string calldata endpoint)
        external
        onlyAdmin
    {
        require(bytes(ekFingerprint).length > 0, "Empty EK fingerprint");
        require(validators[ekFingerprint].status == STATUS_NONE, "Validator exists");
        require(deviceRegistry.isDeviceActive(ekFingerprint), "Device not active in TPMDeviceRegistry");
        validators[ekFingerprint] = Validator({
            domainId: domainId,
            endpoint: endpoint,
            status: STATUS_ACTIVE,
            registeredAt: uint64(block.timestamp),
            updatedAt: uint64(block.timestamp)
        });
        validatorIds.push(ekFingerprint);
        setVersion++;
        emit ValidatorAdded(ekFingerprint, domainId, endpoint, setVersion);
    }

    function suspendValidator(string calldata ekFingerprint) external onlyAdmin {
        _setStatus(ekFingerprint, STATUS_ACTIVE, STATUS_SUSPENDED);
    }

    function resumeValidator(string calldata ekFingerprint) external onlyAdmin {
        require(deviceRegistry.isDeviceActive(ekFingerprint), "Device not active in TPMDeviceRegistry");
        _setStatus(ekFingerprint, STATUS_SUSPENDED, STATUS_ACTIVE);
    }

    function revokeValidator(string calldata ekFingerprint) external onlyAdmin {
        Validator storage v = validators[ekFingerprint];
        require(v.status == STATUS_ACTIVE || v.status == STATUS_SUSPENDED, "Not revocable");
        uint8 old = v.status;
        v.status = STATUS_REVOKED;
        v.updatedAt = uint64(block.timestamp);
        setVersion++;
        emit ValidatorStatusChanged(ekFingerprint, old, STATUS_REVOKED, setVersion);
    }

    function setEndpoint(string calldata ekFingerprint, string calldata endpoint) external onlyAdmin {
        Validator storage v = validators[ekFingerprint];
        require(v.status != STATUS_NONE, "Unknown validator");
        v.endpoint = endpoint;
        v.updatedAt = uint64(block.timestamp);
        emit ValidatorEndpointChanged(ekFingerprint, endpoint);
    }

    function setThreshold(uint256 newThreshold) external onlyAdmin {
        uint256 old = threshold;
        threshold = newThreshold;
        setVersion++;
        emit ThresholdChanged(old, newThreshold, setVersion);
    }

    function _setStatus(string calldata ekFingerprint, uint8 from, uint8 to) private {
        Validator storage v = validators[ekFingerprint];
        require(v.status == from, "Invalid status transition");
        v.status = to;
        v.updatedAt = uint64(block.timestamp);
        setVersion++;
        emit ValidatorStatusChanged(ekFingerprint, from, to, setVersion);
    }

    // ---------------- 查詢 ----------------

    function getValidator(string calldata ekFingerprint)
        external
        view
        returns (string memory domainId, string memory endpoint, uint8 status, uint64 registeredAt, uint64 updatedAt)
    {
        Validator storage v = validators[ekFingerprint];
        return (v.domainId, v.endpoint, v.status, v.registeredAt, v.updatedAt);
    }

    function getValidatorCount() external view returns (uint256) {
        return validatorIds.length;
    }

    function getValidatorIdAt(uint256 index) external view returns (string memory) {
        return validatorIds[index];
    }

    function activeCount() public view returns (uint256 n) {
        for (uint256 i = 0; i < validatorIds.length; i++) {
            if (validators[validatorIds[i]].status == STATUS_ACTIVE) {
                n++;
            }
        }
    }

    /** 目前實際要求的簽章數 */
    function requiredThreshold() public view returns (uint256) {
        uint256 n = activeCount();
        if (n == 0) {
            return 0;
        }
        if (threshold == 0) {
            return (2 * n + 2) / 3; // ceil(2n/3)
        }
        return threshold > n ? n : threshold;
    }

    function isActiveValidator(string calldata ekFingerprint) external view returns (bool) {
        return validators[ekFingerprint].status == STATUS_ACTIVE;
    }

    /**
     * 一次取得設備驗證需要的全部資料（同一個區塊，版本一致）。
     */
    function getValidatorSet()
        external
        view
        returns (
            uint256 version,
            uint256 required,
            string[] memory ekFingerprints,
            string[] memory domainIds,
            string[] memory endpoints
        )
    {
        uint256 n = activeCount();
        ekFingerprints = new string[](n);
        domainIds = new string[](n);
        endpoints = new string[](n);
        uint256 j = 0;
        for (uint256 i = 0; i < validatorIds.length; i++) {
            Validator storage v = validators[validatorIds[i]];
            if (v.status == STATUS_ACTIVE) {
                ekFingerprints[j] = validatorIds[i];
                domainIds[j] = v.domainId;
                endpoints[j] = v.endpoint;
                j++;
            }
        }
        return (setVersion, requiredThreshold(), ekFingerprints, domainIds, endpoints);
    }
}
