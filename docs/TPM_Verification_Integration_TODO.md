# TPM 資料驗證整合 TODO

## 概述

本文檔描述如何將 `org.gslab.tpm` 和 `org.gslab.handlers` 這兩個 package 中的 TPM 資料驗證功能整合到 HiBA 專案中。

### 相關 Package

| Package | 說明 |
|---------|------|
| `org.gslab.tpm` | TPM 核心服務，包含 TPM 硬體操作、公鑰快取、區塊鏈整合 |
| `org.gslab.handlers` | Netty Handler 實作，包含簽章/驗證 Handler、重放攻擊防護 |
| `org.gslab.nettyhandlers_hiba` | 現有的 HiBA Netty Handler（需要整合 TPM 驗證） |

---

## 現有元件分析

### TPM 核心服務 (`org.gslab.tpm`)

| 類別 | 功能 | 整合優先級 |
|------|------|-----------|
| `TPMService` | TPM 硬體操作：連接、簽章、驗證、EK 管理 | 🔴 高 |
| `PublicKeyCacheService` | 公鑰快取（Caffeine），減少區塊鏈查詢 | 🟡 中 |
| `Web3BlockchainService` | 區塊鏈智慧合約互動，設備註冊/查詢 | 🟡 中 |
| `BlockchainContractService` | 區塊鏈合約模擬（開發測試用） | 🟢 低 |
| `SignedDataPackage` | 簽章資料封包格式 | 🔴 高 |
| `SignResult` | 簽章結果封裝 | 🟡 中 |
| `FileMetadata` | 檔案 Metadata 模型 | 🟡 中 |

### 驗證 Handler (`org.gslab.handlers`)

| 類別 | 功能 | 整合優先級 |
|------|------|-----------|
| `TPMSigningOutboundHandler` | 出站資料自動 TPM 簽章 | 🔴 高 |
| `TPMVerifyInboundHandler` | 入站資料 TPM 簽章驗證（基本版） | 🔴 高 |
| `TPMVerifyWithCacheHandler` | 入站驗證（支援區塊鏈公鑰快取） | 🔴 高 |
| `VerificationConfig` | 驗證配置（時間戳/Nonce 驗證開關） | 🟡 中 |
| `VerificationResult` | 驗證結果封裝（含錯誤碼） | 🟡 中 |
| `TimestampValidator` | 時間戳驗證（防止重放攻擊） | 🟡 中 |
| `NonceValidator` | Nonce 驗證（防止重放攻擊） | 🟡 中 |

---

## 整合任務清單

### Phase 1: 基礎設施準備

- [ ] **1.1 確認 TPM 環境**
  - 確認目標設備有 TPM 2.0 晶片或已安裝 TPM 模擬器
  - 執行 `./scripts/tpm_init_key.sh 0x81000001` 初始化持久化金鑰
  - 驗證 TPM 連接：`tpm2_getcap properties-fixed`

- [ ] **1.2 確認區塊鏈環境（若使用區塊鏈模式）**
  - 部署 `TPMDeviceRegistry.sol` 智慧合約
  - 配置 `BlockchainConfig` 中的 RPC URL 和合約地址
  - 註冊設備 EK 到區塊鏈

- [ ] **1.3 相依套件確認**
  - Caffeine Cache（公鑰快取）
  - Web3j（區塊鏈互動）
  - TSS.Java（TPM 操作）

### Phase 2: 伺服器端整合

- [ ] **2.1 建立 TPM 服務實例**
  ```java
  // 在服務啟動時初始化 TPMService
  TPMService tpmService = new TPMService();
  // 或使用模擬器（開發環境）
  TPMService tpmService = new TPMService(true);
  ```

- [ ] **2.2 整合驗證 Handler 到現有 Netty Pipeline**

  **選項 A：基本驗證模式（不使用區塊鏈）**
  ```java
  pipeline.addLast(new TPMVerifyInboundHandler());
  // 接續現有業務 Handler
  pipeline.addLast(new FileTransferHandler());
  ```

  **選項 B：區塊鏈驗證模式**
  ```java
  Web3BlockchainService blockchainService = new Web3BlockchainService.Builder()
      .rpcUrl("http://localhost:8545")
      .contractAddress("0x...")
      .privateKey("0x...")
      .build();

  pipeline.addLast(new TPMVerifyWithCacheHandler(
      TPMVerifyWithCacheHandler.VerificationMode.BLOCKCHAIN_PREFERRED,
      blockchainService
  ));
  pipeline.addLast(new FileTransferHandler());
  ```

- [ ] **2.3 配置驗證參數**
  ```java
  // 根據場景選擇配置
  VerificationConfig config = VerificationConfig.defaultConfig();     // 預設（5分鐘有效期）
  VerificationConfig config = VerificationConfig.strictConfig();      // 嚴格（1分鐘有效期）
  VerificationConfig config = VerificationConfig.relaxedConfig();     // 寬鬆（30分鐘有效期）
  VerificationConfig config = VerificationConfig.noReplayProtection(); // 無重放防護（測試用）
  ```

- [x] **2.4 共享 NonceValidator 實例** ✅ 已完成
  - 使用 `NonceValidator.getSharedInstance()` 取得共享實例
  - 所有 `MessageTPMVerifyHandler` 預設使用共享的 `NonceValidator`
  - 採用 Initialization-on-demand holder idiom，線程安全且延遲初始化
  ```java
  // 取得共享實例
  NonceValidator sharedValidator = NonceValidator.getSharedInstance();

  // MessageTPMVerifyHandler 預設已使用共享實例
  pipeline.addLast(new MessageTPMVerifyHandler(mode, blockchainService));
  ```

### Phase 3: 客戶端整合

- [ ] **3.1 建立 TPM 服務實例**
  ```java
  TPMService tpmService = new TPMService();
  ```

- [ ] **3.2 整合簽章 Handler 到 Netty Pipeline**
  ```java
  pipeline.addLast(new TPMSigningOutboundHandler(tpmService));
  // 接續其他 Handler
  ```

- [ ] **3.3 資料封裝**
  - 發送前的資料會自動被 `TPMSigningOutboundHandler` 封裝成 `SignedDataPackage`
  - 封包包含：原始資料、簽章、公鑰、時間戳、Nonce、設備 ID

### Phase 4: 整合到 FileTransferHandler

- [ ] **4.1 修改 FileTransferHandler 接收已驗證資料**

  **現況：** `FileTransferHandler` 直接處理 `FileTransferMessage`

  **目標：** 在前面加入 TPM 驗證層

  ```java
  // Pipeline 順序
  pipeline.addLast("decoder", new FileTransferMessageDecoder());
  pipeline.addLast("tpm-verify", new TPMVerifyInboundHandler(config));
  pipeline.addLast("business", new FileTransferHandler());
  ```

- [ ] **4.2 處理驗證後的資料**
  - `TPMVerifyInboundHandler` 驗證成功後會傳遞原始 JSON 字串
  - 需要在 `FileTransferHandler` 前加入反序列化 Handler
  - 或修改 `FileTransferHandler` 接收 `String` 並自行反序列化

- [ ] **4.3 錯誤處理**
  - 驗證失敗時，`TPMVerifyInboundHandler` 會發送錯誤回應並關閉連線
  - 確認客戶端能正確處理驗證錯誤回應

### Phase 5: 區塊鏈整合（可選）

- [ ] **5.1 設備初次註冊流程**
  ```java
  // 取得設備 EK 和公鑰
  String ekFingerprint = tpmService.getEKFingerprint();
  PublicKey publicKey = tpmService.getSigningPublicKey();

  // 註冊到區塊鏈
  blockchainService.registerDeviceIfNotExists(ekFingerprint, publicKey);
  ```

- [ ] **5.2 選擇驗證模式**
  | 模式 | 說明 | 適用場景 |
  |------|------|----------|
  | `TRUST_PACKAGE` | 信任封包內公鑰 | 封閉網路、內部系統 |
  | `BLOCKCHAIN_STRICT` | 僅接受區塊鏈註冊設備 | 高安全性場景 |
  | `BLOCKCHAIN_PREFERRED` | 優先區塊鏈，fallback 到封包 | 逐步導入階段 |

- [ ] **5.3 啟用區塊鏈事件監聽**
  ```java
  blockchainService.startEventListeners();
  // 自動處理設備註冊/撤銷/重啟用事件
  // 自動同步快取
  ```

### Phase 6: 監控與維運

- [ ] **6.1 日誌監控**
  - TPM 相關日誌使用 `🔐` emoji 標記
  - 區塊鏈相關日誌使用 `🔗` 和 `📝` emoji 標記
  - 驗證成功/失敗使用 `✅`/`❌` emoji 標記

- [ ] **6.2 快取統計**
  ```java
  // 定期檢查快取效能
  cacheService.printStats();
  nonceValidator.printStats();
  ```

- [ ] **6.3 效能調整**
  - `NonceValidator` 預設保留 10 分鐘內的 Nonce
  - `PublicKeyCacheService` 預設快取 1000 筆公鑰
  - 根據實際流量調整這些參數

---

## 整合注意事項

### 1. TPM 連接管理

```java
// TPMService 實作 AutoCloseable，建議使用 try-with-resources
// 但若需要長期使用，可以作為服務單例
@Bean
public TPMService tpmService() throws TPMConnectionException {
    return new TPMService();
}

// 應用程式關閉時記得釋放資源
@PreDestroy
public void cleanup() {
    tpmService.close();
}
```

### 2. 執行緒安全

- `TPMService.sign()` 方法是 synchronized 的，多執行緒存取安全
- `NonceValidator` 使用 Caffeine Cache，執行緒安全
- `PublicKeyCacheService` 使用 Caffeine Cache，執行緒安全

### 3. 驗證流程圖

```
Client                           Server
  |                                |
  |---(1) 原始資料 ------------------>|
  |      TPMSigningOutboundHandler   |
  |      自動簽章並封裝為             |
  |      SignedDataPackage           |
  |                                  |
  |<--(2) SignedDataPackage ---------|
  |                                  |
  |      TPMVerifyInboundHandler     |
  |      (a) 解析封包                |
  |      (b) 驗證時間戳              |
  |      (c) 驗證 Nonce              |
  |      (d) 取得公鑰（快取/區塊鏈）  |
  |      (e) 驗證簽章                |
  |                                  |
  |      驗證成功 -> 傳遞原始資料     |
  |      驗證失敗 -> 回傳錯誤並關閉   |
  |                                  |
  |<--(3) 業務回應 ------------------|
```

### 4. 錯誤碼對照表

| ErrorCode | 說明 | 建議處理 |
|-----------|------|----------|
| `TIMESTAMP_EXPIRED` | 訊息過期 | 檢查系統時間同步 |
| `TIMESTAMP_FUTURE` | 時間戳在未來 | 檢查系統時間同步 |
| `NONCE_MISSING` | 缺少 Nonce | 確認客戶端有產生 Nonce |
| `NONCE_REUSED` | Nonce 重複（重放攻擊） | 調查是否有惡意重送 |
| `SIGNATURE_INVALID` | 簽章無效 | 資料可能被竄改 |
| `DEVICE_NOT_REGISTERED` | 設備未註冊（區塊鏈模式） | 執行設備註冊流程 |
| `PARSE_ERROR` | 封包解析錯誤 | 檢查資料格式 |

---

## 測試清單

- [ ] 單元測試：`TPMService` 簽章/驗證
- [ ] 單元測試：`TimestampValidator` 各種邊界情況
- [ ] 單元測試：`NonceValidator` 重放攻擊偵測
- [ ] 整合測試：完整 Pipeline 驗證流程
- [ ] 整合測試：區塊鏈模式下的設備註冊與驗證
- [ ] 效能測試：高併發下的 TPM 簽章效能
- [ ] 效能測試：快取命中率

---

## 相關文件

- [BlockchainManager Integration Guide](./BlockchainManager_Interagration_Guide.md)
- [GetFile Blockchain Integration TODO](./GetFile_Blockchain_Integration_TODO.md)
- [HiBA Node Cache Strategy](./hiba-node-cache-strategy.md)

---

## 更新記錄

| 日期 | 作者 | 說明 |
|------|------|------|
| 2025-12-02 | AI Assistant | 初版建立 |
