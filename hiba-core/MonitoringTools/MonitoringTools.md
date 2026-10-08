# HiBA v3: MonitoringTools

用於論文實驗中，進行CPU/RAM使用率輸出與網路流量監控用。

## start.py (啟動用主程式)
* Input: None
* Output: None
* Method: Command下python指令直接執行
* 用途: 啟動HardwareUsage.py 與 NetworkMonitor.py

## HardwareUsage.py (CPU/RAM狀態輸出)
* Input: None
* Output: csv file (Device_%Y-%m-%d_%H-%M-%S.csv)
* Method: 執行start.py 或 Command下python指令直接執行
* 用途: 每秒輸出CPU與RAM使用狀態並寫入csv

## NetworkMonitor.py (網路傳輸流量輸出)
* Input: None
* Output: csv file (Network_%Y-%m-%d_%H-%M-%S.csv)
* Method: 執行start.py 或 Command下python指令直接執行
* 用途: 每秒輸出網路流入(Received(Mbps))與流出(Sent(Mbps))使用量並寫入csv