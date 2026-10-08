package org.gslab.filewatcher;

public interface WatchPathListener {

    String getWatchPath();

    default void modifyFileTrigger(String modifyFilePath) {}

    default void createFileTrigger(String createFilePath) {}

    default void deleteFileTrigger(String deleteFilePath) {}
}
