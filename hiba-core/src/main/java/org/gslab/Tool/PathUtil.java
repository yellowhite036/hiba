package org.gslab.Tool;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for path operations using modern Java NIO Path API.
 * Provides consistent path normalization and construction throughout the application.
 */
public class PathUtil {

    /**
     * Normalize a path string to remove redundant elements like "." and "..".
     * Also converts relative paths to a cleaner form.
     *
     * @param path The path string to normalize
     * @return Normalized Path object
     */
    public static Path normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return Paths.get("");
        }
        return Paths.get(path).normalize();
    }

    /**
     * Build a path by joining base path with one or more path components.
     * Automatically normalizes the result to remove redundant separators and "." elements.
     *
     * @param base The base path
     * @param parts Additional path components to append
     * @return Normalized Path object
     */
    public static Path buildPath(String base, String... parts) {
        if (base == null) {
            base = "";
        }

        Path result = Paths.get(base);
        if (parts != null) {
            for (String part : parts) {
                if (part != null && !part.isEmpty()) {
                    result = result.resolve(part);
                }
            }
        }
        return result.normalize();
    }

    /**
     * Build a path from Path base and String components.
     *
     * @param base The base Path
     * @param parts Additional path components to append
     * @return Normalized Path object
     */
    public static Path buildPath(Path base, String... parts) {
        if (base == null) {
            base = Paths.get("");
        }

        Path result = base;
        if (parts != null) {
            for (String part : parts) {
                if (part != null && !part.isEmpty()) {
                    result = result.resolve(part);
                }
            }
        }
        return result.normalize();
    }

    /**
     * Convert a Path to normalized String representation.
     *
     * @param path The Path to convert
     * @return Normalized path string
     */
    public static String toNormalizedString(Path path) {
        if (path == null) {
            return "";
        }
        return path.normalize().toString();
    }

    /**
     * Build and return normalized string path directly.
     *
     * @param base The base path
     * @param parts Additional path components
     * @return Normalized path string
     */
    public static String buildPathString(String base, String... parts) {
        return buildPath(base, parts).toString();
    }
}
