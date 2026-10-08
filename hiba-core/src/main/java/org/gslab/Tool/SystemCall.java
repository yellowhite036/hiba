package org.gslab.Tool;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class SystemCall {

    public static ArrayList<String> terminal(String cmd, boolean showInfo) {
        ArrayList<String> contentList = new ArrayList<>();

        try {
            Process child;
            child = Runtime.getRuntime().exec(cmd);
            BufferedReader Info = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String content;
            while ((content = Info.readLine()) != null) {
                contentList.add(content);
                if (showInfo) {
                    System.out.println(content);
                }
            }
            return contentList;
        } catch (IOException e) {
            e.printStackTrace();
            return contentList;
        }
    }

    public static void main(String[] args) {
        // ArrayList<String> contentList = terminal("ping");
        // for (String text : contentList) {
        //     System.out.println(text);
        // }
        // 獲取tree.json路徑
        File taskFileDir = new File(
                "D:/00-NKFUST/01-GSLab/HierarchicalBrokeringArchitecture/G01-SourceCode/hiba/t437k901/training/testTask/1/");
        String treeName = "";
        for (File file : taskFileDir.listFiles()) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                treeName = file.getName().replace(".json", "");
                break;
            }
        }
        System.out.println(treeName);
    }
}
