# HiBA 專案深度分析 (Deep Analysis of HiBA Project)

## 1. 專案概述

HiBA (High-performance Intelligent Broker Architecture) 是一個基於 Java Netty 的分散式微服務架構，專為動態、潛在不穩定的網路環境（例如車聯網 V2X、移動自組網 MANET 或邊緣運算場景）設計。其核心思想是**資源感知**、**動態服務協調**和**高效能通訊**。

系統由兩種主要角色的節點組成：**普通節點 (Node)** 和 **仲介者 (Broker)**。它們共同協作來完成服務請求，並透過 Netty 實現底層的高效能網路通訊。

## 2. 專案工作流程 (Overall Workflow)

HiBA 系統的工作流程基於一個 `Taker -> Distributor -> Executor` 的設計模式，旨在實現服務的智慧分發和執行。

1.  **服務發起 (Taker - 任務需求方):**
    *   當一個節點（客戶端）需要執行一個服務時（例如，`getFile`），它會扮演 `Taker` 的角色。
    *   `Taker` 會根據 `service.json` 中定義的服務 `input` 來建立一個請求訊息。
    *   這個請求被發送到網路上，通常是發給 Broker。

2.  **服務分發 (Distributor - 任務分配方):**
    *   **Broker** 節點接收到來自 `Taker` 的請求。Broker 的核心職責是服務分發。
    *   Broker 會載入與該服務對應的 `Distributor` 類別（例如，`getFileDistributor`）。
    *   `Distributor` 的邏輯是整個系統的智慧所在。它會查詢自己維護的網路節點列表，並根據請求的參數和 `service.json` 中定義的 `resource`，找到最適合執行此任務的節點。
    *   例如，對於 `getFile` 請求，`Distributor` 會尋找哪個節點的 `resource` 中宣告了擁有目標 `fileName`。

3.  **服務執行 (Executor - 任務執行方):**
    *   Broker 將請求轉發給選中的**普通節點 (Node)**。
    *   該節點接收到請求後，扮演 `Executor` 的角色。
    *   它會載入對應的 `Executor` 類別（例如，`getFileExecutor`）。
    *   `Executor` 執行實際的業務邏輯（例如，從本地讀取檔案）。
    *   執行完成後，`Executor` 將結果回傳給最初的 `Taker`（可能透過 Broker 中轉，或透過反向通道直接回傳）。

4.  **網路發現與狀態維護:**
    *   節點之間透過 **Multicast**（由 `env.json` 中的 `multicastController` 和 `HiBANetwork.multicastIP` 設定）來互相發現。
    *   普通節點會定期向 Broker 報告自己的狀態和可提供的資源（`HiBAService.updateDeviceInfo`），Broker 根據這些資訊建立一個即時的網路資源拓撲圖。

## 3. 普通節點 (Node) vs. 仲介者 (Broker)

### 普通節點 (Node / Executor)

*   **角色**: 網路中的工作單元，是服務的**執行者**和**發起者**。
*   **工作模式**: 
    1.  **資源宣告**: 啟動時，根據自己的 `service.json` 設定，向 Broker 註冊自己能提供的服務和資源。
    2.  **監聽任務**: 持續監聽來自 Broker 的任務指派。
    3.  **執行任務**: 一旦被指派，就載入對應的 `Executor` 類別來執行任務，並回傳結果。
    4.  **發起請求**: 任何普通節點也可以作為 `Taker`，向 Broker 發起服務請求。
*   **配置**: 在 `env.json` 中，一個普通節點通常會將 `isAccountingServer` 設為 `false`。

### 仲介者 (Broker / Distributor)

*   **角色**: 網路的**協調者**和**管理者**，是服務的**分配者**。
*   **工作模式**: 
    1.  **節點註冊與管理**: 接收來自所有普通節點的註冊和心跳，維護一個包含所有節點能力（CPU、記憶體、可用資源）的動態列表。
    2.  **請求路由**: 監聽來自 `Taker` 的所有服務請求。
    3.  **智慧分派**: 當請求到達時，它使用對應的 `Distributor` 邏輯來決策哪個節點最適合執行任務。
    4.  **容錯與負載平衡**: 透過監控節點狀態，Broker 可以避開已經離線或負載過高的節點。
*   **配置**: 在 `env.json` 中，Broker 節點會將 `isAccountingServer` 設為 `true`。`mandatoryBroker` 和 `offlineBrokerScan` 等參數進一步定義了節點對 Broker 的依賴程度。

## 4. Java Netty 實現節點間的溝通

HiBA 專案利用 Java Netty 這個高效能的網路框架來實現節點間的非同步、事件驅動通訊。

### 4.1 啟動與引導 (Bootstrap)

每個 HiBA 節點在啟動時，都會初始化 Netty 的 `Bootstrap` 或 `ServerBootstrap`。

*   **伺服器端 (Broker & Executor):** 每個節點都會啟動一個 Netty **伺服器**，監聽在 `env.json` 中設定的 `HiBA_PORT`（例如 8091），用於接收來自其他節點的連線和請求。
*   **客戶端 (Node & Taker):** 當一個節點需要主動連接到 Broker 或其他節點時，它會使用 Netty 的 `Bootstrap` 來建立一個**客戶端**連線。

### 4.2 核心關鍵：通道管線 (Channel Pipeline)

Netty 的 `ChannelPipeline` 是通訊的核心。每個連線（`Channel`）都有自己的一個 Pipeline，所有進出的資料都會流經這個鏈上的一系列處理器（`ChannelHandler`）。

**接收資料 (Inbound): `網路 -> ByteBuf -> 訊息物件 -> 業務邏輯`**

1.  **`ByteToMessageDecoder` (位元組到訊息解碼器):** 解決 TCP 黏包/拆包問題，從連續的位元組流中識別出一個個完整的訊息邊界。
2.  **`MessageToMessageDecoder` (訊息到訊息解碼器 / Deserializer):** 將上一步得到的 `ByteBuf` 反序列化成 HiBA 的 Java 訊息物件（例如 `FileDownloadMessage`）。
3.  **`SimpleChannelInboundHandler` (業務邏輯處理器):** 接收到已經是 Java 物件的訊息，並將其分派給對應的業務邏輯單元（例如呼叫 `Distributor` 或 `Executor`）。

**傳送資料 (Outbound): `業務邏輯 -> 訊息物件 -> ByteBuf -> 網路`**

1.  **`MessageToByteEncoder` (訊息到位元組編碼器 / Serializer):** 將業務邏輯產生的 Java 物件序列化成 `ByteBuf`。
2.  **`LengthFieldPrepender` (長度欄位附加器):** 在序列化後的 `ByteBuf` 前面，加上一個表示訊息長度的欄位，以便接收方正確解碼。

### 4.3 通道管理 (Channel Management)

`ChannelRegistry.java` 在此架構中扮演著至關重要的**通訊錄**角色。

*   **註冊**: 當一個普通節點成功連接到 Broker 時，Broker 會將這個新建立的 `Channel` 與該節點的 ID 關聯起來，並存入 `ChannelRegistry`。
*   **查詢與復用**: 當 `Distributor` 決定將任務派送給特定節點時，它會從 `ChannelRegistry` 中透過節點 ID 查找到對應的 `Channel` 物件。
*   **發送**: 拿到 `Channel` 後，`Distributor` 直接呼叫 `channel.writeAndFlush(taskMessage)`，透過已經建立好的長連線高效地發送訊息。

### 4.4 反向通道 (Reverse Channel) 的深入分析

`env.json` 中的 `reverseChannel: true` 和 `REVERSE_DATA_PORT` 參數，揭示了 HiBA 專案在處理複雜網路環境下的通訊策略。

**綜合分析與修正:**

HiBA 專案中的 "Reverse Channel" 旨在解決節點間的網路連通性問題，特別是當「下層節點」無法被「上層節點」直接連接時。它允許「下層節點」主動建立一個出站連線到「上層節點」（或一個中繼伺服器），然後這個連線可以被「反向」用於資料傳輸。

*   **解決方案的必要性**: 在分散式系統中，尤其是在移動或邊緣運算環境下，節點間的直接連通性往往是最大的挑戰。簡單的直接 TCP 連線在面對 NAT、防火牆或動態 IP 時會失效。
*   **實現方式的可能性**: 
    1.  **SSH Reverse Tunnel (反向 SSH 隧道)**: 這是一種可能性。一個「下層節點」作為 SSH 客戶端，主動連接到一個可公開存取的 SSH 伺服器（可能是 Broker 或一個專門的跳板機），並建立一個反向隧道。這個隧道會將 SSH 伺服器上的某個埠（例如 `REVERSE_DATA_PORT`）的流量，轉發到「下層節點」的某個本地埠。這樣，「上層節點」只需連接到 SSH 伺服器上的 `REVERSE_DATA_PORT`，就能間接地與「下層節點」通訊。
    2.  **應用層的反向連線**: 專案也可能在應用層實現類似的反向連線機制。即「下層節點」主動連接到「上層節點」的 `DATA_PORT` 或 `HiBA_PORT`，並維持一個長連線。當「上層節點」需要向「下層節點」發送資料時，它就透過這個已經建立的**出站連線**將資料發送回去。
    3.  **NAT Traversal 技術**: 結合 STUN/TURN/ICE 等技術嘗試建立點對點連線，但這通常更複雜。

`REVERSE_DATA_PORT` 在此情境下，將是這個反向通道的目標埠，無論是 SSH 伺服器上的轉發埠，還是應用層反向連線的監聽埠。

**結論**: 雖然 `env.json` 中沒有直接的 SSH 配置，但 "Reverse Channel" 的存在強烈暗示了專案需要處理複雜的網路連通性問題。其具體實現方式（SSH Tunnel 或應用層反向連線）需要進一步的程式碼分析，但其解決跨網域通訊的目標是明確的。

## 6. 群組組成機制

HiBA 專案的群組組成機制主要集中在 `org.gslab.sdnMobileControl` 套件中，特別是 `MobileMulticastController` 類別，它實現了一個精巧的狀態機來管理節點的發現、Broker 選舉和群組關聯。`MobileNetworkController` 則負責底層的網路連線管理。

### 6.1 `MobileMulticastController` 在移動節點 Wi-Fi 連線時的追加分析

`MobileMulticastController` 確實是專為移動節點設計的，用於在 Wi-Fi 連線環境下管理節點的發現、Broker 選舉和群組關聯。這在車聯網 (V2X) 或其他移動自組網 (MANET) 場景中尤為重要，因為這些環境的網路連線不穩定且拓撲動態變化。

#### 6.1.1 移動節點的網路環境配置 (`HiBAInitial.java`)

`HiBAInitial.java` 負責從 `env.json` 載入所有系統配置，其中包含了大量針對移動網路的設定：

*   **`MobileControllerState`**: 這個布林值（來自 `env.json` 的 `MobileNetwork.state`）是啟用移動網路控制功能的總開關。當它為 `true` 時，系統會啟動 `MobileNetworkController` 和相關的多播機制。
*   **`wifiInterface`**: 指定了用於 Wi-Fi 連線的網路介面名稱（例如 "wlan0"）。`MobileNetworkController` 將會管理這個介面。
*   **`connectionSignalQuality` 和 `disconnectSignalQuality`**: 這些是 Wi-Fi 訊號品質的閾值，`MobileNetworkController` 會根據這些值來決定何時連接或斷開 Wi-Fi 連線，確保節點始終連接到訊號最佳的 RSU。
*   **`startOBU`, `OBURouterInterface`, `OBURouterAddress`, `OBURouterGateway`**: 這些參數明確指出系統支援車載單元 (On-Board Unit, OBU) 的部署。OBU 在此情境下可能作為一個移動路由器，為其下層的設備提供網路連線。這進一步強化了專案在移動/車載環境中的應用。
*   **`multicastInterface`**: 當 `MobileControllerState` 為 `true` 時，`multicastInterface` 會被設定為 `wifiInterface`。這明確表示多播發現和選舉過程是透過 Wi-Fi 介面進行的。
*   **`LOCAL_IP`**: 在移動模式下，`LOCAL_IP` 被初始化為 "None"，這表明節點的 IP 位址是動態獲取的，由 `MobileNetworkController` 負責管理。

#### 6.1.2 `HiBANetwork.java` 中的整合與啟動

`HiBANetwork.java` 是整個網路層的入口點。在其 `start()` 方法中，當 `HiBAInitial.MobileControllerState` 為 `true` 時，會啟動以下關鍵執行緒：

```java
if (HiBAInitial.MobileControllerState) {
    // ... 載入 RSU.json 中的 Wi-Fi 列表 ...
    new Thread(new MobileNetworkController(RSUWiFiList)).start(); // 啟動 Wi-Fi 連線管理
    if (HiBAInitial.startOBU) {
        new Thread(new MobileMulticastReceiver()).start(); // 如果是 OBU，啟動多播接收器
    }
    MULTICAST_INTERFACE = HiBAInitial.wifiInterface; // 多播介面設定為 Wi-Fi 介面
    LOCAL_IP = "None"; // 本地 IP 初始為空，由 MobileNetworkController 動態獲取
}
// ... 接下來啟動群組組成相關的執行緒 ...
new Thread(new MulticastController()).start(); // 啟動多播控制器 (群組組成狀態機)
new Thread(new MulticastReceiver()).start(); // 啟動多播接收器 (接收所有多播訊息)
new Thread(multicastBrokerSelector).start(); // 啟動 Broker 選擇器 (Broker 選舉邏輯)
// ... 其他執行緒，如心跳檢測、檔案傳輸伺服器等 ...
```

這段程式碼清晰地展示了 `MobileNetworkController` 如何作為基礎，確保物理層的 Wi-Fi 連線穩定，然後 `MulticastController` 和 `MulticastReceiver` 才能在其上執行多播發現和 Broker 選舉的邏輯。

#### 6.1.3 移動節點的「反向通道」實現 (SSH Tunnel 確認)

從 `HiBAInitial.java` 和 `HiBANetwork.java` 的程式碼中，我們找到了確鑿的證據，證明 HiBA 專案確實採用了 **SSH 反向隧道** 作為其「反向通道」的實現方式：

*   **`HiBAInitial.getSSHPubKey(String sysName)`**: 這個方法明確地從 `/home/{sysName}/.ssh/id_rsa.pub` 讀取 SSH 公鑰。在 `HiBAInitial.start()` 方法中，如果 `reverseChannel` 為 `true` 且節點不是 `isAccountingServer`，則會呼叫此方法獲取公鑰。這強烈暗示了 SSH 公鑰將用於建立 SSH 連線，以實現反向通道。
*   **`HiBANetwork.java` 中的 `ReverseSSHTunnelManager`**: `HiBANetwork` 類別中宣告了一個 `private final ReverseSSHTunnelManager sshTunnelManager`。在其內部類別 `MulticastBrokerSelector` 中，`createReverseChannel` 方法明確地呼叫了 `sshTunnelManager.createTunnel()`，並在日誌中印出 "Creating SSH tunnel"。它將本地的 `HiBAInitial.DATA_PORT` 映射到遠端節點的指定 `port`。這正是 **SSH 反向隧道 (Reverse SSH Tunnel)** 的典型用法。

**結論**: `MobileMulticastController` 在 HiBA 專案中，是移動節點在 Wi-Fi 環境下實現群組組成的核心邏輯。它依賴於 `MobileNetworkController` 提供的物理連線管理，並透過多播機制進行節點發現和 Broker 選舉。對於移動節點可能面臨的 NAT 穿透和防火牆限制問題，HiBA 專案確實採用了 **SSH 反向隧道** 作為其「反向通道」的實現方式。這使得位於下層網路（例如，移動節點）的設備，即使無法被直接訪問，也能透過主動建立 SSH 連線到上層 Broker，並在上層 Broker 上開啟一個埠轉發流量，從而實現雙向通訊。

### 6.2 `HiBANetwork.java` 裡的 `MulticastBrokerSelector` 分析

`MulticastBrokerSelector` 是 `HiBANetwork` 類別的一個內部類別，它是一個 `Runnable` 執行緒，同時也是一個 `ChannelInboundHandlerAdapter`。它主要負責根據多播發現的結果，協調節點的 Broker 選舉和與上層 Broker 的關聯。

#### 6.2.1 目的與角色

`MulticastBrokerSelector` 的主要職責是：

*   **Broker 選舉**: 在沒有預設 Broker 的情況下，透過節點分數和投票機制，動態選舉出一個 Broker。
*   **層級關聯**: 協助節點（特別是 Broker 節點本身）發現並連接到上層的 Broker 或中央的 Accounting Server。
*   **反向通道建立**: 在需要跨網域通訊時，協調建立 SSH 反向通道。
*   **狀態管理**: 根據 `MulticastController` 的模式，驅動不同的網路行為。

#### 6.2.2 初始化

*   **建構子**: 從 `./device/score.json` 檔案中讀取本地節點的分數 (`score`)。這個分數是節點在 Broker 選舉中的權重依據。初始化 `scoreMap` (儲存所有已知節點的分數) 和 `sameTierNodesCheck` (用於檢查所有同層節點是否都已回覆分數)。
*   **`HiBANetwork` 中的啟動**: 在 `HiBANetwork` 的建構子中被實例化，並在 `HiBANetwork.start()` 方法中作為一個獨立的執行緒啟動 (`new Thread(multicastBrokerSelector).start();`)。

#### 6.2.3 `run()` 方法 - 狀態機驅動器

`run()` 方法是 `MulticastBrokerSelector` 的核心，它在一個無限迴圈中運行，並根據 `MulticastController.getMode()` 的當前狀態來執行不同的邏輯。

*   **前置處理**:
    *   `clearReverseChannel()` 和 `setChannelPubKey("clear")`: 在每次 `init` 模式開始時，會清除所有現有的 SSH 反向通道和相關的公鑰設定。這確保了每次重新發現和選舉時，網路狀態都是乾淨的。
    *   `isFindBroker`: 一個原子布林值，用於標記是否已成功找到並連接到 Broker，一旦找到，則暫停後續的 Broker 選擇邏輯。

*   **狀態處理 (`switch (MulticastController.getMode())`)**:

    *   **`case "init"` (初始化模式)**:
        *   **目的**: 重置狀態，準備進行新的發現和選舉。
        *   **邏輯**: 定期清除 `blackList`。如果從連接狀態斷開或重置，則清除反向通道和公鑰。清空 `scoreMap`。
        *   **與 `MulticastController` 互動**: `MulticastController` 在此模式下會廣播 "init" 訊息。

    *   **`case "connection"` (連線模式)**:
        *   **目的**: 透過 TCP 連線與發現的同層節點交換分數，並確定最高分節點。
        *   **邏輯**:
            *   將 `mulitcastMap` (由 `MulticastReceiver` 透過多播發現的節點) 中的節點加入 `sameTierNodes`。
            *   將本地節點的分數加入 `scoreMap`。
            *   **分數交換**: 遍歷 `sameTierNodes`，透過 `sendMessage()` 方法向每個節點發送 `ScorePublishMessage` (包含本地節點的分數)。
            *   接收 `ScorePublishResponse`，並將其他節點的分數更新到 `scoreMap` 和 `sameTierNodesCheck`。
            *   **逾時檢查**: 如果在一定時間內未收到所有同層節點的分數回覆，則認為逾時，清空所有相關列表，並將 `MulticastController` 模式重置為 `init`，重新開始發現。
            *   **選出最高分**: 呼叫 `compareMap()` 方法，從 `scoreMap` 中選出分數最高的節點作為 `highestScoreNodeName`。
            *   **與 `MulticastController` 互動**: 將計算出的 `scoreMap` 和 `highestScoreNodeName` 設定回 `MulticastController`，以便其進入下一個模式。

    *   **`case "highestScoreBroadcast"` (最高分廣播模式)**:
        *   **目的**: (此處程式碼為空，表示此模式下的主要邏輯由 `MulticastController` 負責發送廣播，而 `MobileMulticastReceiver` 負責接收投票)。

    *   **`case "isBroker"` (Broker 模式)**:
        *   **目的**: 當本地節點被選為 Broker 時，它需要執行以下任務：連接到中央的 Accounting Server，獲取其他 Broker 列表；如果無法連接 Accounting Server，則嘗試掃描並關聯上層 Broker；與上層 Broker 建立 SSH 反向通道。
        *   **邏輯**:
            *   **連接 Accounting Server**: 如果 `accountingServerChannel` 為空，嘗試連接 `accountingServerHost`。成功後，發送 `GetBrokerList` 訊息獲取其他 Broker 列表，並建立心跳通道。
            *   **上層 Broker 掃描 (Offline Broker Scan)**: 如果 `accountingServerChannel` 和 `brokerChannel` 都為空，且 `HiBAInitial.offlineBrokerScan` 為 `true`：
                *   使用 `Traceroute.doTraceroute()` 獲取上層閘道。
                *   使用 `Traceroute.checkUpperNATAddress()` 掃描上層網路中可能存在的 Broker IP。
                *   向這些 IP 發送 `lowerBrokerScansMessage`。
                *   如果收到 `lowerBrokerScansResponse`，表示找到上層 Broker。
                *   **建立 SSH 反向通道**: 呼叫 `createReverseChannel()` 方法，與上層 Broker 建立 SSH 反向通道。這涉及加密交換連線時間和公鑰，並在本地開啟一個埠轉發流量。
            *   **上層 Broker 關聯 (來自 Accounting Server 列表)**: 如果 `acctResponseBrokerList` 不為空：
                *   使用 `Traceroute.doTraceroute()` 獲取上層閘道，並過濾 `acctResponseBrokerList`，找出可能位於同一個上層網路的 Broker。
                *   對這些「可能上層 Broker」重複上述的**建立 SSH 反向通道**和連接邏輯。
            *   **逾時與重試**: 包含多個計數器處理連接 Accounting Server 或上層 Broker 的逾時和重試邏輯。

    *   **`case "isClient"` (客戶端模式)**:
        *   **目的**: 連接到已選出的 Broker。
        *   **邏輯**:
            *   等待 `brokerInformation` 被 `MulticastController` 填充（透過多播接收到 Broker 資訊）。
            *   嘗試使用 `connectToBroker()` 方法連接到 Broker。
            *   如果連接成功，設定 `BROKER_IP` 和 `brokerChannel`，並將 `isFindBroker` 設為 `true`。
            *   如果連接失敗或逾時，則將該 Broker 加入 `blackList`，清空 `brokerInformation`，並將 `MulticastController` 模式重置為 `init`，重新開始發現。

#### 6.2.4 輔助方法

*   **`channelRead(ChannelHandlerContext ctx, Object msg)`**: 作為 Netty Handler，它處理入站訊息。如果收到 `ScorePublishMessage`，則呼叫 `buildScorePublishResponse()` 回覆本地分數。
*   **`buildScorePublishResponse(ScorePublishMessage msg)`**: 將接收到的節點分數更新到 `scoreMap`。回覆一個包含本地節點分數的 `ScorePublishResponse`。
*   **`compareMap(Map<String, Float> map)`**: 簡單地遍歷 `scoreMap`，找出分數最高的節點名稱。
*   **`createReverseChannel(String clientDeviceName, String clientAddress, Integer port)`**: **這是 SSH 反向通道的實現**。呼叫 `sshTunnelManager.createTunnel()` 方法，將本地的 `HiBAInitial.DATA_PORT` 映射到遠端節點的指定 `port`。
*   **`clearReverseChannel()`**: 呼叫 `sshTunnelManager.close()` 關閉所有 SSH 隧道。
*   **`setChannelPubKey(String pubKey)`**: 將 SSH 公鑰寫入或清除 `./auth_ch` 檔案，用於授權 SSH 連線。

#### 6.2.5 與其他組件的互動

*   **`MulticastController`**: `MulticastBrokerSelector` 嚴重依賴 `MulticastController` 的模式來驅動其行為，並會更新 `MulticastController` 的狀態。
*   **`HiBAInitial`**: 讀取 `env.json` 中的配置參數，如 `offlineBrokerScan`、`pubKey`、`DATA_PORT` 等。
*   **`HiBANetwork`**: 使用 `HiBANetwork` 提供的 `sendMessage()` 和 `connectToBroker()` 方法進行 TCP 通訊，並管理 `brokerChannel` 和 `accountingServerChannel`。
*   **`Traceroute`**: 用於發現上層網路的閘道和可能存在的上層 Broker IP，這對於多層級 Broker 結構至關重要。
*   **`AESTools`**: 用於加密和解密連線時間，以確保 SSH 反向通道建立過程中的安全性。
*   **`ReverseSSHTunnelManager`**: 這是 SSH 反向通道的具體實現類別，`MulticastBrokerSelector` 透過它來建立和管理 SSH 隧道。

### 總結

`MulticastBrokerSelector` 是 HiBA 專案中一個非常複雜且關鍵的組件，它不僅實現了基於分數的 Broker 選舉機制，還處理了多層級 Broker 之間的關聯邏輯。特別值得注意的是，它明確地利用 **SSH 反向隧道** 來解決移動節點或下層 Broker 在複雜網路環境中（如 NAT 後方）與上層 Broker 建立連線的挑戰。這使得 HiBA 能夠在動態且受限的網路環境中，建立和維護一個彈性且高效的分散式 Broker 拓撲。

## 7. 如何開發 Micro Service

要在 `hiba-core` 專案中開發一個新的微服務，你需要遵循以下步驟。我們以開發一個名為 `myNewService` 的服務為例。

### 步驟 1: 在 `service.json` 中定義服務

編輯 `hiba-core/service.json` 檔案，在 `mService` 物件中加入你的新服務定義：

```json
{
  "mService": {
    // ... 其他服務定義 ...
    "myNewService": {
      "type": "java",
      "input": ["param1", "param2"],
      "resource": {
        "capability": ["valueA", "valueB"]
      },
      "build": {
        "Distributor": "MyNewServiceDistributor",
        "Executor": "MyNewServiceExecutor",
        "Taker": "MyNewServiceTaker"
      },
      "method": {
        "someConfigPath": "./config/my_new_service.conf",
        "timeoutSeconds": 30
      }
    }
  }
}
```

*   **`input`**: 定義了呼叫此服務需要傳遞的參數。
*   **`resource`**: 定義了能夠執行此服務的節點必須具備的「能力」。Broker 的 `Distributor` 會根據這些資源來選擇 Executor。
*   **`build`**: 這是最關鍵的部分。它將服務的三個角色（Taker, Distributor, Executor）對應到將要建立的 Java 類別名稱。
*   **`method`**: 包含服務特定的配置參數，例如檔案路徑、逾時設定等。

### 步驟 2: 實作 Java 介面

你需要建立三個 Java 類別，它們將實作 `hiba-common` 模組中定義的抽象介面：`com.socomni.abstraction.Taker`、`com.socomni.abstraction.Distributor` 和 `com.socomni.abstraction.Executor`。

將這些新的 Java 檔案放在 `hiba-core/src/main/java/org/gslab/service/mService/` 目錄下。

1.  **`MyNewServiceTaker.java`**
    *   **職責**: 建立服務請求並處理最終結果。
    *   **實作**: 
        *   包含一個方法，接收 `param1` 和 `param2`。
        *   根據這些參數建立一個標準的 HiBA 請求訊息。
        *   將請求發送給 Broker。
        *   包含處理從 Broker 或 Executor 回傳的結果的邏輯。

2.  **`MyNewServiceDistributor.java`**
    *   **職責**: 在 Broker 上執行，負責根據資源和策略選擇最佳的 Executor 節點。
    *   **實作**: 
        *   實作 `distribute()` 方法，接收請求和參數 (`param1`, `param2`)。
        *   **節點篩選**: 從 Broker 維護的節點列表中，篩選出 `resource` 中包含 `capability` 的節點。
        *   **最佳選擇**: 根據 `param1`、`param2` 的值，或節點的負載、網路延遲等指標，從篩選後的列表中選出最佳節點。
        *   返回選定節點的 ID。

3.  **`MyNewServiceExecutor.java`**
    *   **職責**: 在被選中的普通節點上執行實際的業務邏輯。
    *   **實作**: 
        *   實作 `execute()` 方法，接收請求和參數 (`param1`, `param2`)。
        *   在這裡編寫服務的核心程式碼，例如：
            *   讀取 `method.someConfigPath` 指定的設定檔。
            *   根據參數執行計算、資料處理或 I/O 操作。
            *   將執行結果打包並回傳。

### 步驟 3: 建置與執行

1.  **編譯**: 由於這是一個 Maven 專案（存在 `pom.xml`），你需要執行 Maven 建置命令來編譯你的新 Java 類別。在專案根目錄下執行：
    ```bash
    mvn clean install
    ```
2.  **執行**: 建置成功後，新的類別檔案會出現在 `hiba-core/target/classes/org/gslab/service/mService/` 目錄下。當 HiBA 系統啟動時，它會動態讀取 `service.json`，並透過反射機制載入你新定義的 `myNewService` 和相關的類別。你的新服務現在已經是系統的一部分，可以被呼叫了。

---
