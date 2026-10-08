package org.gslab.sdns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.gslab.App;
import org.gslab.Tool.PathUtil;
import org.gslab.Tool.StringTool;
import org.gslab.context.RequestContext;
import org.gslab.model.RequestId;
import org.gslab.model.response.FileOperationResult;
import org.gslab.model.response.ResponseAdapter;
import org.gslab.model.response.UnifiedError;
import org.gslab.model.response.UnifiedResponse;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP request handler supporting both JSON and multipart file uploads.
 * <p>
 * Memory Management: - Files > 1MB automatically spill to disk (not heap) - Disk temp files cleaned up after processing
 * - Services receive file paths, not file contents
 * <p>
 * Backward Compatibility: - Existing JSON API unchanged (Content-Type: application/json) - New multipart support
 * (Content-Type: multipart/form-data)
 */
public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestHandler.class);
    private final App app;

    // Configure disk-backed file upload to prevent memory exhaustion
    private static final HttpDataFactory factory = new DefaultHttpDataFactory(FileUploadConfig.MEMORY_THRESHOLD);

    private final Path requestReceiveDirectory;
    private final Path fileUploadDirectory;

    static {
        // Set temp directory for file uploads
        DiskFileUpload.deleteOnExitTemporaryFile = true;
        DiskFileUpload.baseDirectory = System.getProperty("java.io.tmpdir");
        DiskAttribute.deleteOnExitTemporaryFile = true;
        DiskAttribute.baseDirectory = System.getProperty("java.io.tmpdir");
    }

    public HttpRequestHandler(App app) {
        this.app = app;

        requestReceiveDirectory = Path.of(HiBAInitial.fileTransferDirectory);
        fileUploadDirectory = Path.of(HiBAInitial.fileUploadDirectory);
    }

    /**
     * Create a virtual channel for HTTP requests.
     * EmbeddedChannel is a dummy Netty channel that doesn't perform actual I/O.
     * This allows HTTP requests to integrate with RequestManager which expects channels.
     */
    private Channel createVirtualChannelForHttp() {
        return new EmbeddedChannel();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Generate requestId first - never null from this point
        RequestId requestId = RequestId.generate();

        try {
            // Only handle POST requests
            if (!request.method().equals(HttpMethod.POST)) {
                sendErrorResponse(
                        ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported", requestId);
                return;
            }

            // Check Content-Type
            String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
            if (contentType == null) {
                sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Content-Type header is required", requestId);
                return;
            }

            // Route based on Content-Type
            if (contentType.contains("multipart/form-data")) {
                handleMultipartRequest(ctx, request, requestId);
            } else if (contentType.contains("application/json")) {
                handleJsonRequest(ctx, request, requestId);
            } else {
                sendErrorResponse(
                        ctx,
                        HttpResponseStatus.BAD_REQUEST,
                        "Content-Type must be application/json or multipart/form-data",
                        requestId);
            }

        } catch (Exception e) {
            logger.atError().addKeyValue("requestId", requestId).setCause(e).log("Error handling HTTP request");
            sendErrorResponse(
                    ctx,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal server error: " + e.getMessage(),
                    requestId);
        }
    }

    /**
     * Handle multipart file upload requests. Files are streamed to disk when > 1MB threshold.
     */
    private void handleMultipartRequest(ChannelHandlerContext ctx, FullHttpRequest request, RequestId requestId) {
        logger.atDebug().addKeyValue("requestId", requestId).log("handleMultipartRequest called");
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("uri", request.uri())
                .log("Request URI");
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("method", request.method().toString())
                .log("Request Method");
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("contentType", request.headers().get(HttpHeaderNames.CONTENT_TYPE))
                .log("Content-Type header");
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("contentLength", request.headers().get(HttpHeaderNames.CONTENT_LENGTH))
                .log("Content-Length header");

        HttpPostRequestDecoder decoder = null;
        try {
            // Create decoder with disk-backed storage
            decoder = new HttpPostRequestDecoder(factory, request);

            // Read request body content
            decoder.offer(request);
            decoder.offer(LastHttpContent.EMPTY_LAST_CONTENT);

            // Process the multipart data
            Map<String, Object> requestMap = processMultipartRequest(decoder, requestId);
            if (requestMap == null) {
                sendErrorResponse(
                        ctx,
                        HttpResponseStatus.BAD_REQUEST,
                        "Missing required fields: serviceName is mandatory",
                        requestId);
                return;
            }

            // Ensure requestId is in the map
            requestMap.put("requestId", requestId);

            // Process request asynchronously using RequestContext
            HttpPostRequestDecoder finalDecoder = decoder;
            processRequestAsync(ctx, requestMap, finalDecoder, requestId);

        } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
            logger.atError().addKeyValue("requestId", requestId).setCause(e).log("Failed to decode multipart request");
            sendErrorResponse(
                    ctx, HttpResponseStatus.BAD_REQUEST, "Invalid multipart format: " + e.getMessage(), requestId);
            if (decoder != null) {
                decoder.destroy();
            }
        } catch (Exception e) {
            logger.atError().addKeyValue("requestId", requestId).setCause(e).log("Error handling multipart request");
            sendErrorResponse(
                    ctx,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Failed to process multipart request: " + e.getMessage(),
                    requestId);
            if (decoder != null) {
                decoder.destroy();
            }
        }
    }

    /**
     * Handle JSON requests (existing API - unchanged).
     */
    private void handleJsonRequest(ChannelHandlerContext ctx, FullHttpRequest request, RequestId requestId) {
        try {
            // Read request body
            ByteBuf content = request.content();
            if (content.readableBytes() == 0) {
                sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Empty request body", requestId);
                return;
            }

            String jsonString = content.toString(CharsetUtil.UTF_8);
            logger.atInfo()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("request", StringTool.removeNewlineAndSpace(jsonString))
                    .log("Received HTTP request");

            // Parse JSON
            JSONObject jsonRequest;
            try {
                jsonRequest = new JSONObject(jsonString);
            } catch (Exception e) {
                sendErrorResponse(
                        ctx, HttpResponseStatus.BAD_REQUEST, "Invalid JSON format: " + e.getMessage(), requestId);
                return;
            }

            // Convert to existing request format
            Map<String, Object> requestMap = processHttpRequest(jsonRequest, requestId);
            if (requestMap == null) {
                sendErrorResponse(
                        ctx,
                        HttpResponseStatus.BAD_REQUEST,
                        "Missing required fields: serviceName is mandatory",
                        requestId);
                return;
            }

            // Ensure requestId is in the map
            requestMap.put("requestId", requestId);

            // Process request asynchronously using RequestContext
            processRequestAsync(ctx, requestMap, null, requestId);

        } catch (Exception e) {
            logger.atError().addKeyValue("requestId", requestId).setCause(e).log("Error handling JSON request");
            sendErrorResponse(
                    ctx,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal server error: " + e.getMessage(),
                    requestId);
        }
    }

    /**
     * Process multipart request and extract files.
     */
    private Map<String, Object> processMultipartRequest(HttpPostRequestDecoder decoder, RequestId requestId) {
        logger.atDebug().addKeyValue("requestId", requestId).log("processMultipartRequest started");

        try {
            // Log all body data parts for debugging
            List<InterfaceHttpData> httpBodyData = decoder.getBodyHttpDatas();
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("bodyDataParts", httpBodyData.size())
                    .log("Total body data parts size: {}", httpBodyData.size());

            for (InterfaceHttpData d : httpBodyData) {
                logger.atDebug()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("type", d.getHttpDataType())
                        .addKeyValue("name", d.getName())
                        .log("Body data part info: type={}, name={}", d.getHttpDataType(), d.getName());
            }

            Map<String, Object> request = new HashMap<>();
            request.put("requestId", requestId);

            List<FileUploadInfo> uploadedFiles = new ArrayList<>();
            String serviceName = null;
            long totalSize = 0;

            // Prepare base directory structure
            long timeStamp = System.currentTimeMillis();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_S");
            String date = dateFormat.format(new Date(timeStamp));

            // First pass: calculate total size for disk space check
            long estimatedTotalSize = 0;
            for (InterfaceHttpData data : httpBodyData) {
                if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                    FileUpload fileUpload = (FileUpload) data;
                    estimatedTotalSize += fileUpload.length();
                    logger.atDebug()
                            .addKeyValue("name", fileUpload.getName())
                            .addKeyValue("filename", fileUpload.getFilename())
                            .addKeyValue("size", fileUpload.length())
                            .log(
                                    "Found FileUpload File name {} with size {}",
                                    fileUpload.getFilename(),
                                    fileUpload.length());
                }
            }

            long estimatedTotalSizeMB = estimatedTotalSize / (1024 * 1024);
            logger.atDebug()
                    .addKeyValue("estimatedTotalSizeMB", estimatedTotalSizeMB)
                    .log("Estimated total upload size: {} bytes ({} MB)", estimatedTotalSize, estimatedTotalSizeMB);

            // Check disk space before processing files
            if (estimatedTotalSize > 0 && !checkDiskSpace(estimatedTotalSize)) {
                logger.atError()
                        .addKeyValue("estimatedTotalSizeMB", estimatedTotalSizeMB)
                        .log("Upload rejected: insufficient disk space");
                return null;
            }

            // Iterate through all form data
            for (InterfaceHttpData data : httpBodyData) {
                try {
                    if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                        // Regular form field
                        io.netty.handler.codec.http.multipart.Attribute attribute =
                                (io.netty.handler.codec.http.multipart.Attribute) data;
                        String name = attribute.getName();
                        String value = attribute.getValue();

                        if ("serviceName".equals(name)) {
                            serviceName = value;
                        }
                        request.put(name, value);

                    } else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                        // File upload
                        FileUpload fileUpload = (FileUpload) data;
                        logger.atDebug()
                                .addKeyValue("name", fileUpload.getName())
                                .addKeyValue("filename", fileUpload.getFilename())
                                .addKeyValue("contentType", fileUpload.getContentType())
                                .log(
                                        "Processing FileUpload: name={}, filename={}, contentType={}",
                                        fileUpload.getName(),
                                        fileUpload.getFilename(),
                                        fileUpload.getContentType());

                        // Validate file size
                        long fileSize = fileUpload.length();
                        long fileSizeMB = fileSize / (1024 * 1024);
                        logger.atDebug()
                                .addKeyValue("fileSize", fileSize)
                                .addKeyValue("fileSizeMB", fileSizeMB)
                                .log("File size: {} bytes ({} MB)", fileSize, fileSizeMB);

                        if (fileSize > FileUploadConfig.MAX_FILE_SIZE) {
                            logger.atError()
                                    .addKeyValue("fileSize", fileSize)
                                    .addKeyValue("maxFileSize", FileUploadConfig.MAX_FILE_SIZE)
                                    .log(
                                            "File too large to upload, the limit is {} bytes",
                                            FileUploadConfig.MAX_FILE_SIZE);
                            return null;
                        }

                        totalSize += fileSize;
                        long totalSizeMB = totalSize / (1024 * 1024);
                        logger.atDebug()
                                .addKeyValue("totalSize", totalSize)
                                .addKeyValue("totalSizeMB", totalSizeMB)
                                .log("Total request size so far: {} bytes ({} MB)", totalSize, totalSizeMB);

                        if (totalSize > FileUploadConfig.MAX_REQUEST_SIZE) {
                            logger.atError()
                                    .addKeyValue("totalSize", totalSize)
                                    .addKeyValue("maxRequestSize", FileUploadConfig.MAX_REQUEST_SIZE)
                                    .log(
                                            "Total request size too large, the limit is {} bytes",
                                            FileUploadConfig.MAX_REQUEST_SIZE);
                            return null;
                        }

                        // serviceName must be set before processing files
                        if (serviceName == null) {
                            logger.atError().log("serviceName must be provided before file uploads");
                            return null;
                        }

                        Path fileUploadPath = PathUtil.buildPath(fileUploadDirectory, serviceName + "_" + date);
                        File fileUploadDir = fileUploadPath.toFile();

                        if (!fileUploadDir.exists()) {
                            logger.atDebug()
                                    .addKeyValue("fileUploadPath", fileUploadPath.toAbsolutePath())
                                    .log("File upload directory does not exist, creating");

                            if (!fileUploadDir.mkdirs()) {
                                logger.atError()
                                        .addKeyValue("fileUploadPath", fileUploadPath.toAbsolutePath())
                                        .log("Failed to create file upload directory");
                                return null;
                            }
                            logger.atInfo()
                                    .addKeyValue("fileUploadPath", fileUploadPath.toAbsolutePath())
                                    .log("Successfully created file upload directory");
                        } else {
                            logger.atDebug()
                                    .addKeyValue("fileUploadPath", fileUploadPath.toAbsolutePath())
                                    .log("File upload directory already exists");
                        }

                        // Move file from temp to target directory
                        String originalFilename = fileUpload.getFilename();
                        Path finalPath = PathUtil.buildPath(fileUploadPath, originalFilename);

                        logger.atDebug()
                                .addKeyValue("isInMemory", fileUpload.isInMemory())
                                .addKeyValue("finalPathRelative", finalPath)
                                .addKeyValue("finalPathAbsolute", finalPath.toAbsolutePath())
                                .log("File storage");

                        try {
                            if (fileUpload.isInMemory()) {
                                // Small file in memory - write to disk
                                Files.write(finalPath, fileUpload.get());
                                logger.atInfo()
                                        .addKeyValue("finalPath", finalPath.toAbsolutePath())
                                        .log("Successfully wrote file from memory to disk");

                                if (finalPath.toFile().exists()) {
                                    logger.atDebug()
                                            .addKeyValue("finalPath", finalPath.toAbsolutePath())
                                            .log("Verified: file exists");
                                } else {
                                    logger.atError()
                                            .addKeyValue("finalPath", finalPath.toAbsolutePath())
                                            .log("CRITICAL: Files.write() succeeded but file does not exist");
                                    return null;
                                }
                            } else {
                                // Large file already on disk - rename/move
                                if (!fileUpload.renameTo(finalPath.toFile())) {
                                    logger.atError()
                                            .addKeyValue("finalPath", finalPath.toAbsolutePath())
                                            .log("Failed to move file from temp to final path");
                                    return null;
                                }
                                logger.atInfo()
                                        .addKeyValue("finalPath", finalPath.toAbsolutePath())
                                        .log("Successfully moved file from temp to final path");

                                // Verify file was moved
                                if (finalPath.toFile().exists()) {
                                    logger.atDebug()
                                            .addKeyValue("finalPath", finalPath.toAbsolutePath())
                                            .log("Verified: file exists");
                                } else {
                                    logger.atError()
                                            .addKeyValue("finalPath", finalPath.toAbsolutePath())
                                            .log("CRITICAL: renameTo() succeeded but file does not exist");
                                    return null;
                                }
                            }
                        } catch (IOException e) {
                            logger.atError()
                                    .addKeyValue("finalPath", finalPath.toAbsolutePath())
                                    .setCause(e)
                                    .log("Failed to write file to disk");
                            return null;
                        }

                        FileUploadInfo fileInfo = new FileUploadInfo(
                                originalFilename,
                                finalPath.toAbsolutePath().toString(),
                                fileSize,
                                fileUpload.getContentType());
                        uploadedFiles.add(fileInfo);
                        logger.atInfo().addKeyValue("fileInfo", fileInfo).log("File uploaded");
                    }
                } catch (Exception e) {
                    logger.atError()
                            .addKeyValue("dataName", data.getName())
                            .setCause(e)
                            .log("Error processing multipart data part");
                    return null;
                } finally {
                    // Release resources for this data part
                    data.release();
                }
            }

            // Validate required fields
            if (serviceName == null) {
                logger.atError().log("serviceName is required but not provided");
                return null;
            }

            Path receiveTargetPath = PathUtil.buildPath(requestReceiveDirectory, serviceName + "_" + date);
            receiveTargetPath.toFile().mkdirs();
            logger.atDebug()
                    .addKeyValue("receiveTargetPath", receiveTargetPath)
                    .log("Created receive target directory");

            // Set basic request parameters
            request.put("nodeName", HiBAInitial.nodeName);
            request.put("serviceName", serviceName);
            request.put("requestName", serviceName);
            request.put("requestTimeStamp", timeStamp);
            request.put("requestDate", date);
            request.put("receiveTargetPath", receiveTargetPath.toString());
            request.put("tier", 0);
            request.put("uploadedFiles", uploadedFiles);

            logger.atDebug().log("=== processMultipartRequest completed ===");
            logger.atDebug()
                    .addKeyValue("service", serviceName)
                    .addKeyValue("files", uploadedFiles.size())
                    .addKeyValue("requestParams", request.keySet())
                    .log("processMultipartRequest summary");

            return request;

        } catch (Exception e) {
            logger.atError().setCause(e).log("Error processing multipart request");
            return null;
        }
    }

    /**
     * Process JSON request (existing logic - unchanged).
     */
    private Map<String, Object> processHttpRequest(JSONObject jsonRequest, RequestId requestId) {
        try {
            // Check required fields
            if (!jsonRequest.has("serviceName")) {
                return null;
            }

            String serviceName = jsonRequest.getString("serviceName");

            // Build basic request info - mimics RequestListener.start() logic
            Map<String, Object> request = new HashMap<>();
            request.put("requestId", requestId);

            String requestReceiveDirectory = HiBAInitial.fileTransferDirectory;

            // Basic info setup
            long timeStamp = System.currentTimeMillis();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_S");
            String date = dateFormat.format(new Date(timeStamp));
            Path targetPath = PathUtil.buildPath(requestReceiveDirectory, serviceName + "_" + date);
            String target = targetPath.toString();

            // Transfer file info
            if (jsonRequest.has("transferFile")) {
                request.put("transferFile", jsonRequest.get("transferFile"));
            }

            // Set basic request parameters
            request.put("nodeName", HiBAInitial.nodeName);
            request.put("serviceName", serviceName);
            request.put("requestName", serviceName);
            request.put("requestTimeStamp", timeStamp);
            request.put("requestDate", date);
            request.put("receiveTargetPath", target);
            request.put("tier", 0);

            // Create target directory
            File requestDir = targetPath.toFile();
            requestDir.mkdirs();

            // Copy all other JSON fields to request, protecting receiveTargetPath
            for (String key : jsonRequest.keySet()) {
                if (!"receiveTargetPath".equals(key)) {
                    request.put(key, jsonRequest.get(key));
                }
            }

            return request;

        } catch (Exception e) {
            logger.atError().setCause(e).log("Error processing HTTP request to internal format");
            return null;
        }
    }

    /**
     * Process request asynchronously and send response.
     * No MDC usage - requestId passed explicitly.
     */
    private void processRequestAsync(
            ChannelHandlerContext ctx,
            Map<String, Object> requestMap,
            HttpPostRequestDecoder decoder,
            RequestId requestId) {
        // Calculate dynamic timeout based on uploaded file sizes
        long timeoutSeconds = calculateUploadTimeout(requestMap);
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("timeoutSeconds", timeoutSeconds)
                .log("Request timeout set");

        CompletableFuture.supplyAsync(() -> {
                    try {
                        // Call existing request processing logic
                        Map<String, Object> result = sendRequestAndWaitForResult(requestMap, requestId);
                        return result;
                    } catch (Exception e) {
                        logger.atError()
                                .addKeyValue("requestId", requestId)
                                .setCause(e)
                                .log("Failed to process request");
                        Map<String, Object> errorResult = new HashMap<>();
                        errorResult.put("error", true);
                        errorResult.put("message", "Failed to process request: " + e.getMessage());
                        return errorResult;
                    }
                })
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((result, throwable) -> {
                    try {
                        sendUnifiedResponse(ctx, requestMap, result);
                    } finally {
                        // Cleanup decoder and temp files
                        if (decoder != null) {
                            decoder.destroy();
                        }
                    }
                });
    }

    /**
     * Send request and wait for result completion.
     * Creates a RequestContext with virtual channel to integrate with RequestManager.
     */
    private Map<String, Object> sendRequestAndWaitForResult(Map<String, Object> requestMap, RequestId requestId)
            throws Exception {
        String requestName = (String) requestMap.get("requestName");
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .log("sendRequestAndWaitForResult: Starting");

        // Create result wait mechanism
        CompletableFuture<Map<String, Object>> resultFuture = new CompletableFuture<>();

        // Create virtual channel for HTTP request (integrates with RequestManager)
        Channel virtualChannel = createVirtualChannelForHttp();

        // Create RequestContext to register with RequestManager
        // This allows HTTP requests to work with TaskForwardingHandler and getFileDistributor
        RequestContext requestContext = RequestContext.builder()
                .requestId(requestId)
                .requestName(requestName)
                .serviceName((String) requestMap.get("serviceName"))
                .originChannel(virtualChannel)
                .requestData(requestMap)
                .build();

        app.getRequestManager().registerRequest(requestContext);
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .log("Registered RequestContext with virtual channel for HTTP request");

        // Create temporary result listener (keep for backward compatibility)
        HttpRequestResultListener resultListener = new HttpRequestResultListener(requestId, resultFuture);

        // Add result listener to App's wait list (use requestId for proper notification matching)
        app.registerHttpRequestListener(requestId, resultListener);

        try {
            // Send request
            app.sendRequest(requestMap);

            // Wait for result, max 60 seconds
            Map<String, Object> result = resultFuture.get(60, TimeUnit.SECONDS);
            logger.atDebug()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .log("sendRequestAndWaitForResult: Received result");

            // Cleanup RequestContext
            app.getRequestManager().removeRequest(requestId);

            return result != null ? result : new HashMap<>();

        } catch (Exception e) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("requestName", requestName)
                    .setCause(e)
                    .log("sendRequestAndWaitForResult: Error");
            // Cleanup both listener and RequestContext
            app.getRequestManager().removeRequest(requestId);
            app.unregisterHttpRequestListener(requestId);
            throw e;
        }
    }

    private void sendSuccessResponseWithResult(
            ChannelHandlerContext ctx, Map<String, Object> requestMap, Map<String, Object> result) {
        JSONObject response = new JSONObject();
        response.put("status", "success");
        response.put("message", "Request processed successfully");
        response.put("requestName", requestMap.get("requestName"));
        response.put("requestId", requestMap.get("requestId"));
        response.put("serviceName", requestMap.get("serviceName"));
        response.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));

        // Add complete result data
        if (result != null && !result.isEmpty()) {
            response.put("result", new JSONObject(result));
        } else {
            response.put("result", new JSONObject());
        }

        sendJsonResponse(ctx, HttpResponseStatus.OK, response.toString());
    }

    private void sendSuccessResponse(ChannelHandlerContext ctx, Map<String, Object> requestMap) {
        JSONObject response = new JSONObject();
        response.put("status", "success");
        response.put("message", "Request processed successfully");
        response.put("requestId", requestMap.get("requestName"));
        response.put("serviceName", requestMap.get("serviceName"));
        response.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));

        sendJsonResponse(ctx, HttpResponseStatus.OK, response.toString());
    }

    private void sendErrorResponse(
            ChannelHandlerContext ctx, HttpResponseStatus status, String message, RequestId requestId) {
        JSONObject response = new JSONObject();
        response.put("requestId", requestId);
        response.put("status", "error");
        response.put("message", message);
        response.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));

        sendJsonResponse(ctx, status, response.toString());
    }

    /**
     * Send unified response format.
     * <p>
     * This is the new standardized response method that eliminates: - Multiple success indicators (status, success,
     * state) - Redundant metadata (requestId, serviceName, timestamp in both levels) - Inconsistent error reporting
     * <p>
     * Use this for all new code. Legacy sendSuccessResponseWithResult kept for compatibility.
     */
    private void sendUnifiedResponse(
            ChannelHandlerContext ctx, Map<String, Object> requestMap, Map<String, Object> result) {
        String requestName = (String) requestMap.get("requestName");
        RequestId requestId = (RequestId) requestMap.get("requestId");
        String serviceName = (String) requestMap.get("serviceName");

        // DIAGNOSTIC: Log entry point with channel state
        logger.atDebug()
                .addKeyValue("requestId", requestId)
                .addKeyValue("requestName", requestName)
                .addKeyValue("channelActive", ctx.channel().isActive())
                .addKeyValue("channelWritable", ctx.channel().isWritable())
                .addKeyValue("channelId", ctx.channel().id().asShortText())
                .log("sendUnifiedResponse: Entry - Channel state");

        try {
            JSONObject response;

            if (result != null && ResponseAdapter.isSuccess(result)) {
                logger.atDebug()
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("requestId", requestId)
                        .log("Request processed successfully");
                // Success case: transform result to unified format
                FileOperationResult fileResult = ResponseAdapter.transformToFileOperation(result);
                Long executionTime = ResponseAdapter.extractExecutionTime(result);

                UnifiedResponse<?> unifiedResponse = UnifiedResponse.success(requestName, requestId, serviceName)
                        .data(fileResult.toMap())
                        .executionTimeMs(executionTime)
                        .build();

                response = new JSONObject(unifiedResponse.toMap());
            } else {
                logger.atDebug()
                        .addKeyValue("requestName", requestName)
                        .addKeyValue("requestId", requestId)
                        .log("Request processed with error");
                // Error case: extract error AND data for consistent response format
                UnifiedError error = ResponseAdapter.extractError(result);

                if (error == null) {
                    error = new UnifiedError(UnifiedError.ErrorCode.INTERNAL_ERROR, "Unknown error occurred");
                }

                // Also transform result data to maintain consistent response structure
                FileOperationResult fileResult = ResponseAdapter.transformToFileOperation(result);
                Long executionTime = ResponseAdapter.extractExecutionTime(result);

                UnifiedResponse<?> unifiedResponse = UnifiedResponse.error(requestName, requestId, serviceName, error)
                        .data(fileResult.toMap())
                        .executionTimeMs(executionTime)
                        .build();

                response = new JSONObject(unifiedResponse.toMap());
            }

            sendJsonResponse(ctx, HttpResponseStatus.OK, response.toString());
        } catch (Exception e) {
            logger.atError()
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("error", e.getMessage())
                    .setCause(e)
                    .log("Failed to serialize unified response");

            // Fall back to simple error response
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("requestName", requestName);
            errorResponse.put("requestId", requestId);
            errorResponse.put("success", false);
            errorResponse.put("error", "Failed to serialize response: " + e.getMessage());
            errorResponse.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));

            sendJsonResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, errorResponse.toString());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.atError().setCause(cause).log("Exception in HTTP request handler");
        if (ctx.channel().isActive()) {
            sendErrorResponse(
                    ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error", RequestId.UNKNOWN);
        }
        ctx.close();
    }

    private void sendJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String jsonContent) {
        // Verify channel is active before attempting to write
        if (!ctx.channel().isActive()) {
            logger.atError()
                    .addKeyValue("channelId", ctx.channel().id().asShortText())
                    .log("Cannot send response: channel is not active (closed)");
            return;
        }

        if (!ctx.channel().isWritable()) {
            logger.atWarn()
                    .addKeyValue("channelId", ctx.channel().id().asShortText())
                    .log("Channel is not writable (backpressure), attempting write anyway");
        }

        ByteBuf responseContent = Unpooled.copiedBuffer(jsonContent, CharsetUtil.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, responseContent);

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseContent.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        logger.atDebug()
                .addKeyValue("channelId", ctx.channel().id().asShortText())
                .addKeyValue("contentLength", responseContent.readableBytes())
                .log("Attempting to write HTTP response");

        // Write response and add listener to track success/failure
        ctx.writeAndFlush(response).addListener(future -> {
            if (future.isSuccess()) {
                logger.atDebug()
                        .addKeyValue("channelId", ctx.channel().id().asShortText())
                        .log("HTTP response written successfully");
            } else {
                logger.atError()
                        .addKeyValue("channelId", ctx.channel().id().asShortText())
                        .setCause(future.cause())
                        .log("Failed to write HTTP response");
            }
        });
    }

    /**
     * Calculate appropriate timeout based on uploaded file sizes.
     * <p>
     * Formula: - Base timeout: 60 seconds (for JSON requests or processing time) - Per-MB timeout: 1 second per MB
     * (assumes ~1 MB/s minimum network speed) - Multiplier: 3x buffer for slow networks and processing time - Min: 120
     * seconds (2 minutes) - Max: 1800 seconds (30 minutes)
     * <p>
     * Examples: - 100MB file: 60 + 100 = 160s × 3 = 480s (8 minutes) - 1GB file: 60 + 1024 = 1084s × 3 = 3252s → capped
     * at 1800s (30 minutes)
     */
    private long calculateUploadTimeout(Map<String, Object> requestMap) {
        @SuppressWarnings("unchecked")
        List<FileUploadInfo> files = (List<FileUploadInfo>) requestMap.get("uploadedFiles");

        if (files == null || files.isEmpty()) {
            return 60; // JSON request: 60 seconds default
        }

        // Calculate total size in MB
        long totalSizeBytes =
                files.stream().mapToLong(FileUploadInfo::getFileSize).sum();
        long totalSizeMB = totalSizeBytes / (1024 * 1024);

        // Base timeout + file size based timeout
        long baseTimeout = 60; // seconds
        long estimatedSeconds = baseTimeout + totalSizeMB; // 1 second per MB

        // Apply safety multiplier for slow networks
        long timeoutSeconds = estimatedSeconds * 3;

        // Clamp to reasonable range
        long minTimeout = 120; // 2 minutes minimum
        long maxTimeout = 1800; // 30 minutes maximum

        long finalTimeout = Math.max(minTimeout, Math.min(timeoutSeconds, maxTimeout));

        logger.atInfo()
                .addKeyValue("totalSizeMB", totalSizeMB)
                .addKeyValue("finalTimeoutSeconds", finalTimeout)
                .log("Calculated timeout for upload");

        return finalTimeout;
    }

    /**
     * Check if sufficient disk space is available for upload.
     *
     * @param requiredBytes Number of bytes needed for upload
     * @return true if sufficient space available, false otherwise
     */
    private boolean checkDiskSpace(long requiredBytes) {
        try {
            File tmpDir = new File(System.getProperty("java.io.tmpdir"));
            long usableSpace = tmpDir.getUsableSpace();

            // Require 2x space as safety buffer:
            // - 1x for temp files during upload
            // - 1x for files moved to receiveTargetPath
            long requiredSpace = requiredBytes * 2;

            if (usableSpace < requiredSpace) {
                logger.atError()
                        .addKeyValue("availableMB", usableSpace / (1024 * 1024))
                        .addKeyValue("requiredMB", requiredSpace / (1024 * 1024))
                        .log("Insufficient disk space");
                return false;
            }

            logger.atDebug()
                    .addKeyValue("availableMB", usableSpace / (1024 * 1024))
                    .addKeyValue("requiredMB", requiredSpace / (1024 * 1024))
                    .log("Disk space check passed");
            return true;

        } catch (Exception e) {
            logger.atError().setCause(e).log("Failed to check disk space");
            // On error, allow upload to proceed (fail-open rather than fail-closed)
            return true;
        }
    }
}
