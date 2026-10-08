# GetFile微服務區塊鏈集成 - 實施計劃

## 項目概述

本文檔詳細說明將區塊鏈功能集成到HiBA系統GetFile微服務的實施計劃。主要目標是透過BlockchainManager(BCM)將檔案資源轉換為FileMetadata並上傳至區塊鏈，確保檔案傳輸過程中的完整性驗證。

## 核心功能需求

- 檔案雜湊計算和FileMetadata生成
- 區塊鏈metadata上傳和查詢
- 本地資料庫快取機制
- 檔案接收端的區塊鏈驗證
- 避免重複上傳機制

## 實施階段與TODO清單

### Phase 1: 基礎設施建立 (High Priority)

#### ✅ TODO 1: 創建BlockchainManager(BCM)集成文檔和API設計
**負責組件**: Documentation & API Design  
**預估時間**: 1-2天  
**詳細任務**:
- [x] 在 `docs/BlockchainManager_integration_guide.md` 創建詳細文檔
- [x] 定義BCM的API接口：
  - `protectFile(File, String, String)` → `BlockchainTransaction` (包含txHash)
  - `recoverFromBlockchain(String txHash)` → `FileMetadata`
  - `verifyFileIntegrity(String fileName, String filePath)` → `boolean`
- [x] 定義配置參數和初始化方式
- [x] 撰寫使用範例和錯誤碼說明

**完成標準**: ✅ BCM API文檔完整，包含所有接口定義和使用說明

---

#### ✅ TODO 2: 設計FileMetadata數據結構和相關工具類
**負責組件**: Data Model  
**預估時間**: 1天  
**詳細任務**:
- [x] 創建 `org.aiia.blockchain.model.FileMetadata.java` 類別
  ```java
  public class FileMetadata {
      private String id;              // uniqueID
      private String fileName;
      private String fileHash;
      private long fileSize; 
      private String uploader;
      private LocalDateTime uploadTime;
      private String description;
      private String filePath;
      // 建構子、getter/setter、序列化方法已完成
  }
  ```
- [x] 實現完整的getter/setter方法
- [x] 實現equals()和hashCode()方法
- [x] 已包含在現有的FileManager中作為工具類

**完成標準**: ✅ FileMetadata類別功能完整，可正確序列化和反序列化

---

#### ✅ TODO 3: 實現檔案雜湊計算和metadata生成功能
**負責組件**: Core Utilities  
**預估時間**: 1-2天  
**詳細任務**:
- [x] 創建 `org.aiia.blockchain.util.HashUtil.java`
  - 實現SHA-256雜湊計算
  - 處理大檔案的分塊讀取 (8KB buffer)
  - 包含檔案完整性驗證功能
- [x] 在 `FileManager.createFileMetadata()` 實現metadata生成
- [x] 完整的檔案讀取例外處理
- [x] UUID生成邏輯已實現 (`HashUtil.generateUniqueId()`)

**完成標準**: ✅ 可以從任意檔案正確生成完整的FileMetadata

---

### Phase 2: 核心功能集成 (High Priority)

#### ✅ TODO 4: 集成BlockchainManager到GetFileExecutor
**負責組件**: GetFileExecutor Enhancement  
**預估時間**: 2-3天  
**詳細任務**:
- [x] 修改 `getFileExecutor.java` 的 `execute()` 方法
  - 在檔案傳輸前生成FileMetadata
  - 檢查本地資料庫是否已有該檔案的metadata
  - 如果不存在，透過BCM上傳並獲取txHash
  - 如果存在，直接從資料庫取得txHash
- [x] 修改 `brokerExecute()` 方法支援相同邏輯
- [x] 將txHash和metadata添加到返回結果的Map中
- [x] 添加BCM初始化和配置讀取

**完成標準**: ✅ GetFileExecutor可正確生成metadata並與區塊鏈互動

---

#### ✅ TODO 5: 修改GetFileDistributor處理metadata和txHash
**負責組件**: GetFileDistributor Enhancement  
**預估時間**: 2天  
**詳細任務**:
- [x] 更新 `merge()` 方法處理來自多個節點的metadata
  - 檢查多個節點提供同一檔案時metadata的一致性
  - 處理metadata衝突的情況
- [x] 修改 `getResult()` 方法
  - 將metadata和txHash包含在返回結果中
  - 確保檔案傳輸時同時傳遞metadata資訊
- [x] 更新結果數據結構以支援新欄位

**完成標準**: ✅ GetFileDistributor可正確處理和傳遞metadata資訊

---

### Phase 3: 資料持久化和驗證 (Medium-High Priority)

#### ✅ TODO 6: 實現本地資料庫存儲和查詢metadata功能
**負責組件**: Data Persistence  
**預估時間**: 2-3天  
**詳細任務**:
- [x] SQLite資料庫schema已完成
  - 包含file_metadata和blockchain_transaction表
  - 支援完整的metadata和交易記錄存儲
- [x] 實現 `SQLiteDatabaseManagerImpl` 和 `DatabaseManager`
  - `saveFileMetadata(FileMetadata)`
  - `findTransactionByFileName(String fileName)`
  - `findTransactionByFileId(String fileId)`
  - `getAllMetadata()` 和 `getAllTransactions()`
- [x] 資料庫連接管理和初始化已完成
- [x] 透過FileManager提供高級查詢API

**完成標準**: ✅ 本地資料庫可正確存儲和查詢metadata資訊

---

#### ✅ TODO 7: 實現檔案接收端的區塊鏈驗證功能
**負責組件**: File Verification Service  
**預估時間**: 2-3天  
**詳細任務**:
- [x] ~~創建 `org.gslab.service.FileVerificationService.java`~~ 
  **不需要** - FileProtectionService已提供完整驗證功能
- [x] 實現驗證流程：
  1. ✅ 接收檔案 + metadata + txHash - `verifyFileIntegrity(fileName, filePath)`
  2. ✅ 透過BCM從區塊鏈使用txHash取得原始metadata - `recoverFromBlockchain(txHash)`
  3. ✅ 重新計算接收檔案的雜湊值 - `HashUtil.verifyFileIntegrity()`
  4. ✅ 比對三方數據 - `compareFileMetadata*()`系列方法
  5. ✅ 返回驗證結果和詳細報告 - 通過boolean結果和日誌
- [x] 在GetFile服務的適當位置集成驗證功能 - 已在getFileExecutor中集成BlockchainManager
- [x] 添加驗證失敗的處理邏輯 - ✅ 已在HiBAFileTransferServer中完成

**完成標準**: ✅ **已完成** - 檔案接收端可正確驗證檔案完整性和真實性

---

### Phase 4: 協議和穩定性改進 (Medium Priority)

#### ✅ TODO 8: 修改網路通訊協議支援metadata和txHash傳輸
**負責組件**: Network Protocol Enhancement  
**預估時間**: 1-2天  
**詳細任務**:
- [x] 更新相關Message類別支援新欄位
  - ✅ 修改FileTransferFormat類別
  - ✅ 添加metadata和txHash欄位
- [x] ~~修改MessageCodec處理新數據的序列化~~ - **不需要**，使用Java標準序列化
- [x] 確保向下相容性，支援舊版本節點 - ✅ 透過方法重載保持相容性
- [x] 更新網路傳輸文檔 - ✅ 程式碼中已有詳細註解

**完成標準**: ✅ **已完成** - 網路協議可正確傳輸metadata和txHash資訊

---

#### ✅ TODO 9: 添加錯誤處理和重試機制
**負責組件**: Error Handling & Resilience  
**預估時間**: 1-2天  
**詳細任務**:
- [x] 完整的異常處理體系
  - FileAlreadyProtectedException
  - FileNotValidException
  - FileMetadataCreationException
  - FileMetadataSaveException
- [x] 交易狀態管理 (PENDING/SUCCESS/FAILED)
- [x] 區塊鏈操作失敗時自動更新交易狀態
- [x] 詳細的錯誤日誌和監控 (SLF4J)
- [x] Mock模式支援 (開發/測試環境fallback)

**完成標準**: ✅ 系統在各種異常情況下都能穩定運行

---

### Phase 5: 測試和文檔 (Low Priority)

#### ☐ TODO 10: 撰寫測試案例和整合測試
**負責組件**: Testing & Documentation  
**預估時間**: 2-3天  
**詳細任務**:
- [ ] 單元測試
  - FileMetadata相關類別測試
  - FileHashCalculator測試
  - MetadataRepository測試
  - FileVerificationService測試
- [ ] 整合測試
  - GetFile服務完整流程測試
  - 多節點場景測試
  - 錯誤情況測試
- [ ] 性能測試
  - 區塊鏈互動的延遲影響
  - 大檔案處理性能
- [ ] 更新使用文檔和部署指南

**完成標準**: 所有核心功能都有對應的測試案例，整合測試通過

---

## 實施建議和注意事項

### 實施順序
1. **先完成TODO 1**，確立BCM的API設計和文檔
2. **並行進行TODO 2和3**，建立基礎數據結構和工具
3. **按順序實現TODO 4-7**，逐步集成核心功能
4. **最後處理TODO 8-10**，完善系統穩定性和測試

### 風險控制
- 每完成一個TODO都要進行基本功能測試
- 保持代碼的向下相容性
- 記錄所有API變更和配置修改
- 準備回滾計劃

### 配置管理
- 所有區塊鏈相關配置都添加到 `env.json`
- 提供開發和生產環境的不同配置
- 支援功能開關，可以暫時禁用區塊鏈功能

## 進度追蹤

**項目開始日期**: ___________  
**預計完成日期**: ___________  
**實際完成日期**: ___________  

### 里程碑
- [x] Phase 1 完成: 基礎設施建立 ✅ **已完成** (TODO 1-3)
- [x] Phase 2 完成: 核心功能集成 ✅ **已完成** (TODO 4-5)
- [x] Phase 3 完成: 資料持久化和驗證 ✅ **已完成** (TODO 6,7,9 全部完成)
- [x] Phase 4 完成: 協議和穩定性改進 ✅ **已完成** (TODO 8 完成)
- [ ] Phase 5 完成: 測試和文檔

---

## 📊 當前進度總結

**已完成**: 9/10 TODO項目 (90%) ✅
**剩餘**: 1/10 TODO項目 (10%) ⏳

### ✅ 已完成項目
- TODO 1: BlockchainManager集成文檔和API設計
- TODO 2: FileMetadata數據結構和相關工具類
- TODO 3: 檔案雜湊計算和metadata生成功能
- TODO 4: 集成BlockchainManager到GetFileExecutor
- TODO 5: 修改GetFileDistributor處理metadata和txHash
- TODO 6: 本地資料庫存儲和查詢metadata功能
- TODO 7: 實現檔案接收端的區塊鏈驗證功能 ✅ **已完成**
- TODO 8: 修改網路通訊協議支援metadata和txHash傳輸 ✅ **已完成**
- TODO 9: 錯誤處理和重試機制

### ⏳ 剩餘項目
- TODO 10: 撰寫測試案例和整合測試 📝 **最後一個項目**

---
### 目前的測試方式為手動發起getFile請求，並觀察區塊鏈和資料庫的互動情況
- 同階層傳輸與驗證已經完成
- 跨階層傳輸尚有問題，沒有從upperBroker回傳資料到broker, node9有接收到metadata和其他資訊，但沒有接收到資料的本體

**最後更新**: 2025-07-30  
**版本**: 4.0 - Phase 3 & 4 驗證和協議功能完成  