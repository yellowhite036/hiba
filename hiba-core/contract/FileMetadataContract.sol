// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

/**
 * FileProtectionContract Smart Contract
 * Stores file metadata on the blockchain for integrity verification
 */
contract FileProtectionContract {

    address private owner;

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

    constructor() {
        owner = msg.sender;
    }

    modifier onlyOwner() {
        require(msg.sender == owner, "Only contract owner can perform this action");
        _;
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
    ) public {
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
        } else {
            emit FileUpdated(fileHash, msg.sender, block.timestamp);
        }
    }

    /**
     * Store single file using FileMetadata struct
     */
    function storeFileWithStruct(FileMetadata memory fileData) public {
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
        } else {
            emit FileUpdated(fileData.fileHash, msg.sender, block.timestamp);
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
    ) public {
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
            } else {
                updatedFilesCount++;
                emit FileUpdated(fileHashes_[i], msg.sender, block.timestamp);
            }
        }

        emit BatchFileStored(msg.sender, newFilesCount + updatedFilesCount, block.timestamp);
    }

    /**
     * Batch store multiple files using FileMetadata struct array (NEW!)
     */
    function batchStoreFileWithStruct(FileMetadata[] memory fileDataArray) public {
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
            } else {
                updatedFilesCount++;
                emit FileUpdated(fileData.fileHash, msg.sender, block.timestamp);
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