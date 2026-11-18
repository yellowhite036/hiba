// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

/// TPMDeviceRegistry 中本合約用到的部分（合約本身不變，沿用已部署的那一份）
interface ITPMDeviceRegistry {
    function owner() external view returns (address);
    function admins(address account) external view returns (bool);
    function isDeviceActive(string calldata ekFingerprint) external view returns (bool);
}

/**
 * FileProtectionContract Smart Contract
 * Stores file metadata on the blockchain for integrity verification
 *
 * 誰可以上鏈：每台設備有自己的錢包，由 TPMDeviceRegistry 的管理員用 bindDeviceWallet 綁定到
 * 該設備的 EK 指紋。storeFile / batchStoreFile 只接受「已綁定、且設備在 TPMDeviceRegistry 中為啟用」
 * 的錢包，並在 FileStoredByDevice 事件寫出 EK 指紋，所以鏈上看得出是哪台設備的動作，且無法冒充。
 * 設備被撤銷（revokeDevice）後，它的錢包立即無法再上鏈。
 * 尚未設定 deviceRegistry 前，只有合約擁有者能上鏈（部署當下的過渡狀態）。
 */
contract FileProtectionContract {

    address private owner;

    /// 設備登記合約；設定後只有已綁定的設備錢包能上鏈
    ITPMDeviceRegistry public deviceRegistry;

    mapping(address => string) private walletDevice;   // 錢包 => EK 指紋
    mapping(string => address) private deviceWallet;   // EK 指紋 => 錢包

    // Input struct for batch operations (user provides these fields)
    struct FileMetadata {
        string id;
        string fileName;
        string fileHash;
        string uploader;
        uint256 fileSize;
        string filePath;
        string description;
    }

    // Storage struct with additional system fields
    struct StoredFileMetadata {
        string id;
        string fileName;
        string fileHash;
        string uploader;
        uint256 fileSize;
        string filePath;
        string description;
        uint256 timestamp;
        bool exists;
    }

    mapping(string => StoredFileMetadata) private files;  // fileHash => StoredFileMetadata
    string[] private fileHashes;                          // Array of file hashes for iteration

    event FileStored(
        string fileHash,
        address indexed uploader,
        string fileName,
        uint256 timestamp
    );

    event FileUpdated(
        string indexed fileHash,
        address indexed uploader,
        uint256 timestamp
    );

    event FileDeleted(
        string indexed fileHash,
        address indexed deletedBy,
        uint256 timestamp
    );

    event FilesCleared(
        address indexed clearedBy,
        uint256 totalFiles,
        uint256 timestamp
    );

    event BatchFileDeleted(
        address indexed deletedBy,
        uint256 deletedCount,
        uint256 timestamp
    );

    event BatchFileStored(
        address indexed uploader,
        uint256 filesCount,
        uint256 timestamp
    );

    /// 每一筆檔案登記（新增或更新）是哪台設備送出的
    event FileStoredByDevice(
        string fileHash,
        string ekFingerprint,
        address indexed uploader,
        bool isUpdate,
        uint256 timestamp
    );

    event DeviceRegistrySet(address indexed registry, uint256 timestamp);

    event DeviceWalletBound(
        string indexed ekFingerprintHash,
        string ekFingerprint,
        address indexed wallet,
        uint256 timestamp
    );

    event DeviceWalletUnbound(
        string indexed ekFingerprintHash,
        string ekFingerprint,
        address indexed wallet,
        uint256 timestamp
    );

    constructor() {
        owner = msg.sender;
    }

    modifier onlyOwner() {
        require(msg.sender == owner, "Only contract owner can perform this action");
        _;
    }

    /// 合約擁有者，或 TPMDeviceRegistry 的擁有者／管理員（登記設備的同一把管理員私鑰）
    modifier onlyRegistryAdmin() {
        require(
            msg.sender == owner ||
            (address(deviceRegistry) != address(0) &&
                (deviceRegistry.admins(msg.sender) || deviceRegistry.owner() == msg.sender)),
            "Only device registry admin can perform this action"
        );
        _;
    }

    /// 上鏈權限檢查：錢包要綁定到一台在 TPMDeviceRegistry 中啟用的設備
    modifier onlyActiveDevice() {
        _checkActiveDevice();
        _;
    }

    function _checkActiveDevice() private view {
        if (address(deviceRegistry) == address(0)) {
            require(msg.sender == owner, "Device registry not set: only owner can store files");
            return;
        }
        string memory ek = walletDevice[msg.sender];
        require(bytes(ek).length > 0, "Wallet is not bound to a registered device");
        require(deviceRegistry.isDeviceActive(ek), "Device is revoked or not registered");
    }

    function _emitDevice(string memory fileHash, bool isUpdate) private {
        emit FileStoredByDevice(fileHash, walletDevice[msg.sender], msg.sender, isUpdate, block.timestamp);
    }

    // ============ 設備錢包 ============

    /**
     * 設定設備登記合約（TPMDeviceRegistry 位址）。設定後只有已綁定的設備錢包能上鏈。
     */
    function setDeviceRegistry(address registry) public onlyOwner {
        require(registry != address(0), "Registry cannot be zero address");
        require(registry.code.length > 0, "Registry is not a contract");
        deviceRegistry = ITPMDeviceRegistry(registry);
        emit DeviceRegistrySet(registry, block.timestamp);
    }

    /**
     * 把設備（EK 指紋）綁定到它的錢包；設備原本綁定的舊錢包會解除。
     * 設備要已在 TPMDeviceRegistry 註冊且啟用；一個錢包只能屬於一台設備。
     */
    function bindDeviceWallet(string memory ekFingerprint, address wallet) public onlyRegistryAdmin {
        require(address(deviceRegistry) != address(0), "Device registry not set");
        require(wallet != address(0), "Wallet cannot be zero address");
        require(deviceRegistry.isDeviceActive(ekFingerprint), "Device is revoked or not registered");
        bytes memory current = bytes(walletDevice[wallet]);
        require(
            current.length == 0 || keccak256(current) == keccak256(bytes(ekFingerprint)),
            "Wallet is already bound to another device"
        );

        address old = deviceWallet[ekFingerprint];
        if (old == wallet) {
            return;
        }
        if (old != address(0)) {
            delete walletDevice[old];
            emit DeviceWalletUnbound(ekFingerprint, ekFingerprint, old, block.timestamp);
        }
        deviceWallet[ekFingerprint] = wallet;
        walletDevice[wallet] = ekFingerprint;
        emit DeviceWalletBound(ekFingerprint, ekFingerprint, wallet, block.timestamp);
    }

    /**
     * 解除設備的錢包綁定（例如錢包私鑰外洩、要換新錢包時）。
     */
    function unbindDeviceWallet(string memory ekFingerprint) public onlyRegistryAdmin {
        address old = deviceWallet[ekFingerprint];
        require(old != address(0), "Device has no bound wallet");
        delete walletDevice[old];
        delete deviceWallet[ekFingerprint];
        emit DeviceWalletUnbound(ekFingerprint, ekFingerprint, old, block.timestamp);
    }

    /// 錢包屬於哪台設備（EK 指紋；沒有綁定則為空字串）
    function getDeviceOfWallet(address wallet) public view returns (string memory) {
        return walletDevice[wallet];
    }

    /// 設備綁定的錢包（沒有綁定則為 0 位址）
    function getWalletOfDevice(string memory ekFingerprint) public view returns (address) {
        return deviceWallet[ekFingerprint];
    }

    /// 這個錢包現在能不能上鏈
    function canStoreFiles(address wallet) public view returns (bool) {
        if (address(deviceRegistry) == address(0)) {
            return wallet == owner;
        }
        string memory ek = walletDevice[wallet];
        return bytes(ek).length > 0 && deviceRegistry.isDeviceActive(ek);
    }

    /**
     * Store single file metadata on the blockchain
     */
    function storeFile(
        string memory id,
        string memory fileName,
        string memory fileHash,
        string memory uploader,
        uint256 fileSize,
        string memory filePath,
        string memory description
    ) public onlyActiveDevice {
        require(bytes(fileName).length > 0, "File name cannot be empty");
        require(bytes(fileHash).length > 0, "File hash cannot be empty");
        require(bytes(uploader).length > 0, "Uploader cannot be empty");

        bool isUpdate = files[fileHash].exists;

        files[fileHash] = StoredFileMetadata({
            id: id,
            fileName: fileName,
            fileHash: fileHash,
            uploader: uploader,
            fileSize: fileSize,
            filePath: filePath,
            description: description,
            timestamp: block.timestamp,
            exists: true
        });

        if (!isUpdate) {
            fileHashes.push(fileHash);
            emit FileStored(fileHash, msg.sender, fileName, block.timestamp);
            _emitDevice(fileHash, false);
        } else {
            emit FileUpdated(fileHash, msg.sender, block.timestamp);
            _emitDevice(fileHash, true);
        }
    }

    /**
     * Store single file using FileMetadata struct
     */
    function storeFileWithStruct(FileMetadata memory fileData) public onlyActiveDevice {
        require(bytes(fileData.fileName).length > 0, "File name cannot be empty");
        require(bytes(fileData.fileHash).length > 0, "File hash cannot be empty");
        require(bytes(fileData.uploader).length > 0, "Uploader cannot be empty");

        bool isUpdate = files[fileData.fileHash].exists;

        files[fileData.fileHash] = StoredFileMetadata({
            id: fileData.id,
            fileName: fileData.fileName,
            fileHash: fileData.fileHash,
            uploader: fileData.uploader,
            fileSize: fileData.fileSize,
            filePath: fileData.filePath,
            description: fileData.description,
            timestamp: block.timestamp,
            exists: true
        });

        if (!isUpdate) {
            fileHashes.push(fileData.fileHash);
            emit FileStored(fileData.fileHash, msg.sender, fileData.fileName, block.timestamp);
            _emitDevice(fileData.fileHash, false);
        } else {
            emit FileUpdated(fileData.fileHash, msg.sender, block.timestamp);
            _emitDevice(fileData.fileHash, true);
        }
    }

    /**
     * Batch store multiple files using arrays (backward compatibility)
     */
    function batchStoreFile(
        string[] memory ids,
        string[] memory fileNames,
        string[] memory fileHashes_,
        string[] memory uploaders,
        uint256[] memory fileSizes,
        string[] memory filePaths,
        string[] memory descriptions
    ) public onlyActiveDevice {
        require(fileNames.length > 0, "No files provided");
        require(
            fileNames.length == ids.length &&
            fileNames.length == fileHashes_.length &&
            fileNames.length == uploaders.length &&
            fileNames.length == fileSizes.length &&
            fileNames.length == filePaths.length &&
            fileNames.length == descriptions.length,
            "All arrays must have the same length"
        );

        uint256 newFilesCount = 0;
        uint256 updatedFilesCount = 0;

        for (uint256 i = 0; i < fileNames.length; i++) {
            require(bytes(ids[i]).length > 0, "ID cannot be empty");
            require(bytes(fileNames[i]).length > 0, "File name cannot be empty");
            require(bytes(fileHashes_[i]).length > 0, "File hash cannot be empty");
            require(bytes(uploaders[i]).length > 0, "Uploader cannot be empty");

            bool isUpdate = files[fileHashes_[i]].exists;

            files[fileHashes_[i]] = StoredFileMetadata({
                id: ids[i],
                fileName: fileNames[i],
                fileHash: fileHashes_[i],
                uploader: uploaders[i],
                fileSize: fileSizes[i],
                filePath: filePaths[i],
                description: descriptions[i],
                timestamp: block.timestamp,
                exists: true
            });

            if (!isUpdate) {
                fileHashes.push(fileHashes_[i]);
                newFilesCount++;
                emit FileStored(fileHashes_[i], msg.sender, fileNames[i], block.timestamp);
                _emitDevice(fileHashes_[i], false);
            } else {
                updatedFilesCount++;
                emit FileUpdated(fileHashes_[i], msg.sender, block.timestamp);
                _emitDevice(fileHashes_[i], true);
            }
        }

        emit BatchFileStored(msg.sender, newFilesCount + updatedFilesCount, block.timestamp);
    }

    /**
     * Batch store multiple files using FileMetadata struct array (NEW!)
     */
    function batchStoreFileWithStruct(FileMetadata[] memory fileDataArray) public onlyActiveDevice {
        require(fileDataArray.length > 0, "No files provided");

        uint256 newFilesCount = 0;
        uint256 updatedFilesCount = 0;

        for (uint256 i = 0; i < fileDataArray.length; i++) {
            FileMetadata memory fileData = fileDataArray[i];

            require(bytes(fileData.id).length > 0, "ID cannot be empty");
            require(bytes(fileData.fileName).length > 0, "File name cannot be empty");
            require(bytes(fileData.fileHash).length > 0, "File hash cannot be empty");
            require(bytes(fileData.uploader).length > 0, "Uploader cannot be empty");

            bool isUpdate = files[fileData.fileHash].exists;

            files[fileData.fileHash] = StoredFileMetadata({
                id: fileData.id,
                fileName: fileData.fileName,
                fileHash: fileData.fileHash,
                uploader: fileData.uploader,
                fileSize: fileData.fileSize,
                filePath: fileData.filePath,
                description: fileData.description,
                timestamp: block.timestamp,
                exists: true
            });

            if (!isUpdate) {
                fileHashes.push(fileData.fileHash);
                newFilesCount++;
                emit FileStored(fileData.fileHash, msg.sender, fileData.fileName, block.timestamp);
                _emitDevice(fileData.fileHash, false);
            } else {
                updatedFilesCount++;
                emit FileUpdated(fileData.fileHash, msg.sender, block.timestamp);
                _emitDevice(fileData.fileHash, true);
            }
        }

        emit BatchFileStored(msg.sender, newFilesCount + updatedFilesCount, block.timestamp);
    }

    /**
     * Retrieve file metadata from the blockchain
     */
    function getFile(string memory fileHash) public view returns (
        string memory id,
        string memory fileName,
        string memory fileHashReturn,
        string memory uploader,
        string memory description,
        uint256 fileSize,
        string memory filePath,
        string memory timestamp
    ) {
        require(files[fileHash].exists, "File not found");

        StoredFileMetadata memory file = files[fileHash];
        return (
            file.id,
            file.fileName,
            file.fileHash,
            file.uploader,
            file.description,
            file.fileSize,
            file.filePath,
            string(abi.encodePacked(file.timestamp))
        );
    }

    /**
     * Get file metadata as struct
     */
    function getFileAsStruct(string memory fileHash) public view returns (StoredFileMetadata memory) {
        require(files[fileHash].exists, "File not found");
        return files[fileHash];
    }

    /**
     * Check if file exists
     */
    function fileExists(string memory fileHash) public view returns (bool) {
        return files[fileHash].exists;
    }

    /**
     * Get file hash for verification (now redundant but kept for compatibility)
     */
    function getFileHash(string memory fileHash) public view returns (string memory) {
        require(files[fileHash].exists, "File not found");
        return files[fileHash].fileHash;
    }

    /**
     * Get total number of files stored
     */
    function getTotalFiles() public view returns (uint256) {
        return fileHashes.length;
    }

    /**
     * Get file hash by index
     */
    function getFileHashByIndex(uint256 index) public view returns (string memory) {
        require(index < fileHashes.length, "Index out of bounds");
        return fileHashes[index];
    }

    /**
     * Get file timestamp
     */
    function getFileTimestamp(string memory fileHash) public view returns (uint256) {
        require(files[fileHash].exists, "File not found");
        return files[fileHash].timestamp;
    }

    /**
     * Delete a single file - Only owner can delete
     */
    function deleteFile(string memory fileHash) public onlyOwner {
        require(files[fileHash].exists, "File does not exist");

        // Mark file as non-existent
        files[fileHash].exists = false;

        // Remove from fileHashes array
        _removeFromFileHashes(fileHash);

        emit FileDeleted(fileHash, msg.sender, block.timestamp);
    }

    /**
     * Destroy the contract - Only owner can destroy
     */
    function destroyContract() public onlyOwner {
        // Clear all files first
        _clearAllFiles();

        // Transfer any remaining ETH to owner
        payable(owner).transfer(address(this).balance);
    }

    /**
     * Clear all files - Only owner can clear
     */
    function clearAllFiles() public onlyOwner {
        uint256 totalFiles = fileHashes.length;
        _clearAllFiles();
        emit FilesCleared(msg.sender, totalFiles, block.timestamp);
    }

    /**
     * Delete multiple files by fileHash - Only owner can delete
     */
    function batchDeleteFile(string[] memory fileHashesToDelete) public onlyOwner {
        require(fileHashesToDelete.length > 0, "No file hashes provided");

        uint256 deletedCount = 0;
        for (uint256 i = 0; i < fileHashesToDelete.length; i++) {
            string memory fileHash = fileHashesToDelete[i];
            if (files[fileHash].exists) {
                files[fileHash].exists = false;
                _removeFromFileHashes(fileHash);
                deletedCount++;
                emit FileDeleted(fileHash, msg.sender, block.timestamp);
            }
        }

        emit BatchFileDeleted(msg.sender, deletedCount, block.timestamp);
    }

    /**
     * Internal function to clear all files
     */
    function _clearAllFiles() private {
        // Mark all files as non-existent
        for (uint256 i = 0; i < fileHashes.length; i++) {
            files[fileHashes[i]].exists = false;
        }

        // Clear the fileHashes array
        delete fileHashes;
    }

    /**
     * Internal function to remove a fileHash from the fileHashes array
     */
    function _removeFromFileHashes(string memory fileHashToRemove) private {
        for (uint256 i = 0; i < fileHashes.length; i++) {
            if (keccak256(bytes(fileHashes[i])) == keccak256(bytes(fileHashToRemove))) {
                // Move the last element to the current position
                fileHashes[i] = fileHashes[fileHashes.length - 1];
                // Remove the last element
                fileHashes.pop();
                break;
            }
        }
    }

    /**
     * Get contract owner address
     */
    function getOwner() public view returns (address) {
        return owner;
    }

    /**
     * Transfer ownership (optional security feature)
     */
    function transferOwnership(address newOwner) public onlyOwner {
        require(newOwner != address(0), "New owner cannot be zero address");
        owner = newOwner;
    }
}