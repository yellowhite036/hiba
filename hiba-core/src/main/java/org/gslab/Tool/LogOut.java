package org.gslab.Tool;

import java.io.FileWriter;
import java.text.SimpleDateFormat;

public class LogOut {

    /*
     * 額外輸出Log檔用的Function
     * 可以直接呼叫, 系統會寫出log檔, 不需要設定log4j文件
     */
    public static void doLogOut(String data) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            String date = dateFormat.format(System.currentTimeMillis());
            String disconnectionLog = "./logs/Disconnection_" + date + ".log";
            FileWriter writerNetwork = new FileWriter(disconnectionLog, true);
            writerNetwork.write(data + "\n");
            writerNetwork.close();
        } catch (Exception e) {

        }
    }
}
