package org.gslab.Tool;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 支援優先級的資源清理管理器
 * <p>
 * 這個管理器負責統一管理系統中所有需要清理的資源（如網路連接、數據庫連接、文件句柄等）， 確保在程式關閉時所有資源都能被正確釋放。
 *
 * <h3>特性：</h3>
 * <ul>
 *   <li>支援優先級清理：優先級高的資源會先被清理（數字越大優先級越高）</li>
 *   <li>LIFO順序：相同優先級的資源按後進先出(LIFO)順序清理</li>
 *   <li>線程安全：使用讀寫鎖保證並發安全</li>
 *   <li>自動shutdown hook：可自動註冊JVM關閉鉤子</li>
 *   <li>錯誤隔離：單個資源清理失敗不影響其他資源</li>
 * </ul>
 *
 * <h3>使用方式：</h3>
 * <pre>{@code
 * // 獲取管理器實例
 * ResourceCleanupManager manager = ResourceCleanupManager.getManager();
 *
 * // 添加資源（使用預設優先級）
 * manager.addResource(connection, "Database Connection");
 *
 * // 添加資源（指定優先級）
 * manager.addResource(serverSocket, "Main Server Socket", ResourcePriority.NETWORK_CONNECTIONS);
 *
 * // 程式結束時自動清理所有資源（如果註冊了shutdown hook）
 * }</pre>
 *
 * <h3>優先級設計：</h3>
 * <ul>
 *   <li>CRITICAL (1000): 關鍵資源，最先清理</li>
 *   <li>HIGH (800): 高優先級資源</li>
 *   <li>NORMAL (500): 一般資源（預設）</li>
 *   <li>LOW (200): 低優先級資源</li>
 *
 *   <li>APPLICATION_SERVICE (700): 應用服務層資源</li>
 *   <li>NETWORK_CONNECTION (600): 網路連接資源</li>
 *   <li>FILE_HANDLE (400): 檔案處置資源</li>
 *   <li>DATABASE_CONNECTION (300): 資料庫連接</li>
 *   <li>THREAD_POOL (150): 執行續池資源</li>
 *   <li>INFRASTRUCTURE (100): 基礎設施資源，最後清理</li>
 * </ul>
 *
 * @author HiBA Core Team
 * @version 1.0
 * @see AutoCloseable
 * @see ResourcePriority
 * @since 1.0
 */
public class ResourceCleanupManager {

    private static final Logger logger = LoggerFactory.getLogger(ResourceCleanupManager.class);
    private static final Object instanceLock = new Object();
    private static volatile ResourceCleanupManager instance;
    // 使用 TreeMap 按優先級分組，每個優先級內部使用 Stack
    private final TreeMap<Integer, Stack<ResourceWrapper>> priorityGroups = new TreeMap<>(Collections.reverseOrder());

    // 讀寫鎖
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    private volatile boolean shutdownInProgress = false;
    private volatile boolean shutdownHookRegistered = false;

    /**
     * 私有構造函數，防止直接實例化 使用 {@link #getInstance()} 或 {@link #getManager()} 獲取實例
     */
    private ResourceCleanupManager() {
        // 私有構造函數 - Singleton 模式
    }

    /**
     * 獲取{@link ResourceCleanupManager}的單例實例
     * <p>
     * 使用雙重檢查鎖定模式確保線程安全的單例創建
     *
     * @return {@link ResourceCleanupManager}的唯一實例
     */
    public static ResourceCleanupManager getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new ResourceCleanupManager();
                }
            }
        }
        return instance;
    }

    /**
     * 獲取{@link ResourceCleanupManager}實例的便捷方法
     * <p>
     * 等同於 {@link #getInstance()}，提供更簡潔的方法名
     *
     * @return {@link ResourceCleanupManager}的唯一實例
     */
    public static ResourceCleanupManager getManager() {
        return getInstance();
    }

    /**
     * 註冊 JVM shutdown hook
     * <p>
     * 在JVM正常關閉時自動觸發資源清理。 這個方法是線程安全的，多次調用只會註冊一次hook。
     * <p>
     * <strong>注意：</strong> shutdown hook 只在正常關閉時觸發，
     * 在程式被強制終止（如 kill -9）時不會執行。
     *
     * @see #cleanup()
     */
    public void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            synchronized (instanceLock) {
                if (!shutdownHookRegistered) {

                    Runtime.getRuntime()
                            .addShutdownHook(new Thread(
                                    () -> {
                                        logger.atDebug().log("JVM shutdown hook triggered");

                                        try {
                                            logger.atDebug()
                                                    .addKeyValue("resourceCount", getResourceCount())
                                                    .log("Current resource count");

                                            long startTime = System.currentTimeMillis();
                                            logger.atInfo().log(
                                                    "JVM shutdown detected, cleaning up resources by priority...");

                                            cleanup();

                                            long duration = System.currentTimeMillis() - startTime;
                                            logger.atInfo()
                                                    .addKeyValue("durationMs", duration)
                                                    .log("JVM shutdown complete");

                                        } catch (Exception e) {
                                            logger.atError()
                                                    .addKeyValue("errorMessage", e.getMessage())
                                                    .setCause(e)
                                                    .log("Shutdown error");
                                        } finally {
                                            logger.atInfo().log("Closing Log4j logger...");
                                            // 為了避免log4j的shutdownHook搶在我們之前執行，導致無法輸出log，
                                            // 在log4j2.xml裡取消shutdownHook，並在這裡手動關閉log4j
                                            org.apache.logging.log4j.LogManager.shutdown();
                                            logger.atInfo().log("Log4j logger closed");
                                        }
                                    },
                                    "CleanupManager-ShutdownHook"));

                    shutdownHookRegistered = true;
                    logger.atInfo().log("CleanupManager shutdown hook registered");
                }
            }
        }
    }

    /**
     * 添加資源並指定數字優先級
     * <p>
     * 將資源添加到指定優先級的清理隊列中。優先級數字越大，越早被清理。 相同優先級的資源按照後進先出(LIFO)順序清理。
     *
     * @param resource    需要清理的資源，必須實現 {@link AutoCloseable} 介面
     * @param description 資源的描述性名稱，用於日誌記錄和調試
     * @param priority    資源優先級（數字越大越先清理）
     * @throws IllegalArgumentException 如果 resource 為 null
     * @see ResourcePriority
     * @see #addResource(AutoCloseable, String)
     * @see #addResource(AutoCloseable)
     */
    public void addResource(AutoCloseable resource, String description, ResourcePriority priority) {
        if (resource == null) {
            throw new IllegalArgumentException("Resource cannot be null");
        }

        writeLock.lock();
        try {
            if (shutdownInProgress) {
                logger.atWarn().addKeyValue("description", description).log("Cannot add resource during shutdown");
                return;
            }

            // 獲取或創建優先級組
            Stack<ResourceWrapper> stack = priorityGroups.computeIfAbsent(priority.value, k -> new Stack<>());

            ResourceWrapper wrapper = new ResourceWrapper(resource, description, priority);
            stack.push(wrapper);

            logger.atInfo().log(
                    "Added resource [ResourcePriority:{}({})] {} (group size: {}, total groups: {})",
                    priority,
                    priority.value,
                    description,
                    stack.size(),
                    priorityGroups.size());

        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 添加資源（使用預設優先級）
     * <p>
     * 使用 {@link ResourcePriority#NORMAL} 作為預設優先級
     *
     * @param resource    需要清理的資源
     * @param description 資源描述
     * @throws IllegalArgumentException 如果 resource 為 null
     */
    public void addResource(AutoCloseable resource, String description) {
        addResource(resource, description, ResourcePriority.NORMAL);
    }

    /**
     * 添加資源（自動生成描述，使用預設優先級）
     * <p>
     * 描述會自動生成為：類名@hashCode 的格式 使用 {@link ResourcePriority#NORMAL} 作為預設優先級
     *
     * @param resource 需要清理的資源
     * @throws IllegalArgumentException 如果 resource 為 null
     */
    public void addResource(AutoCloseable resource) {
        addResource(resource, generateDescription(resource), ResourcePriority.NORMAL);
    }

    /**
     * 便捷方法：添加高優先級資源
     * <p>
     * 等同於 {@code addResource(resource, description, ResourcePriority.HIGH)}
     *
     * @param resource    需要清理的資源
     * @param description 資源描述
     */
    public void addHighPriorityResource(AutoCloseable resource, String description) {
        addResource(resource, description, ResourcePriority.HIGH);
    }

    /**
     * 便捷方法：添加低優先級資源
     * <p>
     * 等同於 {@code addResource(resource, description, ResourcePriority.LOW)}
     *
     * @param resource    需要清理的資源
     * @param description 資源描述
     */
    public void addLowPriorityResource(AutoCloseable resource, String description) {
        addResource(resource, description, ResourcePriority.LOW);
    }

    /**
     * 便捷方法：添加應用服務層資源
     * <p>
     * 等同於 {@code addResource(resource, description, ResourcePriority.APPLICATION_SERVICES)}
     *
     * @param resource    需要清理的資源
     * @param description 資源描述
     */
    public void addApplicationServiceResource(AutoCloseable resource, String description) {
        addResource(resource, description, ResourcePriority.APPLICATION_SERVICE);
    }

    /**
     * 便捷方法：添加網路連接資源
     * <p>
     * 等同於 {@code addResource(resource, description, ResourcePriority.NETWORK_CONNECTIONS)}
     *
     * @param resource    需要清理的資源
     * @param description 資源描述
     */
    public void addNetworkConnectionResource(AutoCloseable resource, String description) {
        addResource(resource, description, ResourcePriority.NETWORK_CONNECTION);
    }

    /**
     * 便捷方法：添加基礎設施資源（最低優先級）
     * <p>
     * 等同於 {@code addResource(resource, description, ResourcePriority.INFRASTRUCTURE)} 基礎設施資源會在最後被清理
     *
     * @param resource    需要清理的資源
     * @param description 資源描述
     */
    public void addInfrastructureResource(AutoCloseable resource, String description) {
        addResource(resource, description, ResourcePriority.INFRASTRUCTURE);
    }

    /**
     * 移除並關閉特定資源
     * <p>
     * 在所有優先級組中查找指定的資源，找到後移除並關閉它。 使用對象引用比較（==）來查找資源。
     *
     * @param resource 要移除的資源對象
     * @return true 如果找到並成功移除資源，false 如果未找到資源
     */
    public boolean removeResource(AutoCloseable resource) {
        writeLock.lock();
        try {
            if (shutdownInProgress) {
                return false;
            }

            Iterator<Map.Entry<Integer, Stack<ResourceWrapper>>> mapIterator =
                    priorityGroups.entrySet().iterator();
            while (mapIterator.hasNext()) {
                Map.Entry<Integer, Stack<ResourceWrapper>> entry = mapIterator.next();
                Stack<ResourceWrapper> stack = entry.getValue();

                Iterator<ResourceWrapper> stackIterator = stack.iterator();
                while (stackIterator.hasNext()) {
                    ResourceWrapper wrapper = stackIterator.next();
                    if (wrapper.resource == resource) {
                        stackIterator.remove(); // 安全地從集合中移除當前元素

                        // 如果組變空了，移除它
                        if (stack.isEmpty()) {
                            mapIterator.remove(); // 使用Map的Iterator安全移除空組
                        }

                        try {
                            wrapper.resource.close();
                            logger.atInfo().log(
                                    "✓ Found and closed resource [ResourcePriority:{}] {}",
                                    wrapper.priority,
                                    wrapper.description);
                            return true;
                        } catch (Exception e) {
                            logger.atError()
                                    .log("✗ Error closing found resource {}: {}", wrapper.description, e.getMessage());
                            return true;
                        }
                    }
                }
            }

            logger.atWarn().log("Resource not found in cleanup manager");
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 清理特定優先級的所有資源
     * <p>
     * 關閉並清理指定優先級組中的所有資源。 資源按照LIFO順序被清理。
     *
     * @param priority 要清理的優先級
     */
    public void cleanupPriority(ResourcePriority priority) {
        writeLock.lock();
        try {
            Stack<ResourceWrapper> stack = priorityGroups.get(priority.value);
            if (stack == null || stack.isEmpty()) {
                logger.atInfo().log("No resources found for priority {}", priority);
                return;
            }

            logger.atInfo().log("Cleaning up priority {} ({} resources)...", priority, stack.size());

            int cleanedCount = 0;
            int errorCount = 0;

            while (!stack.isEmpty()) {
                ResourceWrapper wrapper = stack.pop();
                cleanedCount++;

                try {
                    wrapper.resource.close();
                    logger.atInfo().log("✓ [{}] Closed: {}", cleanedCount, wrapper.description);
                } catch (Exception e) {
                    errorCount++;
                    logger.atError()
                            .log("✗ [{}] Error closing {}: {}", cleanedCount, wrapper.description, e.getMessage());
                }
            }

            priorityGroups.remove(priority.value);
            logger.atInfo().log(
                    "ResourcePriority {} cleanup completed: {} success, {} errors",
                    priority,
                    (cleanedCount - errorCount),
                    errorCount);

        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 清理所有資源（按優先級順序）
     * <p>
     * 這是核心的清理方法，會按照優先級從高到低的順序清理所有資源。 相同優先級內的資源按LIFO順序清理。
     * <p>
     * 此方法是線程安全的，如果清理已在進行中，後續調用會被忽略。 個別資源的清理失敗不會影響其他資源的清理過程。
     *
     * @see #registerShutdownHook()
     */
    public void cleanup() {
        // 先獲取寫入鎖來設置 shutdownInProgress
        writeLock.lock();
        try {
            if (shutdownInProgress) {
                logger.atWarn().log("Cleanup already in progress, skipping...");
                return;
            }

            shutdownInProgress = true;

            if (priorityGroups.isEmpty()) {
                logger.atInfo().log("No resources to cleanup");
                return;
            }
        } finally {
            // 設置完狀態後立即釋放寫入鎖，避免在 shutdown hook 中死鎖
            writeLock.unlock();
        }

        // 現在不持有任何鎖來進行清理，因為 shutdownInProgress = true 已經防止新資源添加

        int totalResources = getTotalResourceCount();

        logger.atInfo().log(
                "Starting cleanup of {} resources across {} priority levels...", totalResources, priorityGroups.size());

        int globalCleanedCount = 0;
        int globalErrorCount = 0;

        // 按優先級從高到低清理（TreeMap 已按降序排列）
        // 直接訪問 priorityGroups，因為 shutdownInProgress = true 防止了新的修改
        for (Map.Entry<Integer, Stack<ResourceWrapper>> entry : priorityGroups.entrySet()) {
            int priority = entry.getKey();
            Stack<ResourceWrapper> stack = entry.getValue();

            if (stack.isEmpty()) {
                continue;
            }

            logger.atInfo().log(
                    "--- Cleaning ResourcePriority {} ({} resources) ---",
                    ResourcePriority.fromValue(priority),
                    stack.size());

            int priorityCleanedCount = 0;
            int priorityErrorCount = 0;

            while (!stack.isEmpty()) {
                ResourceWrapper wrapper = stack.pop();
                globalCleanedCount++;
                priorityCleanedCount++;

                try {
                    wrapper.resource.close();
                    logger.atInfo().log("✓ [{}/{}] {}", globalCleanedCount, totalResources, wrapper.description);
                    logger.atDebug().log("Closed resource: {}", wrapper);
                } catch (Exception e) {
                    globalErrorCount++;
                    priorityErrorCount++;
                    logger.atError()
                            .log(
                                    "✗ [{}/{}] {}: {}",
                                    globalCleanedCount,
                                    totalResources,
                                    wrapper.description,
                                    e.getMessage());
                }
            }

            logger.atInfo().log(
                    "ResourcePriority {} completed: {} success, {} errors",
                    ResourcePriority.fromValue(priority),
                    (priorityCleanedCount - priorityErrorCount),
                    priorityErrorCount);
        }

        priorityGroups.clear();

        logger.atInfo().log("=== Cleanup Summary ===");
        logger.atInfo().log(
                "Total cleaned: {} success, {} errors", (globalCleanedCount - globalErrorCount), globalErrorCount);
    }

    /**
     * 顯示當前管理器狀態
     * <p>
     * 輸出管理器的詳細狀態信息，包括：
     * <ul>
     *   <li>總資源數量</li>
     *   <li>優先級組數量</li>
     *   <li>shutdown hook 註冊狀態</li>
     *   <li>清理進行狀態</li>
     *   <li>各優先級的資源列表</li>
     * </ul>
     * 主要用於調試和監控。
     */
    public void printStatus() {
        readLock.lock();
        try {
            logger.atInfo().log("=== CleanupManager Status ===");
            logger.atInfo().log("Total resources: {}", getTotalResourceCount());
            logger.atInfo().log("ResourcePriority groups: {}", priorityGroups.size());
            logger.atInfo().log("Shutdown hook registered: {}", shutdownHookRegistered);
            logger.atInfo().log("Shutdown in progress: {}", shutdownInProgress);

            if (!priorityGroups.isEmpty()) {
                logger.atInfo().log("Resources by priority (high to low):");
                for (Map.Entry<Integer, Stack<ResourceWrapper>> entry : priorityGroups.entrySet()) {
                    int priority = entry.getKey();
                    Stack<ResourceWrapper> stack = entry.getValue();

                    logger.atInfo().log(
                            "  ResourcePriority {} ({}) : {} resources",
                            ResourcePriority.fromValue(priority),
                            priority,
                            stack.size());

                    // 顯示該優先級的資源（從頂部開始）
                    for (int i = stack.size() - 1; i >= 0; i--) {
                        ResourceWrapper wrapper = stack.get(i);
                        logger.atInfo().log("    [{}] {}", (i + 1), wrapper.description);
                    }
                }
            }
            logger.atInfo().log("================================");
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 計算所有優先級組中的資源總數
     * <p>
     * 內部方法，不加鎖，調用方需要確保線程安全
     *
     * @return 資源總數
     */
    private int getTotalResourceCount() {
        return priorityGroups.values().stream().mapToInt(Stack::size).sum();
    }

    /**
     * 為資源生成預設描述
     * <p>
     * 格式：類名@hashCode（16進制）
     *
     * @param resource 資源對象
     * @return 生成的描述字符串
     */
    private String generateDescription(AutoCloseable resource) {
        return resource.getClass().getSimpleName() + "@" + Integer.toHexString(resource.hashCode());
    }

    /**
     * 獲取當前管理的資源總數
     * <p>
     * 線程安全的公開方法
     *
     * @return 資源總數
     */
    public int getResourceCount() {
        readLock.lock();
        try {
            return getTotalResourceCount();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 檢查管理器是否為空（無資源）
     *
     * @return true 如果沒有任何資源被管理
     */
    public boolean isEmpty() {
        readLock.lock();
        try {
            return priorityGroups.isEmpty() || getTotalResourceCount() == 0;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 檢查清理是否正在進行中
     *
     * @return true 如果清理正在進行，此時不能添加新資源
     */
    public boolean isShutdownInProgress() {
        return shutdownInProgress;
    }

    /**
     * 獲取當前所有優先級的集合
     * <p>
     * 返回當前管理器中有資源的所有優先級值，按從小到大排序
     *
     * @return 優先級集合的副本
     */
    public Set<Integer> getAllPriorities() {
        readLock.lock();
        try {
            return new TreeSet<>(priorityGroups.keySet());
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 預定義的資源清理優先級
     * <p>
     * 定義了系統中常見資源類型的建議清理優先級。 數字越大，優先級越高，越早被清理。
     * <p>
     * 建議的使用場景：
     * <ul>
     *   <li><strong>CRITICAL</strong>: 關鍵系統資源，如安全相關的連接</li>
     *   <li><strong>HIGH</strong>: 重要業務資源</li>
     *   <li><strong>NORMAL</strong>: 一般資源（預設優先級）</li>
     *   <li><strong>LOW</strong>: 低優先級資源</li>
     *
     *   <li><strong>APPLICATION_SERVICE</strong>: 應用服務層資源</li>
     *   <li><strong>NETWORK_CONNECTION</strong>: 網路連接資源</li>
     *   <li><strong>FILE_HANDLE</strong>: 檔案處置資源</li>
     *   <li><strong>DATABASE_CONNECTION</strong>: 資料庫連接</li>
     *   <li><strong>THREAD_POOL</strong>: 執行續池資源</li>
     *   <li><strong>INFRASTRUCTURE</strong>: 基礎設施資源，最後清理</li>
     * </ul>
     */
    public enum ResourcePriority {
        CRITICAL(1000, "CRITICAL"),
        HIGH(800, "HIGH"),
        NORMAL(500, "NORMAL"),
        LOW(200, "LOW"),

        APPLICATION_SERVICE(700, "APPLICATION_SERVICE"),
        NETWORK_CONNECTION(600, "NETWORK_CONNECTION"),
        FILE_HANDLE(400, "FILE_HANDLE"),
        DATABASE_CONNECTION(300, "DATABASE_CONNECTION"),
        THREAD_POOL(150, "THREAD_POOL"),
        INFRASTRUCTURE(100, "INFRASTRUCTURE");

        public final int value;
        public final String name;

        ResourcePriority(int value, String name) {
            this.value = value;
            this.name = name;
        }

        public static ResourcePriority fromValue(int value) {
            for (ResourcePriority priority : values()) {
                if (priority.value == value) {
                    return priority;
                }
            }
            throw new IllegalArgumentException("Unknown priority value: " + value);
        }

        public static ResourcePriority fromName(String name) {
            for (ResourcePriority priority : values()) {
                if (priority.name.equalsIgnoreCase(name)) {
                    return priority;
                }
            }
            throw new IllegalArgumentException("Unknown priority name: " + name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 資源包裝器內部類
     * <p>
     * 封裝了資源對象、描述信息和優先級，用於內部管理。 實現了 {@link AutoCloseable} 介面，方便統一處理。
     */
    private static class ResourceWrapper implements AutoCloseable {

        final AutoCloseable resource;
        final String description;
        final ResourcePriority priority;
        final LocalDateTime createdAt;
        final String createdBy;

        ResourceWrapper(AutoCloseable resource, String description, ResourcePriority priority) {
            this.resource = resource;
            this.description = description != null ? description : "Unknown Resource";
            this.priority = priority;

            this.createdAt = LocalDateTime.now();

            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            // 自動跳過 ResourceCleanupManager 內部方法，找到真實呼叫者
            String callerInfo = "Unknown";
            for (int i = 2; i < stack.length; i++) {
                if (!stack[i].getClassName().equals(ResourceCleanupManager.class.getName())) {
                    callerInfo = String.format(
                            "%s.%s:%d",
                            stack[i].getClassName()
                                    .substring(stack[i].getClassName().lastIndexOf('.') + 1),
                            stack[i].getMethodName(),
                            stack[i].getLineNumber());
                    break;
                }
            }
            this.createdBy = callerInfo;
        }

        @Override
        public void close() throws Exception {
            resource.close();
        }

        @Override
        public String toString() {
            return String.format(
                    "%s[description=%s, priority=%s, createdAt=%s, createdBy=%s]",
                    resource.getClass().getName(), description, priority.name, createdAt, createdBy);
        }
    }
}
