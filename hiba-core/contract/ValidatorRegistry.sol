// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

interface ITPMDeviceRegistry {
    function isDeviceActive(string calldata ekFingerprint) external view returns (bool);

    function getRegisteredDevices(uint256 offset, uint256 limit)
        external
        view
        returns (string[] memory fingerprints, uint256 total);
}

/**
 * @title ValidatorRegistry
 * @notice 決定哪些 TPM 設備有「授權簽章」資格（M1）。
 *
 * - 平等制（預設）：TPMDeviceRegistry 裡每一台 active 的設備自動就是 Validator，不需要另外登記。
 *   設備登記本來就由管理員一台一台用私鑰執行，這裡不再重複。
 * - 這個合約只記錄「例外設定」：某台設備屬於別的網域（setDomain）、暫停或撤銷投票資格
 *   （suspend／revoke）、簽章服務位址（setEndpoint）、代表標記（setRepresentative）。
 *   第一次對某台設備做例外設定時自動建立它的紀錄。
 * - 沒有設定網域的設備屬於 defaultDomain（預設 "default"）。
 * - 狀態：Active(1)、Suspended(2)、Revoked(3)；沒有紀錄的設備視為 Active。Revoked 不能再恢復。
 *   在 TPMDeviceRegistry 被撤銷的設備一律不算。
 * - 這裡的設定每變動一次，setVersion 就加 1；授權憑證要寫明它依據的版本，版本不同即失效。
 *   TPMDeviceRegistry 新增或撤銷設備不會改變 setVersion（節點驗證時一律以當下的名單計票，不影響安全性）。
 * - 全網門檻 threshold = 0 時自動取 ceil(2n/3)（n = 有效 Validator 數）；節點實際以網域過半數計算。
 * - 代表制（預留，節點程式尚未使用）：representative 標記哪些設備是代表，
 *   representativeAbove = N 表示網域 Validator 數 > N 且代表 >= 3 時改用代表制；0 = 永遠用平等制。
 */
contract ValidatorRegistry {
    uint8 public constant STATUS_NONE = 0;
    uint8 public constant STATUS_ACTIVE = 1;
    uint8 public constant STATUS_SUSPENDED = 2;
    uint8 public constant STATUS_REVOKED = 3;
    /// deviceAt() 用：設備在 TPMDeviceRegistry 已被撤銷
    uint8 public constant STATUS_DEVICE_INACTIVE = 4;

    struct Validator {
        string domainId; // 空字串 = 跟著 defaultDomain
        string endpoint; // 簽章服務位址 host:port（只有 HTTP 收集簽章時需要）
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
    string public defaultDomain;

    /// 代表標記（代表制用；預留）
    mapping(string => bool) public representative;
    /// 網域 Validator 數超過這個值、且代表 >= 3 時改用代表制；0 = 永遠平等制（預留）
    uint256 public representativeAbove;

    event ValidatorAdded(string ekFingerprint, string domainId, string endpoint, uint256 version);
    event ValidatorStatusChanged(string ekFingerprint, uint8 oldStatus, uint8 newStatus, uint256 version);
    event ValidatorEndpointChanged(string ekFingerprint, string endpoint);
    event ValidatorDomainChanged(string ekFingerprint, string oldDomainId, string newDomainId, uint256 version);
    event DefaultDomainChanged(string oldDomainId, string newDomainId, uint256 version);
    event ThresholdChanged(uint256 oldThreshold, uint256 newThreshold, uint256 version);
    event RepresentativeChanged(string ekFingerprint, bool isRepresentative, uint256 version);
    event RepresentativeAboveChanged(uint256 oldValue, uint256 newValue, uint256 version);
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
        defaultDomain = "default";
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

    // ---------------- 例外設定 ----------------

    /// 取得設備的紀錄；還沒有就建立（設備必須在 TPMDeviceRegistry 是 active）
    function _entry(string calldata ekFingerprint) private returns (Validator storage v) {
        v = validators[ekFingerprint];
        if (v.status == STATUS_NONE) {
            require(bytes(ekFingerprint).length > 0, "Empty EK fingerprint");
            require(deviceRegistry.isDeviceActive(ekFingerprint), "Device not active in TPMDeviceRegistry");
            v.status = STATUS_ACTIVE;
            v.registeredAt = uint64(block.timestamp);
            v.updatedAt = uint64(block.timestamp);
            validatorIds.push(ekFingerprint);
            emit ValidatorAdded(ekFingerprint, "", "", setVersion);
        }
    }

    /// 一次設定網域與位址（舊用法；平等制下不需要，設備登記後就自動是 Validator）
    function addValidator(string calldata ekFingerprint, string calldata domainId, string calldata endpoint)
        external
        onlyAdmin
    {
        Validator storage v = _entry(ekFingerprint);
        require(v.status != STATUS_REVOKED, "Validator revoked");
        v.domainId = domainId;
        v.endpoint = endpoint;
        v.updatedAt = uint64(block.timestamp);
        setVersion++;
        emit ValidatorDomainChanged(ekFingerprint, "", domainId, setVersion);
        emit ValidatorEndpointChanged(ekFingerprint, endpoint);
    }

    function suspendValidator(string calldata ekFingerprint) external onlyAdmin {
        _entry(ekFingerprint);
        _setStatus(ekFingerprint, STATUS_ACTIVE, STATUS_SUSPENDED);
    }

    function resumeValidator(string calldata ekFingerprint) external onlyAdmin {
        require(deviceRegistry.isDeviceActive(ekFingerprint), "Device not active in TPMDeviceRegistry");
        _setStatus(ekFingerprint, STATUS_SUSPENDED, STATUS_ACTIVE);
    }

    function revokeValidator(string calldata ekFingerprint) external onlyAdmin {
        Validator storage v = _entry(ekFingerprint);
        require(v.status == STATUS_ACTIVE || v.status == STATUS_SUSPENDED, "Not revocable");
        uint8 old = v.status;
        v.status = STATUS_REVOKED;
        v.updatedAt = uint64(block.timestamp);
        setVersion++;
        emit ValidatorStatusChanged(ekFingerprint, old, STATUS_REVOKED, setVersion);
    }

    function setEndpoint(string calldata ekFingerprint, string calldata endpoint) external onlyAdmin {
        Validator storage v = _entry(ekFingerprint);
        v.endpoint = endpoint;
        v.updatedAt = uint64(block.timestamp);
        emit ValidatorEndpointChanged(ekFingerprint, endpoint);
    }

    /// 改設備所屬網域（門檻以網域計算，所以會讓名單版本 +1，舊憑證失效）
    function setDomain(string calldata ekFingerprint, string calldata domainId) external onlyAdmin {
        Validator storage v = _entry(ekFingerprint);
        require(bytes(domainId).length > 0, "Empty domain");
        string memory old = _domainOf(ekFingerprint);
        v.domainId = domainId;
        v.updatedAt = uint64(block.timestamp);
        setVersion++;
        emit ValidatorDomainChanged(ekFingerprint, old, domainId, setVersion);
    }

    /// 沒有另外設定網域的設備所屬的網域
    function setDefaultDomain(string calldata domainId) external onlyAdmin {
        require(bytes(domainId).length > 0, "Empty domain");
        string memory old = defaultDomain;
        defaultDomain = domainId;
        setVersion++;
        emit DefaultDomainChanged(old, domainId, setVersion);
    }

    /// 標記／取消代表（會讓名單版本 +1）
    function setRepresentative(string calldata ekFingerprint, bool isRepresentative) external onlyAdmin {
        Validator storage v = _entry(ekFingerprint);
        representative[ekFingerprint] = isRepresentative;
        v.updatedAt = uint64(block.timestamp);
        setVersion++;
        emit RepresentativeChanged(ekFingerprint, isRepresentative, setVersion);
    }

    /// 設定代表制的裝置數門檻 N（0 = 永遠平等制；會讓名單版本 +1）
    function setRepresentativeAbove(uint256 n) external onlyAdmin {
        uint256 old = representativeAbove;
        representativeAbove = n;
        setVersion++;
        emit RepresentativeAboveChanged(old, n, setVersion);
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

    /// TPMDeviceRegistry 裡登記過的全部設備
    function _devices() private view returns (string[] memory list) {
        (, uint256 total) = deviceRegistry.getRegisteredDevices(0, 0);
        if (total == 0) {
            return new string[](0);
        }
        (list,) = deviceRegistry.getRegisteredDevices(0, total);
    }

    function _domainOf(string memory ekFingerprint) private view returns (string memory) {
        string memory d = validators[ekFingerprint].domainId;
        return bytes(d).length > 0 ? d : defaultDomain;
    }

    /// 設備是否有投票資格：在 TPMDeviceRegistry 是 active，而且沒有被暫停或撤銷
    function _isEffective(string memory ekFingerprint) private view returns (bool) {
        uint8 st = validators[ekFingerprint].status;
        return (st == STATUS_NONE || st == STATUS_ACTIVE) && deviceRegistry.isDeviceActive(ekFingerprint);
    }

    function _required(uint256 n) private view returns (uint256) {
        if (n == 0) {
            return 0;
        }
        if (threshold == 0) {
            return (2 * n + 2) / 3; // ceil(2n/3)
        }
        return threshold > n ? n : threshold;
    }

    /// 回傳設備的有效網域、位址、有效狀態（沒有紀錄的 active 設備為 Active；設備被撤銷為 4）
    function getValidator(string calldata ekFingerprint)
        external
        view
        returns (string memory domainId, string memory endpoint, uint8 status, uint64 registeredAt, uint64 updatedAt)
    {
        Validator storage v = validators[ekFingerprint];
        uint8 st = v.status;
        if (!deviceRegistry.isDeviceActive(ekFingerprint)) {
            st = STATUS_DEVICE_INACTIVE;
        } else if (st == STATUS_NONE) {
            st = STATUS_ACTIVE;
        }
        return (_domainOf(ekFingerprint), v.endpoint, st, v.registeredAt, v.updatedAt);
    }

    /// 有例外設定紀錄的設備數（不是 Validator 數）
    function getValidatorCount() external view returns (uint256) {
        return validatorIds.length;
    }

    function getValidatorIdAt(uint256 index) external view returns (string memory) {
        return validatorIds[index];
    }

    /// TPMDeviceRegistry 登記過的設備數（validators.sh list 用）
    function deviceCount() external view returns (uint256 total) {
        (, total) = deviceRegistry.getRegisteredDevices(0, 0);
    }

    /// 第 i 台登記過的設備與它的有效設定（validators.sh list 用）
    function deviceAt(uint256 index)
        external
        view
        returns (string memory ekFingerprint, string memory domainId, uint8 status, bool isRepresentative, bool hasEntry)
    {
        (string[] memory one,) = deviceRegistry.getRegisteredDevices(index, 1);
        require(one.length == 1, "Index out of range");
        ekFingerprint = one[0];
        Validator storage v = validators[ekFingerprint];
        status = v.status;
        if (!deviceRegistry.isDeviceActive(ekFingerprint)) {
            status = STATUS_DEVICE_INACTIVE;
        } else if (status == STATUS_NONE) {
            status = STATUS_ACTIVE;
        }
        return (ekFingerprint, _domainOf(ekFingerprint), status, representative[ekFingerprint], v.status != STATUS_NONE);
    }

    /// 有效 Validator 數
    function activeCount() public view returns (uint256 n) {
        string[] memory devs = _devices();
        for (uint256 i = 0; i < devs.length; i++) {
            if (_isEffective(devs[i])) {
                n++;
            }
        }
    }

    /** 全網門檻（節點實際以網域過半數計算，只在不知道自己網域時使用） */
    function requiredThreshold() public view returns (uint256) {
        return _required(activeCount());
    }

    function isActiveValidator(string calldata ekFingerprint) external view returns (bool) {
        return _isEffective(ekFingerprint);
    }

    /**
     * 一次取得設備驗證需要的全部資料（同一個區塊，版本一致）。
     * 回傳格式與舊版相同，節點程式不需要修改。
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
        string[] memory devs = _devices();
        bool[] memory eff = new bool[](devs.length);
        uint256 n = 0;
        for (uint256 i = 0; i < devs.length; i++) {
            eff[i] = _isEffective(devs[i]);
            if (eff[i]) {
                n++;
            }
        }
        ekFingerprints = new string[](n);
        domainIds = new string[](n);
        endpoints = new string[](n);
        uint256 j = 0;
        for (uint256 i = 0; i < devs.length; i++) {
            if (eff[i]) {
                ekFingerprints[j] = devs[i];
                domainIds[j] = _domainOf(devs[i]);
                endpoints[j] = validators[devs[i]].endpoint;
                j++;
            }
        }
        return (setVersion, _required(n), ekFingerprints, domainIds, endpoints);
    }

    /**
     * 代表制用的資料：與 getValidatorSet() 同順序的代表標記，以及 N。
     * 分開成另一個函式，getValidatorSet() 的格式不變。
     */
    function getRepresentativeInfo() external view returns (uint256 version, uint256 above, bool[] memory flags) {
        string[] memory devs = _devices();
        uint256 n = 0;
        for (uint256 i = 0; i < devs.length; i++) {
            if (_isEffective(devs[i])) {
                n++;
            }
        }
        flags = new bool[](n);
        uint256 j = 0;
        for (uint256 i = 0; i < devs.length; i++) {
            if (_isEffective(devs[i])) {
                flags[j] = representative[devs[i]];
                j++;
            }
        }
        return (setVersion, representativeAbove, flags);
    }
}
