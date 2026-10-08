package org.gslab.Tool;

public class StringTool {

    /**
     * 移除字串中的所有換行符號。
     *
     * @param str 要處理的字串
     * @return 移除換行符號後的字串，如果輸入為 null 則返回 null，如果輸入為空字串則返回空字串
     */
    public static String removeNewline(String str) {
        if (str == null) {
            return null;
        }

        if (str.isEmpty()) {
            return "";
        }

        return str.replaceAll("\\R", ""); // 移除換行
    }

    /**
     * 移除字串中的所有換行符號和空格。
     *
     * @param str 要處理的字串
     * @return 移除換行符號後的字串，如果輸入為 null 則返回 null，如果輸入為空字串則返回空字串
     */
    public static String removeNewlineAndSpace(String str) {
        if (str == null) {
            return null;
        }

        if (str.isEmpty()) {
            return "";
        }

        // 一次性移除所有空白字符（包括換行、空格、Tab等）
        return str.replaceAll("\\s+", "");
    }
}
