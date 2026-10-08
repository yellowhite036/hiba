package org.gslab.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public class FileCopy {

    public static void copyFile(String srcStr, String destStr) {
        Path src = Paths.get(srcStr);
        Path dest = Paths.get(destStr + "/" + src.getFileName());
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(source -> copy(source, dest.resolve(src.relativize(source))));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void copyDirector(String srcStr, String destStr) {
        Path src = Paths.get(srcStr);
        Path dest = Paths.get(destStr);
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(source -> copy(source, dest.resolve(src.relativize(source))));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void copy(Path source, Path dest) {
        try {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
