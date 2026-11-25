package org.gslab.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 檔案傳輸的存檔位置限制：只能寫進允許的目錄（相對於 HiBA 工作目錄），
 * 檔名不能帶路徑，也不能經由符號連結跳出允許的目錄。
 * <p>
 * 原本由傳送端指定任意 targetPath（例如 ../hiba-core-0.0.1.jar 或 /home/abcd/.ssh），
 * 等於任何人都能覆寫節點上的程式與金鑰。
 * </p>
 */
public final class ReceivePathPolicy {

    public record Decision(Path dir, Path file, String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    private final Path workDir;
    private final List<Path> roots = new ArrayList<>();

    public ReceivePathPolicy(Path workDir, List<String> allowedDirectories) {
        this.workDir = workDir.toAbsolutePath().normalize();
        for (String d : allowedDirectories) {
            roots.add(this.workDir.resolve(d).normalize());
        }
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("no allowed receive directories");
        }
    }

    public List<Path> roots() {
        return List.copyOf(roots);
    }

    /** 只看路徑字串（不碰檔案系統）。targetPath 為空時用第一個允許目錄 */
    public Decision check(String targetPath, String fileName) {
        Path dir;
        try {
            dir = targetPath == null || targetPath.isBlank()
                    ? roots.get(0)
                    : workDir.resolve(targetPath).normalize();
        } catch (InvalidPathException e) {
            return new Decision(null, null, "invalid target path");
        }
        String nameProblem = checkName(fileName);
        if (nameProblem != null) {
            return new Decision(dir, null, nameProblem);
        }
        Path file = dir.resolve(fileName).normalize();
        if (!dir.equals(file.getParent())) {
            return new Decision(dir, null, "file name escapes the target directory");
        }
        if (rootOf(dir) == null) {
            return new Decision(dir, file, "target " + dir + " is outside the allowed directories " + roots);
        }
        return new Decision(dir, file, null);
    }

    /**
     * 建立目錄後，用實際路徑（解開符號連結）再檢查一次。
     *
     * @return 問題描述；沒問題時為 null
     */
    public String checkReal(Decision d) throws IOException {
        Path root = rootOf(d.dir());
        if (root == null) {
            return "target is outside the allowed directories";
        }
        Files.createDirectories(root);
        Files.createDirectories(d.dir());
        if (!d.dir().toRealPath().startsWith(root.toRealPath())) {
            return "target directory resolves outside " + root + " (symbolic link)";
        }
        if (Files.isSymbolicLink(d.file())) {
            return "target file is a symbolic link";
        }
        return null;
    }

    private Path rootOf(Path dir) {
        for (Path r : roots) {
            if (dir.startsWith(r)) {
                return r;
            }
        }
        return null;
    }

    static String checkName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "empty file name";
        }
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf('\0') >= 0) {
            return "file name contains a path separator";
        }
        if (fileName.equals(".") || fileName.equals("..")) {
            return "invalid file name";
        }
        return null;
    }
}
