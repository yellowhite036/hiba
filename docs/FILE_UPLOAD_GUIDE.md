# HTTP File Upload Feature Guide

## Overview

The HTTP request handler now supports file uploads with built-in memory management to prevent heap exhaustion.

## Memory Management Strategy

### How It Works

1. **Small files (< 1MB)**: Kept in memory temporarily, then written to disk
2. **Large files (> 1MB)**: Automatically streamed to disk by Netty
3. **Cleanup**: Automatic cleanup via `decoder.destroy()` + scheduled backup task

### Memory Protection

- **Per-file limit**: 100MB (`FileUploadConfig.MAX_FILE_SIZE`)
- **Per-request limit**: 500MB (`FileUploadConfig.MAX_REQUEST_SIZE`)
- **Memory threshold**: 1MB (`FileUploadConfig.MEMORY_THRESHOLD`)

## API Usage

### JSON API (Existing - Unchanged)

```bash
curl -X POST http://localhost:8080/request \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "MyService", "param1": "value1"}'
```

### File Upload API (New)

```bash
curl -X POST http://localhost:8080/request \
  -H "Content-Type: multipart/form-data" \
  -F "serviceName=BlockchainFileProtect" \
  -F "param1=value1" \
  -F "file1=@/path/to/document.pdf" \
  -F "file2=@/path/to/image.jpg"
```

## Service Implementation

### Receiving File Uploads

Services receive uploaded files as `FileUploadInfo` objects in the request map:

```java
@SuppressWarnings("unchecked")
List<FileUploadInfo> uploadedFiles =
    (List<FileUploadInfo>) request.get("uploadedFiles");

if (uploadedFiles != null && !uploadedFiles.isEmpty()) {
    for (FileUploadInfo fileInfo : uploadedFiles) {
        String filename = fileInfo.getOriginalFilename();
        String path = fileInfo.getStoredPath();
        long size = fileInfo.getFileSize();
        String contentType = fileInfo.getContentType();

        // Process the file at the given path
        processFile(path);
    }
}
```

### File Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│ Upload Phase                                                 │
├─────────────────────────────────────────────────────────────┤
│ 1. Client sends multipart/form-data                         │
│ 2. Netty receives chunks:                                   │
│    - Small files (<1MB) → memory buffer                     │
│    - Large files (>1MB) → /tmp/hiba-upload-*.tmp           │
│ 3. HttpRequestHandler moves files to receiveTargetPath:     │
│    /fileTransfer/<serviceName>_<timestamp>/<filename>       │
└─────────────────────────────────────────────────────────────┘
           ↓
┌─────────────────────────────────────────────────────────────┐
│ Service Phase                                                │
├─────────────────────────────────────────────────────────────┤
│ 4. Service receives FileUploadInfo with file path           │
│ 5. Service processes file (read, parse, transform, etc.)    │
│ 6. Service decides when to delete (immediately or later)    │
└─────────────────────────────────────────────────────────────┘
           ↓
┌─────────────────────────────────────────────────────────────┐
│ Cleanup Phase                                                │
├─────────────────────────────────────────────────────────────┤
│ 7. Immediate: decoder.destroy() deletes /tmp files          │
│ 8. Service: Deletes processed files when done               │
│ 9. Safety net: TempFileCleanupTask removes orphaned files   │
│    (runs hourly, removes files >24h old)                    │
└─────────────────────────────────────────────────────────────┘
```

### File Cleanup Responsibilities

**Services MUST handle file cleanup:**

```java
private void processUploadedFiles(List<FileUploadInfo> uploadedFiles) {
    for (FileUploadInfo fileInfo : uploadedFiles) {
        Path filePath = Paths.get(fileInfo.getStoredPath());

        try {
            // Process the file
            processFile(filePath);

        } finally {
            // IMPORTANT: Delete file after processing
            try {
                Files.deleteIfExists(filePath);
                logger.info("Deleted processed file: {}", filePath);
            } catch (IOException e) {
                logger.error("Failed to delete file: {}", filePath, e);
            }
        }
    }
}
```

### When to Keep Files

If your service needs to keep files for later processing:

1. **Document the retention policy**
2. **Implement your own cleanup logic**
3. **Consider disk space limits**

Example:

```java
// Keep files for 7 days
private static final long RETENTION_DAYS = 7;

private void scheduleFileCleanup(Path filePath) {
    scheduler.schedule(() -> {
        try {
            Files.deleteIfExists(filePath);
            logger.info("Deleted retained file after {} days: {}",
                       RETENTION_DAYS, filePath);
        } catch (IOException e) {
            logger.error("Failed to delete retained file: {}", filePath, e);
        }
    }, RETENTION_DAYS, TimeUnit.DAYS);
}
```

## Configuration

### Adjusting Limits

Edit `FileUploadConfig.java`:

```java
// Memory threshold (files larger than this go to disk)
public static final long MEMORY_THRESHOLD = 1_048_576; // 1MB

// Max single file size
public static final long MAX_FILE_SIZE = 104_857_600; // 100MB

// Max total request size
public static final long MAX_REQUEST_SIZE = 524_288_000; // 500MB

// Temp file cleanup age
public static final long TEMP_FILE_MAX_AGE_MS = 24 * 60 * 60 * 1000; // 24h
```

### Monitoring

Watch for these log messages:

**Success:**
```
INFO  HttpRequestHandler - File uploaded: FileUploadInfo{originalFilename='doc.pdf', ...}
INFO  HttpRequestHandler - HTTP request processed successfully with result: MyService
```

**Errors:**
```
ERROR HttpRequestHandler - File too large: 150000000 bytes (max: 104857600)
ERROR HttpRequestHandler - Total request size too large: 600000000 bytes (max: 524288000)
```

**Cleanup:**
```
INFO  TempFileCleanupTask - Deleted orphaned temp file: hiba-upload-123.tmp (age: 90000000ms)
INFO  TempFileCleanupTask - Temp file cleanup completed: deleted=3, errors=0
```

## Testing

### Test Small File (Memory Path)

```bash
# Create 500KB test file
dd if=/dev/urandom of=/tmp/small.bin bs=1024 count=500

# Upload
curl -X POST http://localhost:8080/request \
  -F "serviceName=TestService" \
  -F "file=@/tmp/small.bin"
```

### Test Large File (Disk Path)

```bash
# Create 10MB test file
dd if=/dev/urandom of=/tmp/large.bin bs=1048576 count=10

# Upload
curl -X POST http://localhost:8080/request \
  -F "serviceName=TestService" \
  -F "file=@/tmp/large.bin"
```

### Test Multiple Files

```bash
curl -X POST http://localhost:8080/request \
  -F "serviceName=TestService" \
  -F "document=@/tmp/doc.pdf" \
  -F "image=@/tmp/image.jpg" \
  -F "data=@/tmp/data.csv"
```

### Verify Memory Usage

```bash
# Before upload
jcmd <pid> VM.native_memory summary

# During large upload (should stay low)
jcmd <pid> VM.native_memory summary

# Compare heap usage
```

## Troubleshooting

### Issue: Out of Memory during uploads

**Cause**: HttpObjectAggregator buffer too small, causing fallback to memory
**Solution**: Verify pipeline configuration in `HiBAHttpRequestServer.java`:

```java
new HttpObjectAggregator((int) FileUploadConfig.MAX_REQUEST_SIZE)
```

### Issue: Temp files not deleted

**Check:**
1. Is `decoder.destroy()` being called? (Check finally block)
2. Is cleanup task running? (Check logs for "Starting temp file cleanup task")
3. File permissions correct? (Temp dir must be writable)

### Issue: Files missing after upload

**Cause**: Service might be looking in wrong directory
**Solution**: Use `receiveTargetPath` from request map:

```java
String targetDir = (String) request.get("receiveTargetPath");
// Files are in this directory
```

## Best Practices

1. **Always cleanup files** - Don't rely on safety net alone
2. **Handle large files** - Test with files near the limit
3. **Monitor disk space** - File uploads consume disk, not just heap
4. **Validate file types** - Check `contentType` if security matters
5. **Log file operations** - Essential for debugging upload issues

## Migration from Old Code

No changes needed! Existing JSON API works unchanged. Only add file upload support when needed.
