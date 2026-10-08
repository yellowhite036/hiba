#!/bin/bash

# 設定 java 程式的路徑
JAVA_FILE_PATH="hiba.jar"

# 搜尋正在執行的 java 程式的 PID
PIDS=$(ps -ef | grep "java -jar $JAVA_FILE_PATH" | grep -v grep | awk '{print $2}')

# 如果找到相對應的 PID，則對其進行 kill -9 操作
if [ ! -z "$PIDS" ]; then
    echo "Killing running instance of $JAVA_FILE_PATH..."
    kill -9 $PIDS
fi

# 重新啟動 java 程式
echo "Starting $JAVA_FILE_PATH..."
java -jar $JAVA_FILE_PATH &
echo "Java program started."

# 結束 bash 檔
exit 0
