# RequestId Migration TODO

**Status:** Core Complete - 62% (21/34 files)
**Type:** Type Safety Refactoring (String → RequestId value object)
**Breaking Change:** Yes (serialization incompatible)
**Strategy:** Use `RequestId.UNKNOWN` for null cases

**✅ Phases 1-2 COMPLETE** | **🔶 Phase 3 MOSTLY DONE** | **⬜ Phases 4-6 SCRIPTED**

---

## Quick Start

To complete the remaining migration:

```bash
# Option 1: Automated (Recommended)
cd /home/user/Desktop/hiba
./fix-requestid-migration.sh

# Option 2: Manual
# See: docs/RequestId_Migration_Fix_Guide.md
```

---

---

## Overview

Complete migration of all String-based `requestId` to the type-safe `RequestId` value object across 34 files.

**Critical Rules:**
- ✅ Use `RequestId.UNKNOWN` instead of null
- ✅ Call `.value()` ONLY at serialization boundaries (JSON, toMap())
- ✅ Type-only changes - no additional refactoring
- ✅ Breaking change accepted - no backward compatibility

---

## Phase 1: Foundation - Message & Model Classes

### Message Classes (6 files)

- [ ] **RegisteMessage.java** (`hiba-core/src/main/java/org/gslab/message_hiba/`)
  - Line 8: `String requestId` → `RequestId requestId`
  - Lines 15, 38-40: Update constructor/getter

- [ ] **RegisteResponse.java** (`hiba-core/src/main/java/org/gslab/message_hiba/`)
  - Line 6: `String requestId` → `RequestId requestId`
  - Lines 10, 21-23: Update constructor/getter

- [ ] **WaitingRequestMessage.java** (`hiba-core/src/main/java/org/gslab/message_hiba/`)
  - Line 6: `String requestId` → `RequestId requestId`
  - Lines 8, 17-19: Update constructor/getter

- [ ] **TaskStartMessage.java** (`hiba-core/src/main/java/org/gslab/message_hiba/`)
  - Line 8: `String requestId` → `RequestId requestId`
  - Lines 12, 22-24: Update constructor/getter

- [ ] **FileTransferFormat.java** (`hiba-core/src/main/java/org/gslab/message_hiba/`)
  - Line 10: `String requestId` → `RequestId requestId` (initialize to UNKNOWN)
  - Lines 23-29: Update getter/setter

- [ ] **FileTransferClient.java** (`hiba-core/src/main/java/org/gslab/sdns/`)
  - Lines 35, 58: Method parameters `String requestId` → `RequestId requestId`
  - Lines 86-89: Call `.value()` when setting in transfer format

### Model Classes (4 files)

- [ ] **TaskRequest.java** (`hiba-core/src/main/java/org/gslab/model/`)
  - Line 13: `String requestId` → `RequestId requestId`
  - Lines 62-68: Update getter/setter
  - Line 253: `toMap()` - use `requestId.value()`
  - Line 254: `fromMap()` - use `RequestId.of((String) map.get("requestId"))`

- [ ] **TaskResult.java** (`hiba-core/src/main/java/org/gslab/model/`)
  - Line 16: `String requestId` → `RequestId requestId`
  - Lines 63, 80-86: Update constructor/getter/setter
  - Lines 280, 343: Update toMap/fromMap
  - Line 396: toString() automatically works

- [ ] **TaskAssignment.java** (`hiba-core/src/main/java/org/gslab/model/`)
  - Line 12: `String requestId` → `RequestId requestId`
  - Lines 57-63: Update getter/setter
  - Lines 233, 295, 338: Update toMap/fromMap/toString

- [ ] **UnifiedResponse.java** (`hiba-core/src/main/java/org/gslab/model/response/`)
  - Line 27: `String requestId` → `RequestId requestId`
  - Lines 50, 84, 113, 124, 139, 152: Update builder/construction
  - Use `.value()` when building JSON response

---

## Phase 2: Core Infrastructure (CRITICAL PATH)

### RequestContext

- [ ] **RequestContext.java** (`hiba-core/src/main/java/org/gslab/context/`)
  - Line 28: `public final String requestId` → `public final RequestId requestId`
  - Line 181: Builder field `String requestId` → `RequestId requestId`
  - Line 192: `public Builder requestId(RequestId requestId)` (change parameter)
  - Line 244: Update validation in build() - check for null only
  - Line 268: toString() - no change needed

### RequestManager

- [ ] **RequestManager.java** (`hiba-core/src/main/java/org/gslab/context/`)
  - All Map declarations: `Map<String, X>` → `Map<RequestId, X>`
    - Line 28: activeRequests
    - Line 74: registerRequest()
    - Line 90: unregisterRequest()
    - Line 104: getContext()
    - Line 125: getAllActiveRequests()
    - Line 143: registerWaiter()
    - Line 150: getWaiterChannel()
    - Line 176: unregisterWaiter()
    - Line 190: getAllWaiters()
    - Line 200: registerResultWaiter()
    - Line 236: unregisterResultWaiter()
    - Line 245: getResultWaiter()
  - All method signatures: `String requestId` → `RequestId requestId`

---

## Phase 3: Request Processing Chain

### AsyncRequestForwarder

- [ ] **AsyncRequestForwarder.java** (`hiba-core/src/main/java/org/gslab/context/`)
  - Lines 126, 129: RegistrationHandler constructor `String requestId` → `RequestId requestId`
  - Lines 152, 155: WaitingHandler constructor
  - Line 43: RegisteMessage creation - pass RequestId
  - Line 70: WaitingRequestMessage creation - pass RequestId

### HTTP Handler

- [ ] **HttpRequestHandler.java** (`hiba-core/src/main/java/org/gslab/sdns/`)
  - Line 98: ✅ Already generates `RequestId requestId = RequestId.generate()`
  - Fix all inconsistent String usage throughout:
    - Lines 104, 111, 119, 129, 141, 181, 210, 247: Ensure using RequestId type
    - Lines 535, 546, 598, 639, 656, 669, 735, 756: Update parameter types
  - Use `.value()` ONLY when putting into JSON response maps

### Server Handlers

- [ ] **ServerChannelHandlers.java** (`hiba-core/src/main/java/org/gslab/handlers/`)
  - Lines 237, 327, 378, 425, 457: Method parameters `String requestId` → `RequestId requestId`
  - Lines 587, 611, 637, 660, 718: Message handling
  - Lines 773, 775, 778, 784, 791: Context access
  - Line 1009: Logging

- [ ] **ClientChannelHandlers.java** (`hiba-core/src/main/java/org/gslab/handlers/`)
  - Line 191: `String requestId = msg.getRequestId()` → `RequestId requestId`

### Broker Handlers

- [ ] **BrokerRegistrationHandlers.java** (`hiba-core/src/main/java/org/gslab/handlers/`)
  - Lines 26, 29: RegistrationHandler constructor field
  - Lines 60, 65: CompletionHandler constructor field

- [ ] **BrokerCompletionHandlers.java** (`hiba-core/src/main/java/org/gslab/handlers/`)
  - Lines 29, 32: Handler constructor field
  - Lines 121, 127: Usage

---

## Phase 4: Task Distribution

### Distribution Executor

- [ ] **DistributionThreadExecutor.java** (`hiba-core/src/main/java/org/gslab/distribution/`)
  - Lines 72, 135, 160, 181, 209, 230: Method parameters
  - Lines 418, 454, 478, 513: Task assignment/result handling

### Task Handlers

- [ ] **TaskForwardingHandler.java** (`hiba-core/src/main/java/org/gslab/task/`)
  - Lines 33, 72, 80, 89, 127, 134: All `String requestId` → `RequestId requestId`

- [ ] **TaskResultHandler.java** (`hiba-core/src/main/java/org/gslab/task/`)
  - Lines 34, 95, 196: Method parameters
  - Line 184: Logging - no change needed (toString() handles it)

### Main Application

- [ ] **App.java** (`hiba-core/src/main/java/org/gslab/`)
  - Lines 255, 582, 792, 930, 938, 954: Request tracking

### File Transfer Server

- [ ] **HiBAFileTransferServer.java** (`hiba-core/src/main/java/org/gslab/sdns/`)
  - Line 65: `String requestId = transferFile.getRequestId()` → `RequestId requestId`

---

## Phase 5: Service Layer

### Service Distributors

- [ ] **getFileDistributor.java** (`hiba-core/src/main/java/org/gslab/service/mService/distributor/`)
  - Lines 252, 259, 603, 634, 688: All String requestId → RequestId requestId

- [ ] **getModelDistributor.java** (`hiba-core/src/main/java/org/gslab/service/mService/distributor/`)
  - Line 117: String requestId → RequestId requestId

- [ ] **trainDeepModelDistributor.java** (`hiba-core/src/main/java/org/gslab/service/mService/distributor/`)
  - Lines 105, 216: String requestId → RequestId requestId

### Service Executors

- [ ] **getModelExecutor.java** (`hiba-core/src/main/java/org/gslab/service/mService/executor/`)
  - Line 39: Extract from Map - use `RequestId.of((String) information.get("requestId"))`

- [ ] **trainDeepModelExecutor.java** (`hiba-core/src/main/java/org/gslab/service/mService/executor/`)
  - Line 22: Extract from Map

- [ ] **BlockchainFileProtectExecutor.java** (`hiba-core/src/main/java/org/gslab/service/mService/executor/`)
  - Line 68: Extract from Map

### Abstractions

- [ ] **Distributor.java** (hiba-core) (`hiba-core/src/main/java/org/gslab/`)
  - Line 20: `setRequestID(String requestId)` → `setRequestID(RequestId requestId)`

- [ ] **Distributor.java** (hiba-common) (`hiba-common/src/com/socomni/abstraction/`)
  - Line 7: Interface method `void setRequestID(String requestId)` → `void setRequestID(RequestId requestId)`

---

## Phase 6: Testing & Validation

- [ ] **Unit Tests**
  - Verify RequestId serialization/deserialization
  - Test RequestId.UNKNOWN usage in error paths

- [ ] **Integration Tests**
  - HTTP request → RequestContext → Task execution → Response flow
  - Broker chain forwarding with RequestId propagation
  - Error scenarios with RequestId.UNKNOWN
  - Map key operations (RequestManager)

- [ ] **Manual Testing**
  - getFile service end-to-end
  - Task distribution with broker chain
  - Error handling paths
  - File transfer protocol

---

## Migration Checklist

### Before Starting
- [ ] Ensure all existing tests pass
- [ ] Create feature branch: `feature/requestid-type-migration`
- [ ] Backup current working state

### During Migration
- [ ] Follow phase order (bottom-up dependency chain)
- [ ] Compile after each phase to catch errors early
- [ ] Use `.value()` only at serialization boundaries
- [ ] Use `RequestId.UNKNOWN` for null/error cases
- [ ] Never use `.toString()` explicitly (implicit conversion works)

### After Completion
- [ ] All compilation errors resolved
- [ ] All tests pass (unit + integration)
- [ ] Manual smoke testing complete
- [ ] Code review completed
- [ ] Documentation updated

---

## Common Patterns

### Pattern 1: Field Declaration
```java
// Before
private String requestId;

// After
private RequestId requestId;
```

### Pattern 2: Constructor/Method Parameter
```java
// Before
public void handleRequest(String requestId) { ... }

// After
public void handleRequest(RequestId requestId) { ... }
```

### Pattern 3: Map Operations
```java
// Before
Map<String, RequestContext> map;
map.put(requestId, context);

// After
Map<RequestId, RequestContext> map;
map.put(requestId, context);  // No .value() needed!
```

### Pattern 4: Serialization (toMap)
```java
// Before
map.put("requestId", requestId);

// After
map.put("requestId", requestId.value());  // Extract String
```

### Pattern 5: Deserialization (fromMap)
```java
// Before
String requestId = (String) map.get("requestId");
request.setRequestId(requestId);

// After
String idStr = (String) map.get("requestId");
request.setRequestId(RequestId.of(idStr));
```

### Pattern 6: Null Handling
```java
// Before
if (requestId == null || requestId.isEmpty()) {
    requestId = UUID.randomUUID().toString();
}

// After
if (requestId == null) {
    requestId = RequestId.UNKNOWN;  // or RequestId.generate()
}
```

### Pattern 7: Logging
```java
// Before
logger.atInfo().addKeyValue("requestId", requestId).log("Processing request");

// After
logger.atInfo().addKeyValue("requestId", requestId).log("Processing request");
// No change needed - toString() is automatic!
```

---

## Notes

- **Total files:** 34
- **Estimated effort:** 10-12 developer days
- **Risk level:** MEDIUM-HIGH (core infrastructure)
- **Breaking change:** Old serialized messages won't deserialize
- **Deployment:** Requires coordinated rollout (stop all nodes, deploy, restart)

---

**Last Updated:** 2025-11-10
**Created by:** Claude Code (Uncle Bob mode)
