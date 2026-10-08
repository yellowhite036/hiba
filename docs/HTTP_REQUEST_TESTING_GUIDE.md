# HTTP Request Testing Guide

## 部署後測試HTTP請求功能

### 基本資訊
- **HTTP端口**: 8092 (在 env.json 中配置的 HTTP_REQUEST_PORT)
- **請求方法**: POST
- **Content-Type**: application/json
- **回應格式**: JSON

### 測試請求格式

#### 1. 基本請求格式
```bash
curl -X POST http://localhost:8092/ \
  -H "Content-Type: application/json" \
  -d '{
    "serviceName": "your_service_name",
    "requestName": "your_request_name",
    "transferFile": true,
    "additionalParam1": "value1",
    "additionalParam2": "value2"
  }'
```

#### 2. 根據現有的請求檔案範例

**參考 getFileRequest.json 的格式**:
```bash
curl -X POST http://localhost:8092/ \
  -H "Content-Type: application/json" \
  -d '{
    "serviceName": "getFile",
    "transferFile": true,
    "fileName": "example.txt",
    "targetPath": "./download/"
  }'
```

**參考現有服務的通用格式**:
```bash
curl -X POST http://localhost:8092/ \
  -H "Content-Type: application/json" \
  -d '{
    "serviceName": "NTP",
    "requestName": "timeSync",
    "transferFile": false
  }'
```

### 必要欄位
- **serviceName**: 必要欄位，服務名稱(對應到service.json中的服務)

### 自動產生的欄位
系統會自動添加以下欄位：
- **nodeName**: 節點名稱 (來自env.json)
- **requestTimeStamp**: 時間戳記
- **requestDate**: 格式化日期
- **receiveTargetPath**: 接收目標路徑
- **tier**: 層級 (預設為0)

### 預期回應格式

#### 成功回應 (HTTP 200)
```json
{
  "status": "success",
  "message": "Request processed successfully",
  "requestId": "your_service_name",
  "serviceName": "your_service_name",
  "timestamp": "2025-08-08T12:00:00Z"
}
```

#### 錯誤回應範例

**缺少必要欄位 (HTTP 400)**:
```json
{
  "status": "error",
  "message": "Missing required fields: serviceName is mandatory",
  "timestamp": "2025-08-08T12:00:00Z"
}
```

**無效的JSON格式 (HTTP 400)**:
```json
{
  "status": "error",
  "message": "Invalid JSON format: Unexpected character...",
  "timestamp": "2025-08-08T12:00:00Z"
}
```

**不支援的HTTP方法 (HTTP 405)**:
```json
{
  "status": "error",
  "message": "Only POST method is supported",
  "timestamp": "2025-08-08T12:00:00Z"
}
```

### 測試步驟

#### 1. 驗證HTTP服務器啟動
```bash
# 檢查端口是否開啟
netstat -tulpn | grep 8092
# 或使用
ss -tulpn | grep 8092
```

#### 2. 測試基本連線
```bash
curl -X GET http://localhost:8092/
# 預期回應: 405 Method Not Allowed
```

#### 3. 測試有效請求
```bash
curl -X POST http://localhost:8092/ \
  -H "Content-Type: application/json" \
  -d '{
    "serviceName": "getFile",
    "transferFile": true,
    "fileName": "test.txt"
  }'
```

#### 4. 測試錯誤情況
```bash
# 測試缺少Content-Type
curl -X POST http://localhost:8092/ \
  -d '{"serviceName": "test"}'

# 測試空請求體
curl -X POST http://localhost:8092/ \
  -H "Content-Type: application/json" \
  -d ''

# 測試無效JSON
curl -X POST http://localhost:8092/ \
  -H "Content-Type: application/json" \
  -d '{"serviceName": "test"'

# 測試缺少serviceName
curl -X POST http://localhost:8092/ \
  -H "Content-Type: application/json" \
  -d '{"requestName": "test"}'
```

### 日誌監控
請求處理過程會在日誌中記錄：
- 接收到的HTTP請求內容
- 請求處理成功/失敗訊息
- 錯誤詳細資訊

查看日誌指令：
```bash
tail -f logs/hiba.log | grep -i "http"
```

### 與原有系統的相容性
- HTTP請求會被轉換為與原有資料夾監聽相同的內部格式
- 所有現有的服務邏輯保持不變
- 請求處理流程與原有方式完全一致

### 疑難排解

1. **連線被拒絕**: 檢查HTTP_REQUEST_PORT是否正確設定和啟動
2. **405錯誤**: 確保使用POST方法
3. **400錯誤**: 檢查JSON格式和必要欄位
4. **500錯誤**: 查看伺服器日誌了解詳細錯誤訊息

### 效能測試
可使用以下工具進行效能測試：
```bash
# 使用Apache Bench
ab -n 100 -c 10 -T application/json -p request.json http://localhost:8092/

# 使用wrk
wrk -t12 -c400 -d30s -s script.lua http://localhost:8092/
```

---
建立時間: 2025-08-08
版本: 1.0