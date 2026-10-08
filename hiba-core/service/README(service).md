# HiBA v3 論文中，SDK Microservice使用到的外部程式

## 自動化腳本(autoScriptThesisExpe):

| SDK Function | File name            | Call python     |
| --------     | --------             | --------            |
| non-SDK      | autoScriptThesisExpe | sampleClassifier.py |

* 外部呼叫程式:
    * sampleClassifier.py:
        * Input: npz檔目錄(receiveTargetPath)與getModel所使用的tree.json檔
        * Output: 狀態(True)
        * Method: 腳本內部依流程觸發，若要手動測試，也可直接按Input輸入
        * 用途: 將收到npz檔的目錄(receiveTargetPath)依照tree.json以資料夾來分類樹狀類別

## 樣本、模型尋找(getModel):

| SDK Function | File name           | Call python       |
| --------     | --------            | --------          |
| distributor  | getModelDistributor |                   |
| executor     | getModelExecutor    | getDescriptors.py |
| taker        | resourceCheckTaker  |                   |

* 外部呼叫程式:
    * getDescriptors.py:
        * Input:  樣本目錄絕對路徑
        * Output: 輸出HoG壓縮檔(.npz)絕對路徑
        * Method: 直接呼叫python使用
        * 用途: 獲取local端中，Task所需樣本編號的所有樣本，並壓縮成npz檔

## 模型分散式訓練服務(trainDeepModel):

| SDK Function | File name                | Call python     |
| --------     | --------                  | --------       |
| distributor  | trainDeepModelDistributor |                |
| executor     | trainDeepModelExecutor    |                |
| taker        | trainDeepModelTaker       | trainingSVM.py |

* 外部呼叫程式:
    * trainingSVM.py:
        * Input: npz檔路徑(sample_path), tree.json路徑(tree_path), 輸出訓練完畢模型路徑(out_model_path), 輸出訓練完畢模型檔名(out_model_name)
        * Output: print出訓練資訊
        * Method: 呼叫python使用
        * 用途: 輸入npz檔跟tree.json來訓練模型，注意，只能訓練單個模型