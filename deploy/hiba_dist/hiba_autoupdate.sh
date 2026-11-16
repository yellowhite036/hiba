#!/bin/bash
# HiBA 節點自動更新
#
# 由 setup_node.sh 安裝成 /usr/local/sbin/hiba-autoupdate，hiba-autoupdate.timer 每 2 分鐘以 root 執行一次：
#   1. 向 accounting server 要目前提供的 jar 的 md5（同時回報本機版本，/nodes 網頁會顯示「最新版 / 舊版」）
#   2. 和本機 jar 一樣 → 結束
#   3. 不一樣 → 隨機等 0～60 秒（避免所有節點同時下載、同時重啟）→ 下載 → 比對 md5
#      → 備份舊 jar → 停止 HiBA → 換 jar → 啟動 → 觀察 25 秒
#   4. 新版啟動失敗 → 換回舊 jar 並重新啟動，記下這個 md5，之後不再重試同一版
#      （Pi 換上修正後的新版，md5 不同，就會再自動更新）
#
# HiBA 原本沒在執行（例如手動停掉）時只換 jar，不會啟動它。
# 紀錄：$DIR/logs/autoupdate.log，或 journalctl -u hiba-autoupdate.service
#
# 手動執行（立即檢查，不隨機等待）：
#   sudo hiba-autoupdate --user <使用者> --dir <HiBA 目錄> --no-delay
set -uo pipefail

HIBA_USER=""
DIR=""
MAX_DELAY=60
HEALTH_SEC=25

while [ $# -gt 0 ]; do
    case "$1" in
        --user) HIBA_USER="$2"; shift 2 ;;
        --dir) DIR="$2"; shift 2 ;;
        --no-delay) MAX_DELAY=0; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "不認得的選項: $1"; exit 2 ;;
    esac
done

[ -n "$HIBA_USER" ] && [ -n "$DIR" ] || { echo "需要 --user 和 --dir"; exit 2; }
[ "$(id -u)" = 0 ] || { echo "請用 root 執行（sudo）"; exit 2; }
[ -f "$DIR/env.json" ] || { echo "找不到 $DIR/env.json，略過"; exit 0; }

JAR="hiba-core-0.0.1.jar"
UNIT="hiba.service"
RUN_UNIT="hiba-autoupdate-run" # 沒有 hiba.service 時，用 systemd-run 建立的暫時服務來啟動 HiBA
PATTERN="java.*${JAR}"
USER_UID="$(id -u "$HIBA_USER")" || exit 2
USER_GID="$(id -g "$HIBA_USER")"
CACHE="$DIR/.jar.md5"      # 「大小 修改時間 md5」，避免每次都重算整個 jar 的 md5
BAD="$DIR/.autoupdate-bad" # 啟動失敗過的版本
BACKUP_DIR="$DIR/.backup-autoupdate"
LOGFILE="$DIR/logs/autoupdate.log"

mkdir -p "$DIR/logs"
log() {
    local line
    line="$(date '+%Y-%m-%d %H:%M:%S') $*"
    echo "$*"
    echo "$line" >> "$LOGFILE"
    chown "$USER_UID:$USER_GID" "$LOGFILE" 2>/dev/null
}

own() { chown -R "$USER_UID:$USER_GID" "$@" 2>/dev/null; }

env_value() {
    python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['HiBANetwork'].get(sys.argv[2], ''))" "$DIR/env.json" "$1" 2>/dev/null
}

hiba_pids() { pgrep -u "$USER_UID" -f "$PATTERN" || true; }
is_running() { [ -n "$(hiba_pids)" ]; }
use_systemd() { [ -f "/etc/systemd/system/$UNIT" ]; }

local_md5() {
    local f="$DIR/$JAR" key size mtime hash
    [ -f "$f" ] || { echo ""; return; }
    key="$(stat -c '%s %Y' "$f")"
    if read -r size mtime hash < "$CACHE" 2>/dev/null && [ "$size $mtime" = "$key" ] && [ -n "$hash" ]; then
        echo "$hash"
        return
    fi
    hash="$(md5sum "$f" | cut -d' ' -f1)"
    echo "$key $hash" > "$CACHE"
    own "$CACHE"
    echo "$hash"
}

find_java() {
    if [ -x /usr/jvm/jdk-21.0.2/bin/java ]; then
        echo /usr/jvm/jdk-21.0.2/bin/java
    else
        runuser -u "$HIBA_USER" -- bash -lc 'command -v java' 2>/dev/null
    fi
}

stop_hiba() {
    if use_systemd && systemctl is-active --quiet "$UNIT"; then
        systemctl stop "$UNIT"
    fi
    systemctl stop "$RUN_UNIT" 2>/dev/null
    local pids
    pids="$(hiba_pids)"
    [ -z "$pids" ] && return
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null
    for _ in $(seq 30); do
        is_running || return
        sleep 1
    done
    pkill -9 -u "$USER_UID" -f "$PATTERN"
    sleep 1
}

restarts() { systemctl show -p NRestarts --value "$UNIT" 2>/dev/null || echo 0; }

# 啟動 HiBA，觀察 HEALTH_SEC 秒；期間程式結束或被 systemd 重啟過就算失敗
start_and_check() {
    local before
    if use_systemd; then
        systemctl reset-failed "$UNIT" 2>/dev/null
        systemctl start "$UNIT" || return 1
        before="$(restarts)"
    else
        local java_bin
        java_bin="$(find_java)"
        [ -n "$java_bin" ] || { log "找不到 java，無法啟動 HiBA"; return 1; }
        systemctl reset-failed "$RUN_UNIT" 2>/dev/null
        systemd-run --quiet --collect --unit="$RUN_UNIT" \
            --uid="$USER_UID" --gid="$USER_GID" \
            -p WorkingDirectory="$DIR" \
            -p StandardOutput=append:"$DIR/logs/startup.log" -p StandardError=inherit \
            "$java_bin" -DLOG_LEVEL=INFO -DTPM_LOG_LEVEL=WARN -jar "$JAR" || return 1
    fi
    for _ in $(seq "$HEALTH_SEC"); do
        sleep 1
        is_running || return 1
    done
    if use_systemd && [ "$(restarts)" != "$before" ]; then
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
ACCT="$(env_value accountingServer)"
NAME="$(env_value nodeName)"
[ -n "$ACCT" ] || { echo "env.json 沒有 accountingServer，略過"; exit 0; }

HAVE="$(local_md5)"
RUNNING=0
is_running && RUNNING=1

# 問 accounting server 目前的版本（同時回報本機版本）；連不到或回傳格式不對就下次再試
REMOTE="$(python3 - "$ACCT" "$NAME" "$HAVE" "$RUNNING" <<'EOF'
import sys, urllib.parse, urllib.request
acct, name, have, running = sys.argv[1:5]
q = urllib.parse.urlencode({"node": name, "have": have, "running": running})
try:
    print(urllib.request.urlopen(f"http://{acct}:8092/dist/hiba-core-0.0.1.jar.md5?{q}", timeout=10).read().decode().strip())
except Exception:
    pass
EOF
)"
if ! [[ "$REMOTE" =~ ^[0-9a-f]{32}$ ]]; then
    echo "accounting server $ACCT 沒有提供版本資訊（連不到，或還是舊版程式），略過"
    exit 0
fi
if [ "$REMOTE" = "$HAVE" ]; then
    echo "已是最新版（$HAVE）"
    exit 0
fi
if [ "$(cat "$BAD" 2>/dev/null)" = "$REMOTE" ]; then
    echo "版本 $REMOTE 之前啟動失敗過，不再重試（Pi 換上新版、或手動執行 setup_node.sh 後會恢復）"
    exit 0
fi

log "發現新版：本機 ${HAVE:-（沒有 jar）} → accounting server $REMOTE"
if [ "$MAX_DELAY" -gt 0 ]; then
    WAIT=$((RANDOM % (MAX_DELAY + 1)))
    echo "隨機等待 ${WAIT} 秒再下載"
    sleep "$WAIT"
fi

TMP="$DIR/$JAR.download"
rm -f "$TMP"
if ! python3 - "$ACCT" "$TMP" <<'EOF'
import shutil, sys, urllib.request
acct, out = sys.argv[1:3]
with urllib.request.urlopen(f"http://{acct}:8092/dist/hiba-core-0.0.1.jar", timeout=30) as r, open(out, "wb") as f:
    shutil.copyfileobj(r, f, 1024 * 1024)
EOF
then
    rm -f "$TMP"
    log "下載失敗，下次再試"
    exit 1
fi
GOT="$(md5sum "$TMP" | cut -d' ' -f1)"
if [ "$GOT" != "$REMOTE" ]; then
    rm -f "$TMP"
    log "下載的 jar md5 不符（$GOT ≠ $REMOTE），可能是 Pi 剛好在換檔，下次再試"
    exit 1
fi
own "$TMP"

mkdir -p "$BACKUP_DIR"
[ -f "$DIR/$JAR" ] && cp -p "$DIR/$JAR" "$BACKUP_DIR/$JAR"
own "$BACKUP_DIR"

stop_hiba
mv -f "$TMP" "$DIR/$JAR"
local_md5 >/dev/null

if [ "$RUNNING" = 0 ]; then
    log "已換成 $REMOTE（HiBA 原本沒在執行，不啟動）"
    exit 0
fi

if start_and_check; then
    rm -f "$BAD"
    log "已更新並啟動 $REMOTE"
    exit 0
fi

# 新版起不來：換回舊版
log "新版 $REMOTE 啟動失敗，startup.log 最後幾行："
tail -n 15 "$DIR/logs/startup.log" 2>/dev/null | sed 's/^/    /' | tee -a "$LOGFILE"
echo "$REMOTE" > "$BAD"
own "$BAD"
stop_hiba
if [ -f "$BACKUP_DIR/$JAR" ]; then
    cp -p "$BACKUP_DIR/$JAR" "$DIR/$JAR"
    local_md5 >/dev/null
    if start_and_check; then
        log "已換回舊版 $HAVE 並重新啟動"
    else
        log "換回舊版 $HAVE 後仍無法啟動，請手動檢查（bash setup_node.sh --status）"
    fi
else
    log "沒有舊版可以換回，請手動檢查"
fi
exit 1
