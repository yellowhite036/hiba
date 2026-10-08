package org.gslab.service;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.gslab.Tool.JsonFileTool;
import org.gslab.Tool.PathUtil;
import org.gslab.sdns.HiBAInitial;
import org.slf4j.Logger;

public class RequestListener extends Thread {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RequestListener.class);

    @SuppressWarnings("unchecked")
    public static Map<String, Object> start(Map<String, Object> existingServiceList, String listenTarget) {

        Map<String, Object> request = new HashMap<>();
        String requestReceiveDirectory = HiBAInitial.fileTransferDirectory;
        final Path path = Paths.get(listenTarget);
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
            // while (true) {
            final WatchKey key = watchService.take();
            for (WatchEvent<?> watchEvent : key.pollEvents()) {
                final WatchEvent.Kind<?> kind = watchEvent.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) { // 建立事件
                    LOGGER.atInfo().log("[CREATE_REQUEST]: {}", watchEvent.context());

                    File requestFile = new File(listenTarget + "/" + watchEvent.context());
                    try {
                        Map<String, Object> requestTree =
                                JsonFileTool.ReadFile(requestFile).toMap();
                        String serviceName = (String) requestTree.get("serviceName");
                        /*
                         * Put basic additional request message.
                         * Start BIO Socket server.
                         */
                        long timeStamp = System.currentTimeMillis();
                        SimpleDateFormat dataFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_S");
                        String date = dataFormat.format(new Date(Long.parseLong(String.valueOf(timeStamp))));
                        Path targetPath = PathUtil.buildPath(requestReceiveDirectory, serviceName + "_" + date);
                        String target = targetPath.toString();
                        if (requestTree.containsKey("transferFile")) {
                            request.put("transferFile", requestTree.get("transferFile"));
                        }
                        request.put("nodeName", HiBAInitial.nodeName);
                        request.put("serviceName", serviceName);
                        request.put("requestName", serviceName);
                        request.put("requestTimeStamp", timeStamp);
                        request.put("requestDate", date);
                        request.put("receiveTargetPath", target);
                        request.put("tier", 0);
                        File requestDir = targetPath.toFile();
                        requestDir.mkdirs();
                        /*
                         * Defined and undefined service.
                         * If request is defined service, system will follow configuration file(env.json) to configure.
                         */
                        if (existingServiceList.containsKey(serviceName)) { // defined
                            Map<String, Object> inputRules = (Map<String, Object>) existingServiceList.get(serviceName);
                            for (String input : inputRules.keySet()) {
                                // 保護 receiveTargetPath 不被覆蓋，系統自動產生的目錄優先
                                if (!"receiveTargetPath".equals(input)) {
                                    request.put(input, requestTree.get(input));
                                }
                            }
                        } else {
                            for (String requestKey : requestTree.keySet()) { // undefined
                                // 保護 receiveTargetPath 不被覆蓋，系統自動產生的目錄優先
                                if (!"receiveTargetPath".equals(requestKey)) {
                                    request.put(requestKey, requestTree.get(requestKey));
                                }
                            }
                        }
                        LOGGER.atInfo().log("send request: {}", request);
                    } catch (Exception e) {
                        LOGGER.atError().log("can't handle this request, format errors: {}", e.getMessage());
                        return null;
                    }
                }
            }
            return request;
        } catch (Exception e) {
            LOGGER.atError().log("Error in RequestListener: ", e);
            return null;
        }
    }
}
