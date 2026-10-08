package org.gslab.Tool;

import java.sql.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

public class DateTool {

    public static String getNow() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        Date today = new Date(System.currentTimeMillis());
        return dateFormat.format(today);
    }
}
