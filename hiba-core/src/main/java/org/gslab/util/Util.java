package org.gslab.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Util {

    /** 預設 Hash 演算法 */
    private static final String DEFAULT_HASH_ALGORITHM = "SHA-256";

    /**
     * 工具類別不允許被實例化
     */
    private Util() {}

    public static String byteArrayToHexString(final byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static byte[] hexStringToByteArray(final String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * 使用預設演算法 (SHA-256) 計算 Hash
     *
     * @param data 要計算 Hash 的資料
     * @return Hash 值
     */
    public static byte[] calculateHash(final byte[] data) throws NoSuchAlgorithmException {
        return calculateHash(data, DEFAULT_HASH_ALGORITHM);
    }

    /**
     * 使用指定演算法計算 Hash
     *
     * @param data      要計算 Hash 的資料
     * @param algorithm Hash 演算法
     * @return Hash 值
     * @throws NoSuchAlgorithmException 如果指定的演算法不存在
     */
    public static byte[] calculateHash(final byte[] data, final String algorithm) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        return digest.digest(data);
    }

    /**
     * Display first N bytes of byte array as hex string
     *
     * @param bytes  the byte array
     * @param length number of bytes to display from the beginning
     * @return hex string representation
     */
    public static String bytesToHexFirst(final byte[] bytes, final int length) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int end = Math.min(length, bytes.length);
        byte[] subset = new byte[end];
        System.arraycopy(bytes, 0, subset, 0, end);
        return byteArrayToHexString(subset);
    }

    /**
     * Display last N bytes of byte array as hex string
     *
     * @param bytes  the byte array
     * @param length number of bytes to display from the end
     * @return hex string representation
     */
    public static String bytesToHexLast(final byte[] bytes, final int length) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int start = Math.max(0, bytes.length - length);
        int actualLength = bytes.length - start;
        byte[] subset = new byte[actualLength];
        System.arraycopy(bytes, start, subset, 0, actualLength);
        return byteArrayToHexString(subset);
    }

    /**
     * Display first and last N bytes of byte array as hex string
     *
     * @param bytes  the byte array
     * @param length number of bytes to display from each end
     * @return hex string representation like "AABB...CCDD"
     */
    public static String bytesToHexFirstAndLast(final byte[] bytes, final int length) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (bytes.length <= length * 2) {
            return byteArrayToHexString(bytes);
        }
        return bytesToHexFirst(bytes, length) + "..." + bytesToHexLast(bytes, length);
    }

    /**
     * Truncate string to show only the first N characters
     *
     * @param str    the string to truncate
     * @param length number of characters to keep from the beginning
     * @return truncated string with "..." suffix if truncated
     */
    public static String truncateFirst(final String str, final int length) {
        if (str == null) {
            return "null";
        }
        if (str.length() <= length) {
            return str;
        }
        return str.substring(0, length) + "...";
    }

    /**
     * Truncate string to show only the last N characters
     *
     * @param str    the string to truncate
     * @param length number of characters to keep from the end
     * @return truncated string with "..." prefix if truncated
     */
    public static String truncateLast(final String str, final int length) {
        if (str == null) {
            return "null";
        }
        if (str.length() <= length) {
            return str;
        }
        return "..." + str.substring(str.length() - length);
    }

    /**
     * Truncate string to show first and last N characters
     *
     * @param str    the string to truncate
     * @param length number of characters to keep from each end
     * @return truncated string like "ABC...XYZ" if truncated
     */
    public static String truncateFirstAndLast(final String str, final int length) {
        if (str == null) {
            return "null";
        }
        if (str.length() <= length * 2) {
            return str;
        }
        return str.substring(0, length) + "..." + str.substring(str.length() - length);
    }
}
