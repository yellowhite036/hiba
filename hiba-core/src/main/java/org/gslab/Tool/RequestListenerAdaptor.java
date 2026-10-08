package org.gslab.Tool;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * 目錄監控模組, 主程式為requestListener.java
 * 該程式碼只是測試用的
 */

public class RequestListenerAdaptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestListenerAdaptor.class);

    public static void main(String[] args) {
        // public void requestListener(String requestPath) {
        final Path path =
                Paths.get("D:/00-NKFUST/01-GSLab/HierarchicalBrokeringArchitecture/G01-SourceCode/hiba/response");
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            Date date = new Date();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_hh:mm:ss");
            path.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    // StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            while (true) {
                final WatchKey key = watchService.take();
                for (WatchEvent<?> watchEvent : key.pollEvents()) {
                    final WatchEvent.Kind<?> kind = watchEvent.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    // 創建事件
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        System.out.println("[創建]" + dateFormat.format(date) + ": " + watchEvent.context());
                    }
                    // 刪除事件
                    if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        System.out.println("[刪除]" + dateFormat.format(date) + ": " + watchEvent.context());
                    }
                    /*
                    // 修改事件
                    if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        System.out.println("[修改]" + watchEvent.context());
                    }
                    */
                }
                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.atError().log("Error in RequestListenerAdaptor: {}", e.getMessage(), e);
        }
    }
}
