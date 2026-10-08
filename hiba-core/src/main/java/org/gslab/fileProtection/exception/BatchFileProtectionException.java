package org.gslab.fileProtection.exception;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Exception thrown when batch file protection operation encounters invalid files
 * <p>
 * Provides clear information about which files failed validation
 */
public class BatchFileProtectionException extends FileProtectionException {

    private final List<File> invalidFiles;

    public BatchFileProtectionException(String operation, String component, String message, List<File> invalidFiles) {
        super(operation, component, message);
        this.invalidFiles =
                invalidFiles == null ? List.of() : new ArrayList<>(invalidFiles); // Mutable copy that allows nulls
    }

    public BatchFileProtectionException(
            String operation, String component, String message, List<File> invalidFiles, Throwable cause) {
        super(operation, component, message, cause);
        this.invalidFiles =
                invalidFiles == null ? List.of() : new ArrayList<>(invalidFiles); // Mutable copy that allows nulls
    }

    /**
     * Get list of invalid files that caused the batch operation to fail
     *
     * @return immutable list of invalid files
     */
    public List<File> getInvalidFiles() {
        return invalidFiles;
    }

    /**
     * Get list of invalid file names for convenience
     *
     * @return list of invalid file names (null files show as "null")
     */
    public List<String> getInvalidFileNames() {
        return invalidFiles.stream()
                .map(file -> file == null ? "null" : file.getName())
                .toList();
    }

    /**
     * Get list of invalid file paths for convenience
     *
     * @return list of invalid file paths (null files show as "null")
     */
    public List<String> getInvalidFilePaths() {
        return invalidFiles.stream()
                .map(file -> file == null ? "null" : file.getPath())
                .toList();
    }

    /**
     * Get count of invalid files
     *
     * @return number of invalid files
     */
    public int getInvalidFileCount() {
        return invalidFiles.size();
    }

    @Override
    public String getMessage() {
        return super.getMessage() + " Invalid files: " + getInvalidFileNames();
    }
}
