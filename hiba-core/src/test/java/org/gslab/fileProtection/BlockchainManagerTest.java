package org.gslab.fileProtection;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.gslab.fileProtection.database.DatabaseManager;
import org.gslab.fileProtection.service.BlockchainService;
import org.gslab.fileProtection.service.FileProtectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for BlockchainManager class
 */
public class BlockchainManagerTest {

    @Test
    @DisplayName("Test getFileProtectionService with default settings")
    void testGetFileProtectionServiceDefault() {
        System.out.println("Testing getFileProtectionService with default settings...");
        FileProtectionService service = BlockchainFileProtectManager.getFileProtectionService();
        assertNotNull(service, "FileProtectionService should be created with default settings");
        System.out.println("getFileProtectionService with default settings test passed");
    }

    @Test
    @DisplayName("Test getFileProtectionService with BlockchainService")
    void testGetFileProtectionServiceWithBlockchainService() {
        System.out.println("Testing getFileProtectionService with BlockchainService...");
        // Create a mock BlockchainService
        BlockchainService mockBlockchainService = Mockito.mock(BlockchainService.class);

        // Get FileProtectionService with BlockchainService
        FileProtectionService service = BlockchainFileProtectManager.getFileProtectionService(mockBlockchainService);

        assertNotNull(service, "FileProtectionService should be created with BlockchainService");
        System.out.println("getFileProtectionService with BlockchainService test passed");
    }

    @Test
    @DisplayName("Test getFileProtectionService with DatabaseManager")
    void testGetFileProtectionServiceWithDatabaseManager() {
        System.out.println("Testing getFileProtectionService with DatabaseManager...");
        // Create a mock DatabaseManager
        DatabaseManager mockDatabaseManager = Mockito.mock(DatabaseManager.class);

        // Get FileProtectionService with DatabaseManager
        FileProtectionService service = BlockchainFileProtectManager.getFileProtectionService(mockDatabaseManager);

        assertNotNull(service, "FileProtectionService should be created with DatabaseManager");
        System.out.println("getFileProtectionService with DatabaseManager test passed");
    }

    @Test
    @DisplayName("Test getFileProtectionService with BlockchainService and DatabaseManager")
    void testGetFileProtectionServiceWithBlockchainServiceAndDatabaseManager() {
        System.out.println("Testing getFileProtectionService with BlockchainService and DatabaseManager...");
        // Create mock services
        BlockchainService mockBlockchainService = Mockito.mock(BlockchainService.class);
        DatabaseManager mockDatabaseManager = Mockito.mock(DatabaseManager.class);

        // Get FileProtectionService with both services
        FileProtectionService service =
                BlockchainFileProtectManager.getFileProtectionService(mockBlockchainService, mockDatabaseManager);

        assertNotNull(service, "FileProtectionService should be created with BlockchainService and DatabaseManager");
        System.out.println("getFileProtectionService with BlockchainService and DatabaseManager test passed");
    }
}
