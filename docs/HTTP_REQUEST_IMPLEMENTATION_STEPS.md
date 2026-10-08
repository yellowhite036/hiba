# HTTP Request Listening 功能實現步驟

## 概述
將現有的資料夾監聽機制替換為HTTP請求接收機制，使用Netty實現HTTP服務器。

## 實現步驟

### 步驟 1: 配置HTTP端口
- **目標**: 在 `env.json` 中新增 `HTTP_REQUEST_PORT` 配置
- **位置**: `/home/user/Desktop/hiba/hiba-core/env.json`
- **動作**: 在 `HiBANetwork` 區塊中添加新的端口配置
- **範例配置**:
```json
"HiBANetwork": {
  "HTTP_REQUEST_PORT": 8092,
  // 其他現有配置...
}
```

### 步驟 2: 更新網路配置類
- **目標**: 修改 `HibaNetworkConfig.java` 支援HTTP端口讀取
- **位置**: `/home/user/Desktop/hiba/hiba-core/src/main/java/org/gslab/configuration/HibaNetworkConfig.java`
- **動作**: 新增 `getHttpRequestPort()` 方法

### 步驟 3: 更新初始化類
- **目標**: 修改 `HiBAInitial.java` 載入HTTP端口配置
- **位置**: `/home/user/Desktop/hiba/hiba-core/src/main/java/org/gslab/sdns/HiBAInitial.java`
- **動作**: 添加 `HTTP_REQUEST_PORT` 靜態變數

### 步驟 4: 創建HTTP請求服務器
- **目標**: 創建 `HiBAHttpRequestServer.java`
- **位置**: `/home/user/Desktop/hiba/hiba-core/src/main/java/org/gslab/sdns/HiBAHttpRequestServer.java`
- **功能**: 
  - 使用Netty ServerBootstrap
  - 配置HTTP codec (HttpServerCodec, HttpObjectAggregator)
  - 整合SSL支持 (可選)
  - 實現Runnable介面

### 步驟 5: 創建HTTP請求處理器
- **目標**: 創建 `HttpRequestHandler.java`
- **位置**: `/home/user/Desktop/hiba/hiba-core/src/main/java/org/gslab/sdns/HttpRequestHandler.java`
- **功能**:
  - 繼承 `SimpleChannelInboundHandler<FullHttpRequest>`
  - 解析JSON請求體
  - 轉換為現有的請求格式
  - 調用 `app.sendRequest(request)`
  - 回傳HTTP回應

### 步驟 6: 修改啟動邏輯
- **目標**: 修改 `AutoTrackStarter.java`
- **位置**: `/home/user/Desktop/hiba/hiba-core/src/main/java/org/gslab/AutoTrackStarter.java:148-157`
- **動作**:
  - 移除現有的資料夾監聽while循環
  - 啟動HTTP請求服務器
  - 保持主執行緒運行

### 步驟 7: 測試和驗證
- **目標**: 確保HTTP請求功能正常運作
- **測試項目**:
  - HTTP POST請求接收
  - JSON解析正確性
  - 與現有請求處理邏輯整合
  - 錯誤處理機制
  - 效能測試

## 技術細節

### HTTP規格
- **HTTP方法**: 主要支援 POST 請求
- **內容類型**: `application/json`
- **回應格式**: JSON格式的成功/錯誤回應
- **端口**: 預設 8092 (可在env.json中配置)
- **協議**: HTTP/1.1 (未來可擴展支援HTTP/2)

### 請求格式
```json
{
  "serviceName": "example_service",
  "requestName": "example_request",
  "transferFile": true,
  // 其他服務特定參數...
}
```

### 回應格式
```json
{
  "status": "success|error",
  "message": "詳細訊息",
  "requestId": "唯一請求識別碼",
  "timestamp": "2025-08-08T12:00:00Z"
}
```

## 優勢分析
- **即時性**: HTTP請求立即處理，不需要檔案系統I/O
- **可靠性**: 減少檔案鎖定和權限問題
- **擴展性**: 支援更多請求類型和認證機制
- **效能**: 避免檔案系統的輪詢開銷
- **整合性**: 與現有Netty架構一致

## 實現注意事項
1. 保持與現有請求處理邏輯的相容性
2. 確保適當的錯誤處理和日誌記錄
3. 考慮安全性措施（認證、授權）
4. 處理並發請求的能力
5. 提供適當的配置選項

---
建立時間: 2025-08-08
版本: 1.0