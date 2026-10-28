package org.gslab.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONTokener;

public class JsonFileUtil {

    public static JSONObject ReadFile(File JSONFile) {
        try {
            FileInputStream FileIn = new FileInputStream(JSONFile);
            JSONTokener tokener = new JSONTokener(FileIn);
            JSONObject JSONData = new JSONObject(tokener);
            FileIn.close();
            return JSONData;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static File WriteFile(JSONObject InputJSON, File OutputJSON) throws IOException {
        if (!OutputJSON.exists()) {
            OutputJSON.createNewFile();
        }

        FileOutputStream FileOut = new FileOutputStream(OutputJSON);
        byte[] Data = InputJSON.toString().getBytes();
        FileOut.write(Data);
        FileOut.flush();
        FileOut.close();
        return OutputJSON;
    }

    public static synchronized File AppendDataToFile(JSONObject InputJSON, File OutputJSON) throws IOException {

        if (!OutputJSON.exists()) {
            createJsonFile(OutputJSON);
        } else {
            JSONObject jsonObject = ReadFile(OutputJSON);
            if (!jsonObject.isEmpty()) {
                jsonObject.keySet().forEach(key -> InputJSON.put(key, jsonObject.get(key)));
            }
        }

        WriteFile(InputJSON, OutputJSON);
        return OutputJSON;
    }

    private static void createJsonFile(File OutputJSON) throws IOException {

        if (!OutputJSON.exists()) {
            OutputJSON.createNewFile();
        }

        FileOutputStream FileOut = new FileOutputStream(OutputJSON);
        byte[] Data = "{}".getBytes();
        FileOut.write(Data);
        FileOut.flush();
        FileOut.close();
    }

    public static boolean createJson(Map<String, Object> input, String output) {
        JSONObject json = new JSONObject();
        FileWriter content;
        try {
            for (String context : input.keySet()) {
                json.put(context, input.get(context));
            }
            content = new FileWriter(output);
            BufferedWriter bw = new BufferedWriter(content);
            bw.write(json.toString());
            bw.flush();
            bw.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
