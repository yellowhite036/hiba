# HiBA系統架構分析報告
**Hierarchical Brokering Architecture (HiBA) - Software Defined Network**

## 目錄
- [系統概述](#系統概述)
- [架構設計](#架構設計)
- [核心組件分析](#核心組件分析)
- [資源管理機制](#資源管理機制)
- [服務架構](#服務架構)
- [網路通訊](#網路通訊)
- [部署配置](#部署配置)
- [技術棧](#技術棧)
- [系統流程](#系統流程)
- [擴展性分析](#擴展性分析)

---

## 系統概述

HiBA (Hierarchical Brokering Architecture) 是一個基於分層代理架構的軟體定義網路(SDN)系統，重新定義了網路拓撲結構。系統採用分散式架構，透過階層式的broker管理機制，實現資源的動態發現、分配和管理。

### 核心特點
- **分層架構**: 支援多層級的網路拓撲
- **動態broker選舉**: 基於評分機制的自動broker選擇
- **資源管理**: 分散式資源發現和分配
- **容錯機制**: 支援broker失效後的重新選舉
- **微服務架構**: 支援多種服務的動態註冊和執行

---

## 架構設計

### 群組結構

系統採用三層群組架構：

```
                    ┌─────────────────────────────────────┐
                    │            group3 (上層)            │
                    │     Broker2 + Broker1 + Broker3    │
                    │            + Node4                 │
                    └─────────────┬───────────────────────┘
                                 │ (Upper Broker)
                    ┌────────────┴──────────────┐
                    │                          │
        ┌───────────▼──────────┐    ┌─────────▼──────────┐
        │     group1 (下層)     │    │     group2 (下層)   │
        │ Broker1 + Node1 +    │    │ Broker3 + Node5 +  │
        │ Node2 + Node3        │    │ Node6              │
        └──────────────────────┘    └────────────────────┘
```

### 層級關係
1. **group1**: Broker1作為代理，管理Node1、Node2、Node3
2. **group2**: Broker3作為代理，管理Node5、Node6
3. **group3**: Broker2作為upper broker，管理Broker1、Broker3、Node4

### 關鍵設計原則
- 第一個節點為該群組的代理(broker)
- 代理節點同時也是該群組的成員
- group3為group1和group2的上層群組
- broker失效後會從剩餘成員中重新選出新broker

---

## 核心組件分析

### 1. HiBAService.java
**功能**: 服務建構器和管理中心
```java
public class HiBAService {
    private String name;           // 服務名稱
    private Executor executor;     // 執行器
    private Distributor distributor; // 分配器
    private Taker taker;          // 接收器
}
```

**設計模式**: Builder Pattern
- 統一管理三個核心組件的生命週期
- 支援服務的動態實例化和配置
- 提供統一的服務接口

### 2. HiBANetwork.java
**功能**: 網路通訊核心引擎

#### 主要子組件：

##### MulticastBrokerSelector
- **責任**: Broker選舉和管理
- **機制**: 基於評分的競選制度
- **狀態機**: init → connection → highestScoreBroadcast → isBroker/isClient

##### KeepAliver系列
- **BrokerKeepAliver**: 下層到上層broker的心跳檢測
- **ClientKeepAliver**: Broker對下層節點的心跳管理
- **AcctServerKeepAliver**: Broker到會計伺服器的心跳
- **AcctClientKeepAliver**: 會計伺服器對broker的心跳管理

##### 網路管理
- **反向通道管理**: 處理broker間的SSH隧道連線
- **Multicast控制**: 處理廣播消息和群組發現
- **SSL支援**: 提供安全通訊機制

### 3. 三層服務架構

#### Taker (接收器)
- **職責**: 檢查當前層級是否能處理請求
- **接口**: `checkCanHandleRequest(boolean hasBroker, Map<String, Object> resourceInThisTier)`
- **範例**: `getFileTaker.java` 檢查檔案資源是否存在

#### Distributor (分配器)  
- **職責**: 將任務分配給擁有資源的節點
- **接口**: `mappingTask()`, `mappingLocal()`, `merge()`, `getResult()`
- **範例**: `getFileDistributor.java` 分配檔案傳輸任務

#### Executor (執行器)
- **職責**: 實際執行分配的任務
- **接口**: `execute()`, `brokerExecute()`
- **範例**: `getFileExecutor.java` 執行檔案傳輸

---

## 資源管理機制

### 資源清單結構
```json
{
  "NodeName1": {
    "fileName": ["file1.txt", "file2.txt"],
    "modelType": ["roadIntersection"],
    "deviceInfo": [...]
  },
  "NodeName2": {
    "fileName": ["file3.txt"],
    ...
  }
}
```

### 資源發現流程
1. **註冊階段**: 節點加入群組時向broker報告資源清單
2. **同步階段**: 透過`ResourceUpdateMessage`定期更新資源狀態
3. **查詢階段**: 使用迴圈遍歷的方式匹配資源請求

### 資源分配策略
- **本地優先**: 先檢查broker本身是否有資源
- **群組內分配**: 在同一群組內尋找資源
- **向上轉發**: 找不到資源時向上層broker請求

---

## 服務架構

### 微服務設計
系統採用微服務架構，每個服務都有獨立的Distributor/Executor/Taker實現：

#### 已實現的服務
1. **getFile**: 檔案傳輸服務
2. **getModel**: 模型獲取服務  
3. **ntp**: 時間同步服務
4. **trainDeepModel**: 深度學習訓練服務
5. **initDevice**: 設備初始化服務

#### 服務配置 (service.json)
```json
{
  "mService": {
    "getFile": {
      "type": "java",
      "input": ["fileName"],
      "resource": {
        "fileName": ["file1.txt", "file2.txt"]
      },
      "build": {
        "Distributor": "getFileDistributor",
        "Executor": "getFileExecutor", 
        "Taker": "getFileTaker"
      },
      "method": {
        "fileDirectory": "./files/",
        "receiveDirectory": "./receiveFile/"
      }
    }
  }
}
```

### 服務生命週期
1. **註冊**: 透過service.json定義服務配置
2. **實例化**: 使用HiBAService.builder建立服務實例
3. **執行**: 透過RequestListener接收和處理請求
4. **回應**: 將結果回傳給請求端

---

## 網路通訊

### 通訊協議
- **基礎框架**: Netty NIO
- **編解碼**: HiBAFrameDecoder + MessageCodec
- **傳輸格式**: 自定義二進制協議

### 訊息類型
- **管理訊息**: LoginMessage, TestMessage, BrokerCheckMessage
- **資源訊息**: ResourceUpdateMessage, ScorePublishMessage
- **服務訊息**: TaskStartMessage, TaskFinishMessage
- **檔案傳輸**: FileTransferMessage, FileDownloadMessage

### 連線管理
- **短連線**: 一般請求回應
- **長連線**: 心跳檢測和狀態同步
- **反向通道**: SSH隧道用於跨NAT通訊

### 多播支援
- **群組發現**: 透過multicast尋找同層節點
- **狀態廣播**: 廣播broker選舉結果
- **配置**: 支援自定義multicast介面和地址

---

## 部署配置

### 環境配置 (env.json)

#### HiBANetwork配置
```json
{
  "HiBANetwork": {
    "isAccountingServer": true,
    "nodeName": "437k901",
    "HiBA_PORT": 8091,
    "DATA_PORT": 8088,
    "REVERSE_DATA_PORT": 8089,
    "reverseChannel": true,
    "multicastPort": 4321,
    "multicastIP": "230.0.0.0",
    "accountingServer": "163.18.49.31",
    "autoGetNetworkInterface": true,
    "mandatoryBroker": false,
    "offlineBrokerScan": true
  }
}
```

#### 行動網路支援
```json
{
  "MobileNetwork": {
    "state": false,
    "connectionSignalQuality": 70,
    "disconnectSignalQuality": 60,
    "wifiInterface": "wlx04bad60abb00",
    "OBU": false
  }
}
```

### Ansible部署
- **自動化部署**: 使用Ansible playbooks
- **服務管理**: 支援啟動、停止、重啟、更新
- **多節點部署**: 透過hosts.ini管理節點清單

---

## 技術棧

### 核心技術
- **語言**: Java 8+
- **網路框架**: Netty 4.x
- **序列化**: 自定義MessageCodec
- **日誌**: SLF4J + Logback
- **建構工具**: Maven

### 依賴框架
- **JSON處理**: 自定義JsonFileTool
- **加密**: AES對稱加密
- **檔案操作**: Java NIO
- **系統監控**: 自定義效能監控工具

### 部署工具
- **容器化**: 支援Docker部署
- **自動化**: Ansible playbooks
- **監控**: 自定義硬體使用率監控

---

## 系統流程

### 節點啟動流程
1. **初始化**: 讀取env.json和service.json配置
2. **網路啟動**: 啟動HiBANetwork服務
3. **多播發現**: 透過multicast尋找同層節點
4. **Broker選舉**: 基於評分進行broker競選
5. **服務註冊**: 向broker註冊本節點資源
6. **連線建立**: 建立到上層broker的連線

### 資源請求流程 (以getFile為例)
```
Node1 → Broker1: 請求file.txt
       ↓
   Taker檢查: 群組內是否有file.txt?
       ↓ (有)
   Distributor: 分配任務給Node3
       ↓
   Node3 Executor: 執行檔案傳輸
       ↓
   Node3 → Broker1 → Node1: 傳輸file.txt
```

### Broker失效處理
1. **檢測失效**: 心跳超時檢測broker離線
2. **重新選舉**: 剩餘節點進行新的broker選舉
3. **狀態恢復**: 新broker接管群組管理
4. **連線重建**: 重新建立到上層的連線

---

## 擴展性分析

### 水平擴展
- **新增節點**: 透過multicast自動發現和加入
- **動態負載**: 支援節點動態加入和離開
- **資源彈性**: 資源清單動態更新

### 垂直擴展  
- **新增服務**: 透過service.json配置新的微服務
- **功能擴展**: 三層架構支援任意功能的擴展
- **協議擴展**: MessageCodec支援新訊息類型

### 地理分佈
- **多層架構**: 支援任意層數的階層結構
- **跨網段**: 透過反向通道支援NAT穿透
- **區域化**: 每個地理區域可以是一個group

### 效能最佳化
- **本地快取**: 資源清單本地快取
- **連線池**: 重用網路連線
- **非同步處理**: 基於Netty的非同步I/O

---

## 系統優勢

### 容錯性
- **自動容錯**: Broker失效自動重選
- **心跳監控**: 多層次的連線監控
- **冗餘設計**: 多個節點可提供相同資源

### 可擴展性
- **模組化設計**: 服務和網路分離
- **標準化接口**: 統一的三層服務架構
- **動態配置": 運行時配置更新

### 效能
- **分散式架構**: 避免單點瓶頸
- **本地優先**: 減少跨網路傳輸
- **非同步處理**: 高並發支援

---

## 待改進領域

### 安全性
- 需要加強身份驗證機制
- 訊息完整性校驗
- 存取控制和權限管理

### 監控
- 需要更完善的系統監控
- 效能指標收集和分析
- 告警機制

### 測試
- 需要更完整的單元測試
- 整合測試自動化
- 壓力測試和效能測試
