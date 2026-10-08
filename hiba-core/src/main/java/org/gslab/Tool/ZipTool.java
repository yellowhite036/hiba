package org.gslab.Tool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipTool {

    public ZipTool() {}

    public static File zipStart(File srcFile, File destZipFile) {
        try (FileOutputStream fileWriter = new FileOutputStream(destZipFile);
                ZipOutputStream zip = new ZipOutputStream(fileWriter)) {
            if (srcFile.isDirectory()) {
                addFolderToZip(srcFile, srcFile, zip);
            }

            if (srcFile.isFile()) {
                String Parent = srcFile.getParent() == null ? "" : srcFile.getParent();
                addFileToZip(new File(Parent), srcFile, zip);
            }
            return destZipFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static File zipStart(File[] FileList, File destZipFile) {
        try (FileOutputStream fileWriter = new FileOutputStream(destZipFile);
                ZipOutputStream zip = new ZipOutputStream(fileWriter)) {
            for (File fileName : FileList) {
                String Parent = fileName.getParent() == null ? "" : fileName.getParent();
                addFileToZip(new File(Parent), fileName, zip);
            }
            return destZipFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static File zipStart(Iterable<File> FileList, File destZipFile) {
        try (FileOutputStream fileWriter = new FileOutputStream(destZipFile);
                ZipOutputStream zip = new ZipOutputStream(fileWriter)) {
            for (File fileName : FileList) {
                String Parent = fileName.getParent() == null ? "" : fileName.getParent();
                addFileToZip(new File(Parent), fileName, zip);
            }
            return destZipFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static ArrayList<File> unzipStart(File srcZipFile, File destDir) {
        ArrayList<File> fileinzip = new ArrayList<>();

        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(srcZipFile))) {
            ZipEntry entry = null;
            while ((entry = zip.getNextEntry()) != null) {
                File file = extractFile(entry, destDir, zip);
                if (file != null) {
                    fileinzip.add(file);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return fileinzip;
    }

    private static File extractFile(ZipEntry entry, File destDir, ZipInputStream zipIn) throws Exception {
        if (entry.isDirectory()) {
            new File(destDir.getPath() + "/" + entry.getName()).mkdirs();
            return null;
        }

        String ParentPath = new File(entry.getName()).getParent();
        if (ParentPath != null) {
            File Parent = new File(destDir.getPath() + "/" + ParentPath);
            if (!Parent.exists()) {
                Parent.mkdirs();
            }
        }

        try (FileOutputStream FileOut = new FileOutputStream(destDir.getPath() + "/" + entry.getName())) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = zipIn.read(buf)) > 0) {
                FileOut.write(buf, 0, len);
            }
        }
        return new File(destDir.getPath() + "/" + entry.getName());
    }

    private static void addFileToZip(File rootPath, File srcFile, ZipOutputStream zip) throws Exception {

        if (srcFile.isDirectory()) {
            addFolderToZip(rootPath, srcFile, zip);
        } else {
            byte[] buf = new byte[1024];
            int len;
            try (FileInputStream in = new FileInputStream(srcFile)) {
                String name = srcFile.getPath();
                name = name.replace(rootPath.getPath() + "\\", "");
                name = name.replace(rootPath.getPath() + "/", "");
                zip.putNextEntry(new ZipEntry(name));
                while ((len = in.read(buf)) > 0) {
                    zip.write(buf, 0, len);
                }
            }
        }
    }

    private static void addFolderToZip(File rootPath, File srcFolder, ZipOutputStream zip) throws Exception {
        for (File fileName : srcFolder.listFiles()) {
            addFileToZip(rootPath, fileName, zip);
        }
    }
}
