package org.gslab.fileProtection.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Hash Utility Class Provides file hash calculation functionality
 */
public class HashUtil {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 8192;

    private HashUtil() {}

    /**
     * Calculate SHA-256 hash value of file
     *
     * @param file           File to calculate hash for
     * @param additionalData additional data to include in hash calculation
     * @return Hash value in hexadecimal format, or empty string if file is null or doesn't exist
     * @throws IOException              File reading error
     * @throws NoSuchAlgorithmException Algorithm not supported
     */
    public static String calculateFileHash(File file, String... additionalData)
            throws IOException, NoSuchAlgorithmException {
        if (file == null || !file.exists() || !file.isFile()) {
            return "";
        }

        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        if (additionalData != null) {
            for (String data : additionalData) {
                digest.update(data.getBytes());
            }
        }

        byte[] hashBytes = digest.digest();
        return bytesToHex(hashBytes);
    }

    /**
     * Calculate SHA-256 hash value of string
     *
     * @param data Strings to calculate hash for
     * @return Hash value in hexadecimal format, or empty string if input is null or empty
     * @throws NoSuchAlgorithmException Algorithm not supported
     */
    public static String calculateStringHash(String... data) throws NoSuchAlgorithmException {
        if (data == null || data.length == 0) {
            return "";
        }

        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);

        for (String str : data) {
            digest.update(str.getBytes());
        }

        byte[] hashBytes = digest.digest();
        return bytesToHex(hashBytes);
    }

    /**
     * Convert byte array to hexadecimal string
     *
     * @param bytes Byte array
     * @return Hexadecimal string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * Generate unique ID
     *
     * @return UUID string
     */
    public static String generateUniqueId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Verify file integrity
     *
     * @param file         File to verify
     * @param expectedHash Expected hash value
     * @return Whether file is intact
     */
    public static boolean verifyFileIntegrity(File file, String expectedHash) {
        try {
            String actualHash = calculateFileHash(file);
            return expectedHash.equals(actualHash);
        } catch (Exception e) {
            return false;
        }
    }

    public static String getHashAlgorithm() {
        return HASH_ALGORITHM;
    }

    public static int getBufferSize() {
        return BUFFER_SIZE;
    }
}
