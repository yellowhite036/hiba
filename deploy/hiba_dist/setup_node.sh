#!/bin/bash
# HiBA 虛擬機節點一鍵設定
#
# 在「已經有 HiBA 環境」的節點（~/HiBA 內有 env.json、TPM 與 JDK 已就緒）執行。
# 一次完成：檢查環境 → 停止舊程式 → 備份 → 換新 jar → 改 env.json → 啟動 → 等待選舉 → 查 accounting server 節點表
#           → 安裝自動更新（hiba-autoupdate.timer：每 2 分鐘檢查 accounting server 有沒有新版 jar，有就自動換上）
#
# 一行執行（只輸入一次 Pi 密碼）：
#   scp -r user@192.168.1.62:/home/user/hiba_dist ~/ && bash ~/hiba_dist/setup_node.sh
#
# 選項：
#   --name NAME        順便修改 nodeName（每台要不同）
#   --acct IP          accounting server IP（預設 192.168.1.62）
#   --mcast IP:PORT    multicast 位址（預設 230.0.0.1:4322）
#   --dir PATH         HiBA 目錄（預設 ~/HiBA）
#   --jar PATH         新 jar 位置（預設：與本腳本同目錄的 hiba-core-0.0.1.jar；沒有就不換 jar）
#   --no-autoupdate    不要自動更新（已安裝的會移除）
#   --rollback         還原最近一次備份並重新啟動（同時暫停自動更新，否則 2 分鐘內又會被換回新版）
#   --status           只看目前狀態，不做任何修改
set -uo pipefail

ACCT="192.168.1.62"
MCAST_IP="230.0.0.1"
MCAST_PORT="4322"
NAME=""
DIR="$HOME/HiBA"
JAR_SRC=""
MODE="setup"
WAIT_SEC=60
AUTOUPDATE=1

JAR="hiba-core-0.0.1.jar"
PATTERN="java.*${JAR}"
MY_UID="$(id -u)"

while [ $# -gt 0 ]; do
    case "$1" in
        --name) NAME="$2"; shift 2 ;;
        --acct) ACCT="$2"; shift 2 ;;
        --mcast) MCAST_IP="${2%%:*}"; MCAST_PORT="${2##*:}"; shift 2 ;;
        --dir) DIR="$2"; shift 2 ;;
        --jar) JAR_SRC="$2"; shift 2 ;;
        --no-autoupdate) AUTOUPDATE=0; shift ;;
        --rollback) MODE="rollback"; shift ;;
        --status) MODE="status"; shift ;;
        -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
        *) echo "不認得的選項: $1（用 --help 看說明）"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "$JAR_SRC" ] && [ -f "$SCRIPT_DIR/$JAR" ]; then
    JAR_SRC="$SCRIPT_DIR/$JAR"
fi

WARNS=0
ok()   { echo "  ✔ $*"; }
warn() { echo "  ⚠ $*"; WARNS=$((WARNS + 1)); }
fail() { echo "  ✘ $*"; echo; echo "已停止，沒有做任何後續修改。"; exit 1; }
step() { echo; echo "== $* =="; }

hiba_pids() { pgrep -u "$MY_UID" -f "$PATTERN" || true; }

# 有 /etc/systemd/system/hiba.service（install_node.sh 選了「開機自動啟動」）就交給 systemd 啟停
UNIT="hiba.service"
use_systemd() { [ -f "/etc/systemd/system/$UNIT" ]; }

stop_hiba() {
    local pids
    if use_systemd && systemctl is-active --quiet "$UNIT"; then
        sudo systemctl stop "$UNIT" && ok "已停止 systemd 服務 $UNIT"
    fi
    pids="$(hiba_pids)"
    if [ -z "$pids" ]; then
        ok "原本沒有 HiBA 在執行"
    else
        # shellcheck disable=SC2086
        kill $pids 2>/dev/null
        for _ in $(seq 30); do
            [ -z "$(hiba_pids)" ] && break
            sleep 1
        done
        if [ -n "$(hiba_pids)" ]; then
            pkill -9 -u "$MY_UID" -f "$PATTERN"
            sleep 1
            ok "已強制停止舊的 HiBA（PID $pids）"
        else
            ok "已停止舊的 HiBA（PID $pids）"
        fi
    fi
    rm -f "$DIR/hiba.pid"
}

# 自動更新（hiba_autoupdate.sh 安裝成 /usr/local/sbin/hiba-autoupdate，由 timer 以 root 定期執行）
AU="hiba-autoupdate"
AU_BIN="/usr/local/sbin/$AU"
AU_PAUSED=0
has_autoupdate() { [ -f "/etc/systemd/system/$AU.timer" ]; }

# 設定期間先暫停，避免自動更新同時在換 jar、重啟 HiBA；腳本結束時（包含中途失敗）若仍啟用就恢復
pause_autoupdate() {
    if has_autoupdate; then
        sudo systemctl stop "$AU.timer" "$AU.service" 2>/dev/null
        AU_PAUSED=1
    fi
}
resume_autoupdate() {
    if [ "$AU_PAUSED" = 1 ] && systemctl is-enabled --quiet "$AU.timer" 2>/dev/null; then
        sudo systemctl start "$AU.timer" 2>/dev/null
    fi
}
trap resume_autoupdate EXIT

install_autoupdate() {
    if [ "$AUTOUPDATE" = 0 ]; then
        if has_autoupdate; then
            sudo systemctl disable --now "$AU.timer" >/dev/null 2>&1
            sudo rm -f "/etc/systemd/system/$AU.timer" "/etc/systemd/system/$AU.service" "$AU_BIN"
            sudo systemctl daemon-reload
            ok "已移除自動更新"
        else
            ok "不安裝自動更新（--no-autoupdate）"
        fi
        AU_PAUSED=0
        return
    fi
    local src="$SCRIPT_DIR/hiba_autoupdate.sh"
    if [ ! -f "$src" ]; then
        warn "找不到 $src，略過自動更新（請從 Pi 重新抓整個 hiba_dist）"
        return
    fi
    sudo install -m 755 -o root -g root "$src" "$AU_BIN" || { warn "無法安裝 $AU_BIN"; return; }
    sudo tee "/etc/systemd/system/$AU.service" >/dev/null <<UNIT
[Unit]
Description=HiBA node auto update check
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
ExecStart=$AU_BIN --user $(whoami) --dir $DIR
UNIT
    sudo tee "/etc/systemd/system/$AU.timer" >/dev/null <<UNIT
[Unit]
Description=Check for a new HiBA jar every 2 minutes

[Timer]
OnActiveSec=2min
OnBootSec=2min
OnUnitActiveSec=2min
RandomizedDelaySec=20

[Install]
WantedBy=timers.target
UNIT
    rm -f "$DIR/.autoupdate-bad" # 手動設定過，之前失敗的版本可以再試
    sudo systemctl daemon-reload
    if sudo systemctl enable --now "$AU.timer" >/dev/null 2>&1; then
        ok "已啟用自動更新：每 2 分鐘向 $ACCT 檢查新版（紀錄：$DIR/logs/autoupdate.log）"
    else
        warn "啟用 $AU.timer 失敗（sudo systemctl status $AU.timer 查看）"
    fi
    AU_PAUSED=0
}

find_java() {
    if [ -x /usr/jvm/jdk-21.0.2/bin/java ]; then
        echo /usr/jvm/jdk-21.0.2/bin/java
    elif command -v java >/dev/null; then
        command -v java
    fi
}

start_hiba() {
    local java_bin="$1"
    mkdir -p "$DIR/logs"
    if use_systemd; then
        : > "$DIR/logs/startup.log"
        sudo systemctl reset-failed "$UNIT" 2>/dev/null
        if ! sudo systemctl start "$UNIT"; then
            echo "  ✘ systemctl start $UNIT 失敗："
            sudo journalctl -u "$UNIT" -n 15 --no-pager | sed 's/^/      /'
            exit 1
        fi
    else
        (cd "$DIR" && setsid nohup "$java_bin" -DLOG_LEVEL=INFO -DTPM_LOG_LEVEL=WARN -jar "$JAR" \
            > logs/startup.log 2>&1 < /dev/null &)
    fi
    sleep 3
    local pid
    pid="$(hiba_pids | head -1)"
    if [ -z "$pid" ]; then
        echo "  ✘ HiBA 啟動後立刻結束，startup.log 最後幾行："
        tail -n 20 "$DIR/logs/startup.log" | sed 's/^/      /'
        exit 1
    fi
    echo "$pid" > "$DIR/hiba.pid"
    if use_systemd; then
        ok "HiBA 已啟動（systemd 服務 $UNIT，PID $pid，開機自動啟動：$(systemctl is-enabled "$UNIT" 2>/dev/null)）"
    else
        ok "HiBA 已啟動（PID $pid，未設定開機自動啟動）"
    fi
    echo "      log：$DIR/logs/startup.log"
}

# 讀 accounting server 的節點表，印出摘要與本機節點的狀態；回傳 0 表示找到本機節點
show_api_status() {
    local name="$1"
    python3 - "$ACCT" "$name" <<'EOF'
import json, sys, urllib.request
acct, name = sys.argv[1], sys.argv[2]
try:
    d = json.load(urllib.request.urlopen(f"http://{acct}:8092/api/nodes", timeout=5))
except Exception as e:
    print(f"  ⚠ 讀不到 http://{acct}:8092/api/nodes：{e}")
    sys.exit(2)
s = d.get("summary", {})
print(f"  節點表：共 {s.get('total')} 台，上線 {s.get('online')}，離線 {s.get('offline')}，已註冊未上線 {s.get('registeredNeverSeen')}")
found = 1
for n in d.get("nodes", []):
    mark = "  ← 本機" if n.get("name") == name else ""
    if mark:
        found = 0
    print(f"    {n.get('name'):<20} {n.get('ip') or '-':<16} {n.get('role'):<7} {n.get('status'):<8} 上層: {n.get('broker') or '(根)'}{mark}")
sys.exit(found)
EOF
}

env_value() {
    python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['HiBANetwork'].get(sys.argv[2], ''))" "$DIR/env.json" "$1"
}

# ---------------------------------------------------------------------------
[ -f "$DIR/env.json" ] || { echo "找不到 $DIR/env.json。這個腳本用於已經有 HiBA 環境的節點（可用 --dir 指定目錄）。"; exit 1; }
command -v python3 >/dev/null || { echo "需要 python3"; exit 1; }

if [ "$MODE" = "status" ]; then
    step "目前狀態"
    pid="$(hiba_pids | head -1)"
    if [ -n "$pid" ]; then ok "HiBA 執行中（PID $pid）"; else warn "HiBA 沒有在執行"; fi
    if use_systemd; then
        echo "  開機自動啟動：$(systemctl is-enabled "$UNIT" 2>/dev/null)（systemd 服務 $UNIT，狀態 $(systemctl is-active "$UNIT" 2>/dev/null)）"
    else
        echo "  開機自動啟動：未設定（要設定請重跑 install_node.sh --autostart）"
    fi
    echo "  nodeName=$(env_value nodeName) accountingServer=$(env_value accountingServer) multicast=$(env_value multicastIP):$(env_value multicastPort)"
    ACCT="$(env_value accountingServer)"
    grep -E "local is Broker|Find Broker|Received broker list|Start Initializing" "$DIR/logs/startup.log" 2>/dev/null | tail -n 3 | sed 's/^/  /'
    show_api_status "$(env_value nodeName)"
    if has_autoupdate; then
        echo "  自動更新：$(systemctl is-enabled "$AU.timer" 2>/dev/null)，下次檢查 $(systemctl show -p NextElapseUSecRealtime --value "$AU.timer" 2>/dev/null)"
        tail -n 3 "$DIR/logs/autoupdate.log" 2>/dev/null | sed 's/^/    /'
    else
        echo "  自動更新：未安裝（重跑 setup_node.sh 會安裝）"
    fi
    exit 0
fi

if [ "$MODE" = "rollback" ]; then
    step "還原最近一次備份"
    BK="$(ls -d "$DIR"/.backup-setup-* 2>/dev/null | sort | tail -1)"
    [ -n "$BK" ] || fail "找不到 $DIR/.backup-setup-* 備份"
    JAVA_BIN="$(find_java)"
    [ -n "$JAVA_BIN" ] || fail "找不到 java"
    if has_autoupdate; then
        sudo systemctl disable --now "$AU.timer" >/dev/null 2>&1
        ok "已暫停自動更新（恢復：重跑 bash $0）"
    fi
    stop_hiba
    cp -p "$BK/$JAR" "$DIR/$JAR" && cp -p "$BK/env.json" "$DIR/env.json"
    ok "已從 $BK 還原 jar 與 env.json"
    start_hiba "$JAVA_BIN"
    exit 0
fi

# ---------------------------------------------------------------------------
step "1. 檢查環境"
echo "  使用者: $(whoami)   HiBA 目錄: $DIR"

JAVA_BIN="$(find_java)"
[ -n "$JAVA_BIN" ] || fail "找不到 java（預期在 /usr/jvm/jdk-21.0.2/bin/java）"
if "$JAVA_BIN" -version 2>&1 | head -1 | grep -q '"21'; then
    ok "Java 21：$JAVA_BIN"
else
    warn "Java 不是 21 版：$("$JAVA_BIN" -version 2>&1 | head -1)"
fi

# HiBA 只在 reverseChannel=true 時讀 /home/<使用者>/.ssh/id_rsa.pub，讀不到就直接結束
PUBKEY="/home/$(whoami)/.ssh/id_rsa.pub"
if [ "$(env_value reverseChannel)" = "True" ]; then
    [ -f "$PUBKEY" ] || fail "reverseChannel=true 但缺少 $PUBKEY（HiBA 會在啟動時結束），請先執行：ssh-keygen -t rsa -N '' -f ~/.ssh/id_rsa"
    ok "reverseChannel=true，$PUBKEY 存在"
else
    ok "reverseChannel=false，不需要 id_rsa.pub"
fi

if [ -e /dev/tpmrm0 ] || [ -e /dev/tpm0 ]; then
    if [ -r /dev/tpmrm0 ] && [ -w /dev/tpmrm0 ]; then ok "TPM：/dev/tpmrm0 可讀寫"
    else warn "TPM 裝置存在，但 $(whoami) 可能沒有讀寫權限（ls -l /dev/tpmrm0 查看；通常要加入 tss 群組）"; fi
else
    warn "找不到 /dev/tpmrm0 或 /dev/tpm0，TPM 簽章會失敗"
fi

LOCAL_IP="$(ip route get "$ACCT" 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1)"
echo "  本機 IP（往 accounting server 的路徑）: ${LOCAL_IP:-未知}"

if timeout 3 bash -c "echo > /dev/tcp/$ACCT/8091" 2>/dev/null; then
    ok "連得到 accounting server $ACCT:8091"
else
    fail "連不到 accounting server $ACCT:8091（虛擬機網卡是否設成 NAT？Pi 上的 HiBA 是否在執行？）"
fi

# TPM 簽章驗證：訊息時間比對方快 30 秒以上、或慢 5 分鐘以上就會被拒絕並斷線
SKEW="$(python3 - "$ACCT" <<'EOF'
import json, sys, time, urllib.request
try:
    t0 = time.time()
    d = json.load(urllib.request.urlopen(f"http://{sys.argv[1]}:8092/api/nodes", timeout=5))
    t1 = time.time()
    print(round((t0 + t1) / 2 - d["generatedAt"] / 1000))
except Exception:
    print("")
EOF
)"
if [ -z "$SKEW" ]; then
    warn "無法和 accounting server 比對時間"
elif [ "${SKEW#-}" -gt 20 ]; then
    fail "本機時間和 accounting server 差 ${SKEW} 秒（正數＝本機較快），TPM 驗證會失敗。請先對時：sudo timedatectl set-ntp true（等 1 分鐘後用 timedatectl 確認 synchronized: yes）"
else
    ok "時間與 accounting server 相差 ${SKEW} 秒"
fi

RPC="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('Blockchain',{}).get('rpcURL',''))" "$DIR/env.json")"
RPC_HOSTPORT="${RPC#*://}"; RPC_HOSTPORT="${RPC_HOSTPORT%%/*}"
if [ -n "$RPC_HOSTPORT" ] && timeout 3 bash -c "echo > /dev/tcp/${RPC_HOSTPORT%%:*}/${RPC_HOSTPORT##*:}" 2>/dev/null; then
    ok "連得到區塊鏈 $RPC"
else
    warn "連不到區塊鏈 ${RPC:-（env.json 沒有 rpcURL）}，TPM 驗證可能失敗"
fi

if [ -n "$JAR_SRC" ]; then
    [ -f "$JAR_SRC" ] || fail "找不到新 jar：$JAR_SRC"
    NEW_MD5="$(md5sum "$JAR_SRC" | cut -d' ' -f1)"
    ok "新 jar：$JAR_SRC（md5 $NEW_MD5）"
else
    warn "沒有提供新 jar，保留目前的 jar"
fi

FINAL_NAME="${NAME:-$(env_value nodeName)}"
[ -n "$FINAL_NAME" ] || fail "env.json 沒有 nodeName，請用 --name 指定"
DUP="$(python3 - "$ACCT" "$FINAL_NAME" "$LOCAL_IP" <<'EOF'
import json, sys, urllib.request
acct, name, ip = sys.argv[1:4]
try:
    d = json.load(urllib.request.urlopen(f"http://{acct}:8092/api/nodes", timeout=5))
except Exception:
    sys.exit(0)
for n in d.get("nodes", []):
    if n.get("name") == name and n.get("status") == "online" and n.get("ip") != ip:
        print(n.get("ip"))
EOF
)"
if [ -n "$DUP" ]; then
    fail "nodeName「$FINAL_NAME」已經被另一台在線的節點（$DUP）使用，請用 --name 換一個名字"
fi
ok "nodeName：$FINAL_NAME"

# ---------------------------------------------------------------------------
step "2. 停止舊的 HiBA 並備份"
pause_autoupdate
stop_hiba
BK="$DIR/.backup-setup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BK"
[ -f "$DIR/$JAR" ] && cp -p "$DIR/$JAR" "$BK/"
cp -p "$DIR/env.json" "$BK/"
ok "備份在 $BK（還原：bash $0 --rollback）"

# ---------------------------------------------------------------------------
step "3. 更新 jar"
if [ -n "$JAR_SRC" ]; then
    OLD_MD5="$( [ -f "$DIR/$JAR" ] && md5sum "$DIR/$JAR" | cut -d' ' -f1 )"
    if [ "$OLD_MD5" = "$NEW_MD5" ]; then
        ok "jar 已是新版，不用更換"
    else
        cp "$JAR_SRC" "$DIR/$JAR.tmp" && mv -f "$DIR/$JAR.tmp" "$DIR/$JAR"
        ok "已換成新 jar（md5 $NEW_MD5）"
    fi
else
    ok "略過"
fi

# ---------------------------------------------------------------------------
step "4. 修改 env.json"
python3 - "$DIR/env.json" "$ACCT" "$MCAST_IP" "$MCAST_PORT" "$NAME" <<'EOF'
import json, sys
p, acct, mip, mport, name = sys.argv[1:6]
d = json.load(open(p))
n = d["HiBANetwork"]
n.update({"isAccountingServer": False, "accountingServer": acct,
          "multicastIP": mip, "multicastPort": int(mport)})
if name:
    n["nodeName"] = name
json.dump(d, open(p, "w"), indent=4, ensure_ascii=False)
keys = ["nodeName", "accountingServer", "multicastIP", "multicastPort", "multicastInterface", "interfaceAddress", "mandatoryBroker"]
print("  " + ", ".join(f"{k}={n.get(k)!r}" for k in keys))
if n.get("interfaceAddress"):
    print("  ⚠ interfaceAddress 有寫死 IP，若不是本機目前的 IP，HiBA 會綁不到網卡")
EOF

# ---------------------------------------------------------------------------
step "5. 啟動 HiBA"
LOG="$DIR/logs/startup.log"
start_hiba "$JAVA_BIN"

# ---------------------------------------------------------------------------
step "6. 等待 broker 選舉（最多 ${WAIT_SEC} 秒）"
RESULT=""
for i in $(seq "$WAIT_SEC"); do
    sleep 1
    if [ -z "$(hiba_pids)" ]; then
        echo "  ✘ HiBA 在執行中途結束，startup.log 最後幾行："
        tail -n 20 "$LOG" | sed 's/^/      /'
        exit 1
    fi
    if grep -q "Received broker list" "$LOG"; then RESULT="broker"; break; fi
    if grep -q "Find Broker" "$LOG"; then RESULT="client"; break; fi
    if [ $((i % 10)) -eq 0 ]; then echo "  …已等 ${i} 秒"; fi
done

case "$RESULT" in
    broker)
        ok "本機當選 broker，已連上 accounting server"
        ;;
    client)
        ok "找到 broker：$(grep "Find Broker" "$LOG" | tail -1 | sed 's/.*Find Broker : //')"
        ;;
    *)
        if grep -q "Can't connect to Accounting Server" "$LOG"; then
            warn "當選 broker，但連不上 accounting server $ACCT"
        elif grep -q "Start Initializing" "$LOG"; then
            warn "還停在 init：同一個 NAT 網段裡沒有收到其他節點的廣播。HiBA 至少要 2 台節點才會選舉，請再啟動另一台。"
        else
            warn "${WAIT_SEC} 秒內沒有看到選舉結果"
        fi
        echo "  startup.log 最後幾行："
        tail -n 8 "$LOG" | sed 's/^/      /'
        ;;
esac

# ---------------------------------------------------------------------------
step "7. Accounting server 節點表"
if [ -n "$RESULT" ]; then
    FOUND=0
    for _ in $(seq 6); do
        if show_api_status "$FINAL_NAME" >/tmp/hiba_setup_status.$$ 2>&1; then FOUND=1; break; fi
        sleep 5   # broker 每 5 秒回報一次
    done
    cat /tmp/hiba_setup_status.$$; rm -f /tmp/hiba_setup_status.$$
    if [ "$FOUND" = 0 ]; then
        warn "30 秒後節點表裡仍沒有 $FINAL_NAME。最常見的原因：這台的 TPM EK 沒有註冊到鏈上，broker 驗證簽章失敗就把連線關掉"
        echo "  本機 EK 與最近的連線紀錄："
        grep -iE "EK fingerprint|fingerprint|verif|join|connect.*broker|Lost|refused|exception" "$LOG" | tail -n 8 | sed 's/^/      /'
    fi
else
    show_api_status "$FINAL_NAME"
fi

# ---------------------------------------------------------------------------
step "8. 自動更新"
install_autoupdate

echo
echo "網頁：http://$ACCT:8092/nodes"
if [ "$WARNS" -gt 0 ]; then
    echo "完成，但有 $WARNS 個警告（⚠），請看上面的訊息。"
else
    echo "完成。"
fi
echo "之後查狀態：bash $0 --status    還原：bash $0 --rollback"
