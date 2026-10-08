#!/bin/bash

# 找出 hiba.jar 的 PID
PID=$(pgrep -f 'java -jar hiba.jar')

# 如果沒有找到 PID
if [[ -z "$PID" ]]; then
  echo "hiba.jar is not running."
  exit 1
fi

# 使用 ps 命令列出 CPU 使用率
CPU_USAGE=$(ps -p $PID -o %cpu --no-headers)

# 使用 ps 命令列出 RAM 使用率
RAM_USAGE=$(ps -p $PID -o %mem --no-headers)

# 使用 ps 命令列出 RAM 量(以 KiB 為單位)
RAM_KiB=$(ps -p $PID -o rss --no-headers)
RAM_MB=$(echo "scale=2; $RAM_KiB/1024" | bc)

# 打印出 CPU 使用率
echo "CPU_usage:$CPU_USAGE%"

# 打印出 RAM 使用率
echo "RAM_usage:$RAM_USAGE %"

# 打印出 RAM 量
echo "RAM_available:$RAM_MB MB"
