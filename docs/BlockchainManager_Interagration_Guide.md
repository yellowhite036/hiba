# BlockchainManager (BCM) - Claude Integration Guide

## 📋 Overview

BlockchainManager (BCM) is a Java-based blockchain file protection system that provides file integrity verification, blockchain recording, and decentralized storage management. This guide is specifically designed for Claude AI to understand how to integrate and use BCM functionality in other projects.

## 🎯 Core Functionality

BCM provides the following key services:
- **File Protection**: Register files with SHA-256 hash verification
- **Blockchain Recording**: Store file metadata on blockchain (immutable records)
- **Integrity Verification**: Check if files have been modified
- **Local Database**: SQLite-based local storage for quick queries
- **Multiple Service Modes**: Mock mode for testing, Web3j for actual blockchain

## 🏗️ Architecture Overview

```
BlockchainManager (Entry Point)
├── FileProtectionService (Main Service)
├── BlockchainService (Interface)
│   ├── MockBlockchainServiceImpl (Testing)
│   └── Web3jBlockchainServiceImpl (Production)
├── DatabaseManager (Data Storage)
└── Utilities (Hash, Config, File Management)
```

## 📚 API Reference

### 1. Entry Point - BlockchainManager Class

**Location**: `org.aiia.blockchain.BlockchainManager`

**Factory Methods**:
```java
// Get service with default configuration
public static FileProtectionService getFileProtectionService()

// Get service with custom blockchain service
public static FileProtectionService getFileProtectionService(BlockchainService blockchainService)

// Get service with custom database manager
public static FileProtectionService getFileProtectionService(DatabaseManager databaseManager)

// Get service with both custom services
public static FileProtectionService getFileProtectionService(BlockchainService blockchainService, DatabaseManager databaseManager)
```

### 2. Main Service - FileProtectionService

**Location**: `org.aiia.blockchain.service.FileProtectionService`

#### Core Methods:

**File Protection**:
```java
// Protect a file by registering it to blockchain
Transaction protectFile(File file, String uploader, String description)
```

**File Search**:
```java
// Find transaction by file name
Transaction findTransactionByFileName(String fileName)

// Find transaction by file ID
Transaction findTransactionByFileId(String fileId)
```

**Integrity Verification**:
```java
// Verify if file has been modified
boolean verifyFileIntegrity(String fileName, String currentFilePath)
```

**Data Retrieval**:
```java
// Get all protected files
Collection<FileMetadata> getAllProtectedFiles()

// Get all transactions
Collection<Transaction> getAllTransactions()

// Get system statistics
Map<String, Object> getStatistics()
```

**Blockchain Recovery**:
```java
// Recover file metadata directly from blockchain using transaction hash
FileMetadata recoverFromBlockchain(String blockchainTxHash) throws Exception

// Find transaction by blockchain transaction hash (if exists in local database)
Transaction findTransactionByBlockchainTxHash(String blockchainTxHash)

// Get blockchain transaction status
String getBlockchainTransactionStatus(String blockchainTxHash)

// Verify file integrity using blockchain transaction hash
boolean verifyIntegrityByBlockchainTxHash(String blockchainTxHash, String expectedHash)
```

### 3. Data Models

#### FileMetadata Class
**Location**: `org.aiia.blockchain.model.FileMetadata`

```java
public class FileMetadata {
    private String id;           // Unique file identifier
    private String fileName;     // Original file name
    private String fileHash;     // SHA-256 hash of file content
    private long fileSize;       // File size in bytes
    private String uploadTime;   // Upload timestamp
    private String uploader;     // Person who uploaded the file
    private String filePath;     // Original file path
    private String description;  // File description
    
    // Getters and setters available
}
```

#### Transaction Class
**Location**: `org.aiia.blockchain.model.Transaction`

```java
public class Transaction {
    private String txId;              // Transaction ID
    private String fileId;            // Associated file ID
    private String fileName;          // File name
    private String blockchainTxHash;  // Blockchain transaction hash
    private String timestamp;         // Transaction timestamp
    private TransactionStatus status; // PENDING, SUCCESS, FAILED
    private String errorMessage;      // Error message if failed
    private FileMetadata metadata;    // Associated file metadata
    
    // Getters and setters available
}
```

#### TransactionStatus Enum
**Location**: `org.aiia.blockchain.model.TransactionStatus`

```java
public enum TransactionStatus {
    PENDING,   // Transaction is being processed
    SUCCESS,   // Transaction completed successfully
    FAILED     // Transaction failed
}
```

## 🔧 Configuration

### Default Configuration
BCM uses the following configuration sources (in priority order):
1. **Environment Variables** (`.env` file)
2. **System Properties**
3. **application.properties**

### Key Configuration Properties:

```properties
# Database Configuration
database.url=jdbc:sqlite:blockchain_manager.db

# Blockchain Service Type (mock/web3j)
blockchain.service.type=mock

# Web3j Configuration (when using real blockchain)
blockchain.rpc.url=https://sepolia.infura.io/v3/your_project_id
blockchain.network.id=11155111
blockchain.contract.address=0xYourContractAddress
blockchain.private.key=your_private_key_without_0x_prefix
```

### Environment Variables (Recommended for sensitive data):
```bash
BLOCKCHAIN_PRIVATE_KEY=your_private_key_here
BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/v3/your_infura_project_id
CONTRACT_ADDRESS=0xYourDeployedContractAddress
```

## 💻 Usage Examples for Claude

### Basic Integration Example

```java
import org.aiia.blockchain.BlockchainManager;
import org.aiia.blockchain.service.FileProtectionService;
import org.aiia.blockchain.model.Transaction;
import org.aiia.blockchain.model.FileMetadata;
import java.io.File;
import java.util.Collection;

public class ClaudeBlockchainIntegration {
    
    private final FileProtectionService bcm;
    
    public ClaudeBlockchainIntegration() {
        // Initialize with default configuration
        this.bcm = BlockchainManager.getFileProtectionService();
    }
    
    // Protect a file that Claude generated
    public String protectGeneratedFile(String filePath, String description) {
        try {
            File file = new File(filePath);
            Transaction transaction = bcm.protectFile(file, "Claude-AI", description);
            
            if (transaction.getStatus() == TransactionStatus.SUCCESS) {
                return "File protected successfully. File ID: " + transaction.getFileId() + 
                       ", Blockchain TX: " + transaction.getBlockchainTxHash();
            } else {
                return "Protection failed: " + transaction.getErrorMessage();
            }
        } catch (Exception e) {
            return "Error protecting file: " + e.getMessage();
        }
    }
    
    // Recover file metadata from blockchain using transaction hash
    public FileMetadata recoverFileFromBlockchain(String blockchainTxHash) {
        try {
            return bcm.recoverFromBlockchain(blockchainTxHash);
        } catch (Exception e) {
            System.err.println("Failed to recover file from blockchain: " + e.getMessage());
            return null;
        }
    }
    
    // Check blockchain transaction status
    public String checkTransactionStatus(String blockchainTxHash) {
        return bcm.getBlockchainTransactionStatus(blockchainTxHash);
    }
    
    // Verify file integrity using blockchain transaction hash
    public boolean verifyFileByBlockchainTx(String blockchainTxHash, String currentFilePath) {
        try {
            // First get the expected hash from blockchain
            FileMetadata blockchainMetadata = bcm.recoverFromBlockchain(blockchainTxHash);
            String expectedHash = blockchainMetadata.getFileHash();
            
            // Verify using blockchain transaction hash
            return bcm.verifyIntegrityByBlockchainTxHash(blockchainTxHash, expectedHash);
        } catch (Exception e) {
            System.err.println("Verification failed: " + e.getMessage());
            return false;
        }
    }
    
    // Verify integrity of a previously protected file
    public boolean verifyFileIntegrity(String fileName, String currentPath) {
        return bcm.verifyFileIntegrity(fileName, currentPath);
    }
    
    // Search for a protected file
    public FileMetadata findProtectedFile(String fileName) {
        Transaction transaction = bcm.findTransactionByFileName(fileName);
        return transaction != null ? transaction.getMetadata() : null;
    }
    
    // Get all files protected by Claude
    public Collection<FileMetadata> getAllClaudeProtectedFiles() {
        return bcm.getAllProtectedFiles().stream()
                .filter(file -> "Claude-AI".equals(file.getUploader()))
                .collect(Collectors.toList());
    }
    
    // Get system statistics
    public String getSystemStats() {
        Map<String, Object> stats = bcm.getStatistics();
        return String.format("Total Files: %s, Total Transactions: %s, Success Rate: %.2f%%",
                stats.get("totalFiles"),
                stats.get("totalTransactions"),
                calculateSuccessRate(stats));
    }
    
    private double calculateSuccessRate(Map<String, Object> stats) {
        int total = (Integer) stats.get("totalTransactions");
        int successful = (Integer) stats.get("successfulTransactions");
        return total > 0 ? (successful * 100.0 / total) : 0.0;
    }
}
```

### Advanced Usage - Custom Configuration

```java
import org.aiia.blockchain.service.impl.MockBlockchainServiceImpl;
import org.aiia.blockchain.util.impl.SQLiteDatabaseManagerImpl;

public class CustomBCMIntegration {
    
    public FileProtectionService createCustomBCM() {
        // Create custom database manager
        DatabaseManager customDb = new SQLiteDatabaseManagerImpl("custom_blockchain.db");
        
        // Create mock blockchain service for testing
        BlockchainService mockService = new MockBlockchainServiceImpl();
        
        // Create service with custom components
        return BlockchainManager.getFileProtectionService(mockService, customDb);
    }
}
```

### Error Handling Pattern

```java
public class BCMErrorHandling {
    
    public void protectFileWithErrorHandling(String filePath) {
        try {
            FileProtectionService bcm = BlockchainManager.getFileProtectionService();
            File file = new File(filePath);
            
            // Validate file exists
            if (!file.exists()) {
                throw new IllegalArgumentException("File does not exist: " + filePath);
            }
            
            Transaction transaction = bcm.protectFile(file, "Claude-AI", "Auto-generated by Claude");
            
            // Check transaction status
            switch (transaction.getStatus()) {
                case SUCCESS:
                    System.out.println("File protected successfully: " + transaction.getFileId());
                    break;
                case PENDING:
                    System.out.println("Protection in progress: " + transaction.getTxId());
                    break;
                case FAILED:
                    System.err.println("Protection failed: " + transaction.getErrorMessage());
                    break;
            }
            
        } catch (Exception e) {
            System.err.println("Error during file protection: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

## 🎭 Service Modes

### 1. Mock Mode (Development/Testing)
```properties
blockchain.service.type=mock
```
- **Use Case**: Development, testing, CI/CD pipelines
- **Behavior**: Simulates blockchain operations without actual network calls
- **Benefits**: Fast, reliable, no network dependencies

### 2. Web3j Mode (Production)
```properties
blockchain.service.type=web3j
blockchain.rpc.url=https://mainnet.infura.io/v3/your_project_id
blockchain.contract.address=0xYourContractAddress
blockchain.private.key=your_private_key
```
- **Use Case**: Production environments with real blockchain
- **Behavior**: Actual blockchain transactions on Ethereum-compatible networks
- **Requirements**: Valid RPC URL, deployed smart contract, private key with gas

## 🚨 Important Considerations for Claude

### 1. Security Best Practices
- **Never hardcode private keys** in source code
- **Use environment variables** for sensitive configuration
- **Validate file paths** before processing
- **Handle errors gracefully** to prevent crashes

### 2. Performance Considerations
- **Mock mode** for development (fast, no network delays)
- **Batch operations** when protecting multiple files
- **Database queries** are optimized for small to medium datasets
- **File size limits**: Recommended < 100MB per file

### 3. Blockchain Recovery Features
- **Critical**: Always save the `blockchainTxHash` from protection transactions
- **Disaster Recovery**: Use `recoverFromBlockchain(blockchainTxHash)` when local database is lost
- **Cross-Verification**: Compare local database with blockchain data for consistency
- **Transaction Status**: Check `getBlockchainTransactionStatus()` before trusting data
- **Network Independence**: Blockchain recovery works even without local database

### 4. Common Use Cases for Claude

#### Basic File Protection
```java
// 1. Protect code files generated by Claude
Transaction tx = bcm.protectFile(new File("generated_code.java"), "Claude-AI", "Auto-generated Java class");
String blockchainTxHash = tx.getBlockchainTxHash(); // Save this for recovery!

// 2. Verify if user-provided file matches protected version
boolean isValid = bcm.verifyFileIntegrity("user_file.txt", "/path/to/current/file.txt");

// 3. Search for previously protected configurations
Transaction config = bcm.findTransactionByFileName("config.properties");

// 4. Get all files protected during current session
Collection<FileMetadata> sessionFiles = bcm.getAllProtectedFiles();
```

#### Blockchain Recovery Scenarios
```java
// 1. Recover file metadata when local database is lost
FileMetadata recoveredFile = bcm.recoverFromBlockchain("0x1234567890abcdef...");

// 2. Verify file integrity using only blockchain transaction hash
boolean isValid = bcm.verifyIntegrityByBlockchainTxHash("0x1234567890abcdef...", currentFileHash);

// 3. Check if blockchain transaction is confirmed
String status = bcm.getBlockchainTransactionStatus("0x1234567890abcdef...");
// Returns: "PENDING", "CONFIRMED", "FAILED", or "ERROR"

// 4. Cross-verify file between local DB and blockchain
Transaction localTx = bcm.findTransactionByFileName("important_file.txt");
if (localTx != null) {
    FileMetadata blockchainMetadata = bcm.recoverFromBlockchain(localTx.getBlockchainTxHash());
    boolean isConsistent = localTx.getMetadata().getFileHash().equals(blockchainMetadata.getFileHash());
}
```

#### Disaster Recovery Pattern
```java
// Use case: Local database corrupted, only blockchain TX hash available
public FileMetadata recoverLostFile(String blockchainTxHash) {
    try {
        // Step 1: Recover metadata from blockchain
        FileMetadata recoveredMetadata = bcm.recoverFromBlockchain(blockchainTxHash);
        
        // Step 2: Verify transaction status
        String status = bcm.getBlockchainTransactionStatus(blockchainTxHash);
        if (!"CONFIRMED".equals(status)) {
            throw new Exception("Transaction not confirmed: " + status);
        }
        
        // Step 3: Log recovery information
        System.out.println("Recovered file: " + recoveredMetadata.getFileName());
        System.out.println("Original uploader: " + recoveredMetadata.getUploader());
        System.out.println("File hash: " + recoveredMetadata.getFileHash());
        System.out.println("Upload time: " + recoveredMetadata.getUploadTime());
        
        return recoveredMetadata;
        
    } catch (Exception e) {
        System.err.println("Recovery failed for TX: " + blockchainTxHash + " - " + e.getMessage());
        return null;
    }
}
```

### 5. Error Scenarios to Handle
- **File not found**: Validate file existence before protection
- **Network issues**: Use mock mode as fallback
- **Invalid configuration**: Provide clear error messages
- **Database locked**: Implement retry logic for concurrent access

## 📦 Maven Dependency

To use BCM in your project, add this dependency:

```xml
<dependency>
    <groupId>org.gslab</groupId>
    <artifactId>BlockChainManager</artifactId>
    <version>0.1.1</version>
</dependency>
```

## 🧪 Testing Integration

```java
@Test
public void testBCMIntegration() {
    // Use mock mode for testing
    System.setProperty("blockchain.service.type", "mock");
    
    FileProtectionService bcm = BlockchainManager.getFileProtectionService();
    
    // Create test file
    File testFile = new File("test.txt");
    // ... create file content
    
    // Test protection
    Transaction transaction = bcm.protectFile(testFile, "Test", "Test description");
    assertEquals(TransactionStatus.SUCCESS, transaction.getStatus());
    
    // Test verification
    boolean isValid = bcm.verifyFileIntegrity("test.txt", testFile.getAbsolutePath());
    assertTrue(isValid);
    
    // Test search
    Transaction found = bcm.findTransactionByFileName("test.txt");
    assertNotNull(found);
    assertEquals(transaction.getFileId(), found.getFileId());
}
```

## 📞 Support and Troubleshooting

### Common Issues:
1. **Database locked**: Ensure only one BCM instance per database file
2. **File not found**: Use absolute paths when possible
3. **Network timeout**: Check RPC URL and network connectivity
4. **Private key format**: Remove '0x' prefix from private keys

### Debug Mode:
Enable debug logging by setting:
```properties
logging.level.org.aiia.blockchain=DEBUG
```

---

This guide provides comprehensive information for Claude to effectively integrate and use BlockchainManager functionality. The examples are designed to be copy-paste ready for common use cases while maintaining best practices for security and error handling.