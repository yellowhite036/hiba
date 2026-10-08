package org.gslab;

import java.io.File;
import java.util.Map;
import org.gslab.Tool.JsonFileTool;
import org.gslab.errorHandling.FileCreateException;
import org.slf4j.Logger;

public class Utils {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(Utils.class);

    /**
     * Checks if the directory exists, and if not, creates it.
     *
     * @param path the path of the directory to check/create
     * @throws FileCreateException if the directory cannot be created
     */
    public static void fileExists(String path) {

        try {
            File dir = new File(path);
            if (!dir.exists()) {
                logger.atInfo().addKeyValue("path", path).log("Directory is not exist, create it: {}", path);
                boolean created = dir.mkdirs();
                if (!created) {
                    throw new FileCreateException("Failed to create directory: " + path);
                }
            }

        } catch (NullPointerException e) {
            throw new FileCreateException("Path cannot be null", e);
        } catch (SecurityException e) {
            throw new FileCreateException("Permission denied to create directory: " + path, e);
        }
    }

    public static float getCPUScore() {
        float score;
        File scoreFile = new File("./device/score.json");
        Map<String, Object> map = JsonFileTool.ReadFile(scoreFile).toMap();
        logger.atInfo().addKeyValue("cpuScore", map.get("score")).log("CPU score: {}", map.get("score"));
        score = Float.parseFloat((String) map.get("score"));
        return score;
    }
}
