package org.gslab.filewatcher;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileWatchService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(FileWatchService.class);

    private WatchService Watcher;
    private ArrayList<WatchPathListener> WatchDirectoryList;
    private WatchFileHandler Handler;

    private FileWatchService() {}

    public static class builder {

        private final FileWatchService service = new FileWatchService();

        public builder() throws IOException {
            service.Watcher = FileSystems.getDefault().newWatchService();
        }

        public builder watchlist(Collection<WatchPathListener> WatchList) throws IllegalArgumentException {
            if (WatchList.isEmpty()) {
                throw new IllegalArgumentException("List can not be empty");
            }
            service.WatchDirectoryList = new ArrayList<>(WatchList);

            return this;
        }

        public builder handler(WatchFileHandler Handler) {
            service.Handler = Handler;
            return this;
        }

        public FileWatchService build() throws IOException {
            for (Field field : service.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    if (field.get(service) == null) {
                        throw new IllegalArgumentException(field.getName() + " can not be null");
                    }
                } catch (IllegalAccessException e) {
                    // shouldn't happen because I used setAccessible
                }
            }

            service.checkAndRegisteAllFiles();
            return service;
        }
    }

    private void checkAndRegisteAllFiles() throws IOException {
        for (WatchPathListener listener : WatchDirectoryList) {
            registe(getAbsolutePathByString(listener.getWatchPath()));
        }
    }

    private void registe(Path file) throws IOException {
        file.register(Watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    }

    private Path getAbsolutePathByString(String pathStr) {
        return Paths.get(pathStr).toAbsolutePath();
    }

    private WatchPathListener findListenerByPath(Path FilePath) {
        return WatchDirectoryList.stream()
                .filter(listener -> {
                    String findPath = FilePath.toString();
                    String matchPath =
                            getAbsolutePathByString(listener.getWatchPath()).toString();
                    return findPath.equals(matchPath);
                })
                .findFirst()
                .get();
    }

    @Override
    public void run() {
        WatchKey key;
        try {
            while ((key = Watcher.take()) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path eventPath = (Path) event.context();
                    WatchPathListener listener =
                            findListenerByPath(eventPath.toAbsolutePath().getParent());

                    if (event.kind() == ENTRY_CREATE) {
                        listener.createFileTrigger(eventPath.toFile().getPath());
                    } else if (event.kind() == ENTRY_MODIFY) {
                        listener.modifyFileTrigger(eventPath.toFile().getPath());
                    } else if (event.kind() == ENTRY_DELETE) {
                        listener.deleteFileTrigger(eventPath.toFile().getPath());
                    }
                }

                Handler.nofityAllChange();

                if (!key.reset()) {
                    break;
                }
            }
        } catch (InterruptedException e) {

        } finally {
            logger.atInfo().log("ResourceWatcher Stop");
        }
    }
}
