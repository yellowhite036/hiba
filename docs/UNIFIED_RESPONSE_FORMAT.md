# Unified HTTP Response Format

## Overview

This document describes the unified HTTP response format implemented to eliminate inconsistencies in the HiBA distributed file system API responses.

## Problem Statement

Previously, the system had multiple inconsistent response formats:

### Old Format Issues

1. **Multiple success indicators**: Responses had `status`, `success`, `state` fields - checking success required examining 3+ fields
2. **Redundant metadata**: `requestId`, `serviceName`, `timestamp` duplicated at both top level and nested levels
3. **Inconsistent error reporting**: Some responses used `errorMessage`, some used `error`, some used `message`
4. **Service-specific chaos**: Each service used different field names:
   - getFile: `transfers`, `transferred`, `localFile`
   - BlockchainFileProtect: `processedFiles`, `protected`
   - No common contract between services

### Example Old Responses

**getFile (timeout):**
```json
{
  "requestName": "getFile",
  "result": {
    "requestName": "getFile",
    "requestId": "...",
    "success": false,
    "state": false,
    "errorMessage": "Timeout..."
  },
  "requestId": "...",
  "status": "success",
  "timestamp": "..."
}
```

**getFile (success):**
```json
{
  "requestName": "getFile",
  "result": {
    "transferred": true,
    "transfers": [...],
    "state": true,
    "primaryFileId": "...",
    "metadataAvailable": false
  },
  "status": "success",
  "timestamp": "..."
}
```

**BlockchainFileProtect:**
```json
{
  "result": {
    "success": true,
    "processedFiles": [...],
    "successCount": 2,
    "failureCount": 0
  },
  "status": "success"
}
```

## Unified Format

### Response Envelope

All HTTP responses now follow this structure:

```json
{
  "requestId": "uuid-string",
  "serviceName": "getFile|BlockchainFileProtect|...",
  "timestamp": "2025-10-22T16:03:17Z",
  "success": true,
  "executionTimeMs": 3317,
  "data": { /* service-specific payload */ },
  "error": null
}
```

### Success Response

```json
{
  "requestId": "8d226cb1-c99a-468b-9e9a-a2e669cab721",
  "serviceName": "getFile",
  "timestamp": "2025-10-22T16:03:17Z",
  "success": true,
  "executionTimeMs": 3317,
  "data": {
    "files": [
      {
        "fileId": "e497fd34-0b00-476d-9c5c-89976d0e7739",
        "fileName": "file_1_1.txt",
        "fileHash": "f9d0d08bc423272bb4ee33e9109d6e026a13681fd29a0ce7f731c0aee9d17567",
        "fileSize": 29,
        "sourceNode": "Node1",
        "localPath": "receiveFile/getFile_2025-10-22_16-03-13_779/Node1/file_1_1.txt",
        "success": true,
        "metadata": {
          "fileName": "file_1_1.txt",
          "fileSize": 29,
          "uploader": "HiBA",
          "uploadTime": "2025-09-26T07:25:58"
        }
      }
    ],
    "totalFiles": 1,
    "successCount": 1
  },
  "error": null
}
```

### Error Response

```json
{
  "requestId": "5796f85d-a1ee-4894-9ca5-a5104ec71218",
  "serviceName": "getFile",
  "timestamp": "2025-10-22T16:03:43Z",
  "success": false,
  "executionTimeMs": 30000,
  "data": null,
  "error": {
    "code": "TIMEOUT",
    "message": "Timeout waiting for RequestFinishMessage after 30 seconds"
  }
}
```

## Key Design Principles

### 1. One Success Field
- **OLD**: Check `status`, `success`, `state` - three different fields
- **NEW**: Check `success` boolean only

### 2. No Redundancy
- **OLD**: Metadata duplicated at multiple levels
- **NEW**: Metadata only at top level (requestId, serviceName, timestamp)

### 3. Clean Error Contract
- **OLD**: Inconsistent error fields
- **NEW**: `error: null` on success, `error: {code, message}` on failure

### 4. Service Data Isolated
- **OLD**: Service results mixed with metadata
- **NEW**: Service data in `data` field, standardized across services

## Standardized File Operations

All file-based services (getFile, BlockchainFileProtect, etc.) now use the same data structure:

```json
{
  "files": [
    {
      "fileId": "uuid",
      "fileName": "example.txt",
      "fileHash": "sha256...",
      "fileSize": 1234,
      "localPath": "/path/to/file",
      "success": true,
      "sourceNode": "Node1",
      "contentType": "text/plain",
      "protected": true,
      "metadata": { /* blockchain/other metadata */ }
    }
  ],
  "totalFiles": 1,
  "successCount": 1,
  "failureCount": 0
}
```

## Error Codes

Standardized error codes across all services:

### Request Errors (4xx equivalent)
- `INVALID_REQUEST`: Malformed request
- `MISSING_PARAMETER`: Required parameter missing
- `INVALID_PARAMETER`: Parameter value invalid
- `FILE_NOT_FOUND`: Requested file doesn't exist
- `RESOURCE_NOT_FOUND`: Requested resource not found
- `UNAUTHORIZED`: Authentication required
- `FORBIDDEN`: Access denied

### Execution Errors (5xx equivalent)
- `TIMEOUT`: Operation timed out
- `INTERNAL_ERROR`: Unexpected server error
- `SERVICE_UNAVAILABLE`: Service temporarily unavailable
- `BLOCKCHAIN_ERROR`: Blockchain operation failed
- `NETWORK_ERROR`: Network communication failed
- `STORAGE_ERROR`: Storage operation failed

### Transfer Errors
- `TRANSFER_FAILED`: File transfer failed
- `CHECKSUM_MISMATCH`: File hash verification failed
- `INCOMPLETE_TRANSFER`: Transfer interrupted

### Node Errors
- `NODE_UNREACHABLE`: Target node cannot be reached
- `NODE_BUSY`: Node is busy
- `CONSENSUS_FAILED`: Distributed consensus failed

## Implementation

### Core Classes

1. **`UnifiedResponse<T>`** (`org.gslab.model.response`)
   - Generic response envelope
   - Builder pattern for construction
   - Converts to Map for JSON serialization

2. **`UnifiedError`** (`org.gslab.model.response`)
   - Standardized error structure
   - ErrorCode enum for all error types
   - Optional details map for additional context

3. **`FileOperationResult`** (`org.gslab.model.response`)
   - Standardized file operation data structure
   - Used by all file-based services
   - Consistent field names across services

4. **`ResponseAdapter`** (`org.gslab.model.response`)
   - Converts legacy responses to unified format
   - Detects service type automatically
   - Handles backward compatibility

### HTTP Handler

`HttpRequestHandler.sendUnifiedResponse()` (line 628-678):

```java
private void sendUnifiedResponse(ChannelHandlerContext ctx, Map<String, Object> requestMap,
        Map<String, Object> result) {
    String requestId = (String) requestMap.get("requestId");
    String serviceName = (String) requestMap.get("serviceName");

    if (result != null && ResponseAdapter.isSuccess(result)) {
        // Success case: transform to unified format
        FileOperationResult fileResult = ResponseAdapter.transformToFileOperation(result);
        Long executionTime = ResponseAdapter.extractExecutionTime(result);

        UnifiedResponse<?> unifiedResponse =
            UnifiedResponse.success(requestId, serviceName)
                .data(fileResult.toMap())
                .executionTimeMs(executionTime)
                .build();

        response = new JSONObject(unifiedResponse.toMap());
    } else {
        // Error case: extract and format error
        UnifiedError error = ResponseAdapter.extractError(result);

        UnifiedResponse<?> unifiedResponse =
            UnifiedResponse.error(requestId, serviceName, error)
                .build();

        response = new JSONObject(unifiedResponse.toMap());
    }

    sendJsonResponse(ctx, HttpResponseStatus.OK, response.toString());
}
```

### Service Executors

Both `BlockchainFileProtectExecutor` and `getFileExecutor` now have `buildUnifiedResult()` methods that construct standardized responses:

```java
private Map<String, Object> buildUnifiedResult(...) {
    FileOperationResult.Builder resultBuilder = FileOperationResult.builder();

    FileInfo file = FileInfo.builder()
        .fileName(fileName)
        .fileHash(fileHash)
        .fileSize(fileSize)
        .localPath(localPath)
        .success(true)
        .build();

    resultBuilder.addFile(file);
    return resultBuilder.build().toMap();
}
```

## Migration Strategy

### Current Status

- ✅ Core unified response classes implemented
- ✅ ResponseAdapter for backward compatibility
- ✅ HttpRequestHandler.sendUnifiedResponse() added
- ✅ BlockchainFileProtectExecutor updated
- ✅ getFileExecutor updated
- ⏳ Legacy `sendSuccessResponseWithResult()` still in use

### Next Steps

1. **Test unified responses**: Verify all services return correct format
2. **Update clients**: Modify clients to use `success` field and new data structure
3. **Switch HTTP handler**: Replace `sendSuccessResponseWithResult()` calls with `sendUnifiedResponse()`
4. **Monitor**: Verify no regressions in production
5. **Remove legacy code**: Delete old response methods once migration complete

### Backward Compatibility

The `ResponseAdapter` class ensures legacy responses are automatically converted to the unified format. This allows gradual migration without breaking existing clients.

## Usage Examples

### Client-side (JavaScript)

```javascript
// OLD WAY (checking multiple fields)
if (response.status === 'success' &&
    response.result.success === true &&
    response.result.state === true) {
  // Process success...
}

// NEW WAY (one field)
if (response.success) {
  const files = response.data.files;
  // Process files...
} else {
  console.error(`Error ${response.error.code}: ${response.error.message}`);
}
```

### Client-side (Java)

```java
// OLD WAY
Map<String, Object> result = (Map) response.get("result");
Boolean success = (Boolean) result.get("success");
Boolean state = (Boolean) result.get("state");
if (success != null && success && state != null && state) {
    // Process...
}

// NEW WAY
Boolean success = (Boolean) response.get("success");
if (success) {
    Map<String, Object> data = (Map) response.get("data");
    List<Map> files = (List) data.get("files");
    // Process files...
} else {
    Map<String, Object> error = (Map) response.get("error");
    String errorCode = (String) error.get("code");
    String errorMessage = (String) error.get("message");
    // Handle error...
}
```

## Benefits

1. **Simplicity**: One boolean to check for success
2. **Consistency**: All services use same structure
3. **Type safety**: Clear field types and contracts
4. **Debuggability**: Standardized error codes and messages
5. **Maintainability**: Single source of truth for response format
6. **Performance**: No redundant data, efficient serialization

## References

- `UnifiedResponse.java`: `/hiba-core/src/main/java/org/gslab/model/response/UnifiedResponse.java`
- `UnifiedError.java`: `/hiba-core/src/main/java/org/gslab/model/response/UnifiedError.java`
- `FileOperationResult.java`: `/hiba-core/src/main/java/org/gslab/model/response/FileOperationResult.java`
- `ResponseAdapter.java`: `/hiba-core/src/main/java/org/gslab/model/response/ResponseAdapter.java`
- `HttpRequestHandler.java`: `/hiba-core/src/main/java/org/gslab/sdns/HttpRequestHandler.java:628-678`
