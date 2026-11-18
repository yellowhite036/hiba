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
# 每次執行前也會做節點維護（見 maintain）：
#   - jar 裡附帶的新版 hiba_autoupdate.sh 和這支不同 → 換成新版（下次執行生效）
#   - hiba.service 還是 Restart=on-failure → 改成 Restart=always
#   - 軟體 TPM（swtpm-hiba）：調寬字典攻擊保護；被鎖定時自動解除
# 另外會同步 accounting server 的檔案合約位址（換新合約時自動改 env.json 並重啟 HiBA）。
# 所以節點只要 jar 是最新版，這些設定也會跟著更新，不用再手動執行 install_node.sh / setup_node.sh。
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

SELF="/usr/local/sbin/hiba-autoupdate"
HIBA_UNIT_FILE="/etc/systemd/system/$UNIT"
SWTPM_UNIT_FILE="/etc/systemd/system/swtpm-hiba.service"
TPM_DA_MARK="$DIR/.tpm-da-v1"

# 從本機 jar 取出一個檔案（jar 就是 zip）
jar_file() {
    python3 - "$DIR/$JAR" "$1" <<'EOF'
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        sys.stdout.buffer.write(z.read(sys.argv[2]))
except Exception:
    sys.exit(1)
EOF
}

maintain() {
    # 1. 自我更新：jar 內附的 hiba_dist/hiba_autoupdate.sh（pom.xml 打包進去的）
    local tmp
    tmp="$(mktemp)"
    if [ -f "$DIR/$JAR" ] && jar_file hiba_dist/hiba_autoupdate.sh > "$tmp" 2>/dev/null \
        && [ -s "$tmp" ] && bash -n "$tmp" 2>/dev/null && [ -f "$SELF" ] && ! cmp -s "$tmp" "$SELF"; then
        install -m 755 "$tmp" "$SELF" && log "自動更新程式已換成 jar 內附的版本（下次執行生效）"
    fi
    rm -f "$tmp"

    # 2. HiBA 遇到錯誤常以 exit code 0 結束，on-failure 不會重啟它
    if [ -f "$HIBA_UNIT_FILE" ] && grep -q '^Restart=on-failure$' "$HIBA_UNIT_FILE"; then
        sed -i 's/^Restart=on-failure$/Restart=always/' "$HIBA_UNIT_FILE" \
            && systemctl daemon-reload && log "hiba.service 已改為 Restart=always"
    fi

    # 2b. 反向通道（跨網段傳檔）：下層 broker 的公鑰寫在 $DIR/auth_ch，sshd 要讀這個檔才接受它們 ssh 進來
    if [ "$(env_value reverseChannel)" = "True" ] && { command -v sshd >/dev/null || [ -x /usr/sbin/sshd ]; } \
        && grep -qsE '^\s*Include\s+/etc/ssh/sshd_config\.d/' /etc/ssh/sshd_config; then
        local dropin="/etc/ssh/sshd_config.d/hiba-reverse-channel.conf" want
        want="$(printf '# HiBA：下層 broker 建立反向通道用的金鑰（%s/auth_ch，只能轉發 port）\nMatch User %s\n    AuthorizedKeysFile .ssh/authorized_keys %s/auth_ch\n' \
            "$DIR" "$HIBA_USER" "$DIR")"
        if [ "$(cat "$dropin" 2>/dev/null)" != "$want" ]; then
            printf '%s\n' "$want" > "$dropin"
            if /usr/sbin/sshd -t 2>/dev/null || sshd -t 2>/dev/null; then
                systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null
                log "sshd 已設定讀取 $DIR/auth_ch（反向通道）"
            else
                rm -f "$dropin"
                log "sshd 設定檢查失敗，沒有啟用反向通道設定"
            fi
        fi
        # sshd StrictModes：家目錄、HiBA 目錄、auth_ch 都不能讓別人寫入，否則金鑰會被忽略
        chmod go-w "$(getent passwd "$HIBA_USER" | cut -d: -f6)" "$DIR" 2>/dev/null
        [ -f "$DIR/auth_ch" ] && chmod 600 "$DIR/auth_ch"
    fi

    # 3. 軟體 TPM：非正常關機（斷電、強制關 VM）會算一次授權失敗，預設 3 次就鎖住、簽章全部失敗
    if [ -f "$SWTPM_UNIT_FILE" ] && command -v tpm2_dictionarylockout >/dev/null 2>&1; then
        local caps
        caps="$(tpm2_getcap properties-variable 2>/dev/null)"
        if [[ "$caps" =~ inLockout:[[:space:]]*1 ]]; then
            tpm2_dictionarylockout --clear-lockout >/dev/null 2>&1 && log "軟體 TPM 被鎖定（字典攻擊保護），已自動解除"
        fi
        if [ ! -f "$TPM_DA_MARK" ] && tpm2_dictionarylockout --setup-parameters \
            --max-tries=1000 --recovery-time=60 --lockout-recovery-time=60 >/dev/null 2>&1; then
            touch "$TPM_DA_MARK"
            own "$TPM_DA_MARK"
            log "軟體 TPM 的字典攻擊保護已調寬（失敗 1000 次才鎖定，每 60 秒恢復 1 次）"
        fi
    fi
}

maintain

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

# accounting server 可能由根網段的 broker 接手（本機 /api/acct 會回答是誰）：版本也回報給它，狀態頁才看得到。
# 只回報，jar 一律從 env.json 設定的位址下載，避免從還沒更新的代理 broker 換到舊版。
python3 - "$ACCT" "$NAME" "$HAVE" "$RUNNING" >/dev/null 2>&1 <<'EOF'
import json, sys, urllib.parse, urllib.request
seed, name, have, running = sys.argv[1:5]
try:
    info = json.load(urllib.request.urlopen("http://127.0.0.1:8092/api/acct", timeout=3))
except Exception:
    sys.exit(0)
acct = info.get("acct")
if not acct or acct == seed:
    sys.exit(0)
q = urllib.parse.urlencode({"node": name, "have": have, "running": running})
try:
    urllib.request.urlopen(f"http://{acct}:8092/dist/hiba-core-0.0.1.jar.md5?{q}", timeout=5).read()
except Exception:
    pass
EOF

# 檔案合約位址同步：accounting server（env.json 設定的那台，和 jar 來源相同）換了新的 FileProtectionContract
# （deploy_contracts.sh --file-only）時，自動改本機 env.json 並重啟 HiBA，不必逐台手動修改。
# 安全檢查（用本機 env.json 的 RPC 直接問鏈，不只相信 accounting server）：
#   新位址上要有合約；且要是新版檔案合約（有 deviceRegistry()），指向的設備登記合約要和本機設定的相同。
#   舊版檔案合約沒有 deviceRegistry()，所以不會被「同步回」舊合約。
CONTRACT_CHANGE="$(python3 - "$ACCT" "$DIR/env.json" <<'EOF' 2>/dev/null
import json, re, shutil, sys, time, urllib.request
acct, path = sys.argv[1:3]
ADDR = re.compile(r"^0x[0-9a-fA-F]{40}$")
try:
    remote = json.load(urllib.request.urlopen(f"http://{acct}:8092/api/chain", timeout=10))["contracts"]["file"]
    env = json.load(open(path))
    local = env["BlockchainFileProtect"]["contractAddress"]
    registry = env["BlockchainTPMRegistry"]["contractAddress"]
    rpc = env["Blockchain"]["rpcURL"]
except Exception:
    sys.exit(0)
if not remote or not ADDR.match(remote) or remote.lower() == str(local).lower():
    sys.exit(0)

def rpc_call(method, params):
    req = urllib.request.Request(rpc, json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode(),
                                 {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=10)).get("result")

try:
    code = rpc_call("eth_getCode", [remote, "latest"])
    # deviceRegistry() 的 selector：0x5efb870a
    reg = rpc_call("eth_call", [{"to": remote, "data": "0x5efb870a"}, "latest"])
except Exception:
    sys.exit(0)
if not code or code == "0x" or not reg or len(reg) < 66:
    print(f"SKIP accounting server 的檔案合約 {remote} 不是新版或鏈上沒有，沿用 {local}")
    sys.exit(0)
if ("0x" + reg[-40:]).lower() != str(registry).lower():
    print(f"SKIP 檔案合約 {remote} 指向的設備登記合約和本機設定的 {registry} 不同，沿用 {local}")
    sys.exit(0)
shutil.copy2(path, f"{path}.bak-contract-{time.strftime('%Y%m%d-%H%M%S')}")
env["BlockchainFileProtect"]["contractAddress"] = remote
with open(path, "w") as f:
    json.dump(env, f, indent=4, ensure_ascii=False)
print(f"CHANGED {local} {remote}")
EOF
)"
case "$CONTRACT_CHANGE" in
    CHANGED*)
        read -r _ OLD_C NEW_C <<< "$CONTRACT_CHANGE"
        own "$DIR"/env.json.bak-contract-*
        chmod 600 "$DIR/env.json"
        log "檔案合約已改為 $NEW_C（原本 $OLD_C；env.json 已備份）"
        if [ "$RUNNING" = 1 ]; then
            if use_systemd; then
                systemctl restart "$UNIT" && log "已重啟 HiBA 套用新的檔案合約"
            else
                stop_hiba
                start_and_check && log "已重啟 HiBA 套用新的檔案合約" || log "重啟 HiBA 失敗，請手動檢查"
            fi
        fi
        ;;
    SKIP*)
        # 同樣的訊息只記一次
        MARK="$DIR/.contract-skip"
        if [ "$(cat "$MARK" 2>/dev/null)" != "$CONTRACT_CHANGE" ]; then
            log "${CONTRACT_CHANGE#SKIP }"
            echo "$CONTRACT_CHANGE" > "$MARK"
            own "$MARK"
        fi
        ;;
esac

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
