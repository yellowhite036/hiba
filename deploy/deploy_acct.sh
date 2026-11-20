#!/bin/bash
# 在 Pi（專用 accounting server）上：編譯這份原始碼、換上新 jar、重新啟動，等它真的能回應再檢查。
#
#   cd ~/build/hiba-dev && rm -rf hiba && tar -xzf ~/hiba_dev_src.tar.gz && bash hiba/deploy/deploy_acct.sh
#
#   --no-build   不編譯，直接用 output/hiba-core-0.0.1.jar
#   --rollback   換回上一次部署前備份的 jar
#   --hiba DIR   HiBA 目錄（預設 ~/HiBA）
#   --dist DIR   給節點自動更新用的目錄（預設 ~/hiba_dist）
#
# 會做的事：
#   1. mvn package（約數分鐘）
#   2. 停止 hiba.service → 備份舊 jar 到 <HiBA>/.backup-deploy/（保留最近 5 份）→ 換 jar
#      → 同步 ~/hiba_dist 的 jar 與 .sh（節點會自動更新到這一版）
#   3. 設定 logrotate 輪替 <HiBA>/logs/*startup.log（java 主控台輸出，log4j2 不會輪替它）
#   4. 啟動 → 最多等 180 秒直到 http://localhost:8092/api/nodes 回應（啟動約需 40 秒）
#      → 印出 jar md5 與 accounting server 自己的狀態（acting、self）
#   等不到回應時印出最近的紀錄，並提示怎麼回復。
set -uo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
HIBA="$HOME/HiBA"
DIST="$HOME/hiba_dist"
BUILD=1
ROLLBACK=0
UNIT="hiba.service"
JAR="hiba-core-0.0.1.jar"
PORT=8092
WAIT_SEC=180
KEEP=5

ok()   { echo "  ✔ $*"; }
warn() { echo "  ⚠ $*"; }
fail() { echo "  ✘ $*"; exit 1; }
step() { echo; echo "== $* =="; }

while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) BUILD=0; shift ;;
        --rollback) ROLLBACK=1; shift ;;
        --hiba) HIBA="$2"; shift 2 ;;
        --dist) DIST="$2"; shift 2 ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        *) fail "不認得的選項: $1" ;;
    esac
done

[ -f "$HIBA/env.json" ] || fail "找不到 $HIBA/env.json（用 --hiba 指定 HiBA 目錄）"
BACKUP_DIR="$HIBA/.backup-deploy"

# 先問一次 sudo 密碼（前景），後面的 sudo 就不會卡在背景提示
sudo -v || fail "需要 sudo"

stop_hiba() {
    sudo systemctl stop "$UNIT" && ok "已停止 $UNIT"
}

# 等到 /api/nodes 回應 JSON；回傳 0 表示成功
wait_ready() {
    local i
    for i in $(seq "$WAIT_SEC"); do
        if ! systemctl is-active --quiet "$UNIT"; then
            echo
            warn "$UNIT 已經停止（$(systemctl is-active "$UNIT")）"
            return 1
        fi
        if curl -sf -m 3 "http://localhost:$PORT/api/nodes" 2>/dev/null | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
            echo
            ok "第 $i 秒開始回應 http://localhost:$PORT/api/nodes"
            return 0
        fi
        [ $((i % 10)) = 0 ] && printf '  …已等 %s 秒\n' "$i"
        sleep 1
    done
    echo
    warn "等了 $WAIT_SEC 秒，$PORT 還是沒有回應"
    return 1
}

show_status() {
    md5sum "$HIBA/$JAR" | sed 's/^/  /'
    curl -sf -m 5 "http://localhost:$PORT/api/nodes" | python3 -c '
import sys, json
d = json.load(sys.stdin)
s = d["accountingServer"]
print("  acting:", s["acting"])
me = s.get("self")
if s["acting"]:
    print("  （由 broker 兼任：它自己在節點樹裡，不用 self）")
elif not me:
    print("  ⚠ self 是 null：跑的可能不是新版 jar，或自我報告沒有啟動")
else:
    c = me.get("chain") or {}
    w = (c.get("wallet") or {}).get("status")
    print("  self:", me["status"], "| EK", (me.get("ek") or "未回報（讀不到 TPM）")[:16],
          "| 鏈上註冊", c.get("registered"), "| 錢包", w)
sm = d["summary"]
print("  節點：%d 台，%d 台在線，%d 個 broker" % (sm["total"], sm["online"], sm["brokers"]))
' || warn "讀不到 /api/nodes"
}

show_failure() {
    echo
    echo "  最近的紀錄："
    sudo journalctl -u "$UNIT" -n 20 --no-pager | sed 's/^/    /'
    local f
    f="$(ls -t "$HIBA"/logs/*startup.log 2>/dev/null | head -1)"
    [ -n "$f" ] && { echo "  $f 最後 30 行："; tail -n 30 "$f" | sed 's/^/    /'; }
    echo
    echo "  要換回上一版：bash $0 --rollback"
}

# ---------------------------------------------------------------------------
if [ "$ROLLBACK" = 1 ]; then
    step "回復上一版"
    LAST="$(ls -t "$BACKUP_DIR"/hiba-core-*.jar 2>/dev/null | head -1)"
    [ -n "$LAST" ] || fail "$BACKUP_DIR 裡沒有備份"
    stop_hiba
    cp -p "$LAST" "$HIBA/$JAR" && ok "已換回 $LAST"
    cp "$HIBA/$JAR" "$DIST/$JAR" 2>/dev/null && ok "已同步到 $DIST（節點會自動換回這一版）"
    sudo systemctl start "$UNIT"
    if wait_ready; then show_status; else show_failure; exit 1; fi
    exit 0
fi

NEW="$SRC/output/$JAR"
if [ "$BUILD" = 1 ]; then
    step "1. 編譯（約數分鐘）"
    [ -f "$SRC/pom.xml" ] || fail "$SRC 不是 HiBA 原始碼目錄"
    (cd "$SRC" && mvn -q -DskipTests package) || fail "編譯失敗，沒有動到執行中的 HiBA"
fi
[ -f "$NEW" ] || fail "找不到 $NEW"
NEW_MD5="$(md5sum "$NEW" | cut -d' ' -f1)"
ok "新 jar：$NEW_MD5"

step "2. 換 jar"
mkdir -p "$BACKUP_DIR"
if [ -f "$HIBA/$JAR" ] && [ "$(md5sum "$HIBA/$JAR" | cut -d' ' -f1)" = "$NEW_MD5" ]; then
    warn "執行中的 jar 已經是這一版，照樣重新啟動"
fi
stop_hiba
if [ -f "$HIBA/$JAR" ]; then
    B="$BACKUP_DIR/hiba-core-$(date +%Y%m%d-%H%M%S).jar"
    cp -p "$HIBA/$JAR" "$B" && ok "舊 jar 備份為 $B"
    # 只保留最近 $KEEP 份
    ls -t "$BACKUP_DIR"/hiba-core-*.jar 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
fi
cp "$NEW" "$HIBA/$JAR" || { sudo systemctl start "$UNIT"; fail "複製 jar 失敗，已用舊 jar 重新啟動"; }
ok "已換上新 jar"
if [ -d "$DIST" ]; then
    cp "$HIBA/$JAR" "$DIST/$JAR" && cp "$SRC"/deploy/hiba_dist/*.sh "$DIST/" \
        && ok "已同步 $DIST 的 jar 與 .sh（節點會自動更新到這一版）" \
        || warn "同步 $DIST 失敗（HiBA 照常啟動）"
else
    warn "沒有 $DIST，略過同步"
fi

step "3. 主控台輸出輪替"
if command -v logrotate >/dev/null 2>&1; then
    ROT="/etc/logrotate.d/hiba-$(id -un)"
    WANT="$(printf '# HiBA 主控台輸出（deploy_acct.sh 產生）\n%s/logs/*startup.log {\n    su %s %s\n    daily\n    maxsize 10M\n    rotate 5\n    compress\n    missingok\n    notifempty\n    copytruncate\n}\n' \
        "$HIBA" "$(id -un)" "$(id -gn)")"
    if [ "$(cat "$ROT" 2>/dev/null)" = "$WANT" ]; then
        ok "已經設定過（$ROT）"
    else
        printf '%s\n' "$WANT" | sudo tee "$ROT" >/dev/null && sudo chmod 644 "$ROT"
        if sudo logrotate -d "$ROT" >/dev/null 2>&1; then
            ok "已設定 $ROT（超過 10MB 就輪替，保留 5 份壓縮檔）"
            # 已經很大的檔案現在就輪替一次
            sudo logrotate -f "$ROT" >/dev/null 2>&1 && ok "已先輪替一次目前的檔案"
        else
            sudo rm -f "$ROT"
            warn "logrotate 設定檢查失敗，沒有啟用"
        fi
    fi
else
    warn "沒有 logrotate（sudo apt install logrotate），略過"
fi

step "4. 啟動並等待回應（啟動約需 40 秒）"
sudo systemctl start "$UNIT"
if wait_ready; then
    show_status
    echo
    ok "完成。網頁：http://$(hostname -I | awk '{print $1}'):$PORT/nodes"
else
    show_failure
    exit 1
fi
