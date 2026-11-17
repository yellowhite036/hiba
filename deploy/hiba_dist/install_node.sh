#!/bin/bash
# HiBA 新節點安裝（乾淨的 Ubuntu 虛擬機；沒有 TPM 會自動改用 swtpm）
#
# 一行執行（會要求輸入本機 sudo 密碼；註冊時再輸入 Pi 的登入密碼與 Pi 的 sudo 密碼）：
#   scp -r user@192.168.1.62:/home/user/hiba_dist ~/ && bash ~/hiba_dist/install_node.sh --name NewNode-XXX
#
# 會做的事：
#   1. 安裝套件：tpm2-tools、openssl、python3、curl、traceroute、Java 21（並建立 /usr/jvm/jdk-21.0.2）
#   2. 時區設為台灣；NTP 改用 time.stdtime.gov.tw；與 accounting server 對時
#   3. TPM：沒有 TPM 就建立 swtpm 軟體 TPM（swtpm-hiba.service）、加入 tss 群組、建立簽章金鑰 0x81000001（已存在就沿用）、測試簽章
#   4. 建立 ~/HiBA（設定檔取自 hiba_dist/template/；已有 env.json 就不覆蓋）
#   5. 算出 EK 指紋與簽章公鑰，ssh 到 Pi 執行 register_node.sh 上鏈註冊
#   6. 等 accounting server 讀到註冊後，自動執行 setup_node.sh 啟動 HiBA
# 重跑是安全的：做過的步驟會略過。
#
# 選項：
#   --name NAME     nodeName（預設 NewNode-<本機 IP 最後一碼>，每台要不同）
#   --acct IP       accounting server IP（預設 192.168.1.62）
#   --pi-user USER  ssh 到 Pi 的帳號（預設 user）
#   --dir PATH      HiBA 目錄（預設 ~/HiBA）
#   --no-register   不自動 ssh 到 Pi，只印出註冊指令
#   --no-start      裝完不啟動 HiBA
#   --autostart     開機自動啟動 HiBA（systemd 服務 hiba.service），不問
#   --no-autostart  不要開機自動啟動（已設定的會移除），不問
#                   兩者都沒給：第一次執行時會問，之後沿用第一次的選擇
set -uo pipefail

ACCT="192.168.1.62"
PI_USER="user"
NAME=""
DIR="$HOME/HiBA"
DO_REGISTER=1
DO_START=1
HANDLE="0x81000001"
JDK_LINK="/usr/jvm/jdk-21.0.2"
REG_WAIT_SEC=180
AUTOSTART=ask

while [ $# -gt 0 ]; do
    case "$1" in
        --name) NAME="$2"; shift 2 ;;
        --acct) ACCT="$2"; shift 2 ;;
        --pi-user) PI_USER="$2"; shift 2 ;;
        --dir) DIR="$2"; shift 2 ;;
        --no-register) DO_REGISTER=0; shift ;;
        --no-start) DO_START=0; shift ;;
        --autostart) AUTOSTART=on; shift ;;
        --no-autostart) AUTOSTART=off; shift ;;
        -h|--help) sed -n '2,/^set /{/^set /!p}' "$0"; exit 0 ;;
        *) echo "不認得的選項: $1（用 --help 看說明）"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ok()   { echo "  ✔ $*"; }
warn() { echo "  ⚠ $*"; }
fail() { echo "  ✘ $*"; echo; echo "已停止。修正後重跑同一行指令即可（做過的步驟會略過）。"; exit 1; }
step() { echo; echo "== $* =="; }

find_java() {
    local c
    for c in "$JDK_LINK/bin/java" "$(command -v java 2>/dev/null)"; do
        [ -n "$c" ] && [ -x "$c" ] && "$c" -version 2>&1 | head -1 | grep -q '"21' && { echo "$c"; return; }
    done
}

# 新裝好的 Ubuntu 開機後會先跑 unattended-upgrades 自動更新，期間 apt 被鎖住
APT_LOCK="-o DPkg::Lock::Timeout=900"
apt_busy() {
    # 只看 dpkg/apt 的鎖有沒有被占用。不能用 pgrep unattended-upgr：
    # 常駐的 unattended-upgrade-shutdown 行程名稱也是 unattended-upgr，會讓人永遠等下去
    if command -v fuser >/dev/null; then
        sudo fuser /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/lib/apt/lists/lock >/dev/null 2>&1
    else
        pgrep -x 'apt|apt-get|dpkg' >/dev/null || pgrep -f '/usr/bin/unattended-upgrade( |$)' >/dev/null
    fi
}
wait_apt() {
    local waited=0
    while apt_busy; do
        [ "$waited" = 0 ] && echo "  系統正在自動更新（unattended-upgrades），等它結束後再安裝，可能要幾分鐘…"
        sleep 10
        waited=$((waited + 10))
        [ $((waited % 60)) = 0 ] && echo "  …已等 ${waited} 秒"
        [ "$waited" -ge 900 ] && { warn "等了 15 分鐘仍未結束，直接嘗試安裝"; break; }
    done
}

api_json() {  # 印出 accounting server /api/nodes 的 JSON，失敗印空字串
    python3 - "$ACCT" <<'EOF' 2>/dev/null
import sys, urllib.request
try:
    print(urllib.request.urlopen(f"http://{sys.argv[1]}:8092/api/nodes", timeout=5).read().decode())
except Exception:
    pass
EOF
}

# ---------------------------------------------------------------------------
step "0. 前置檢查"
[ "$(id -u)" != 0 ] || fail "請用一般使用者執行（不要用 sudo / root），腳本需要時會自己呼叫 sudo"
[ -f "$SCRIPT_DIR/setup_node.sh" ] || fail "找不到 $SCRIPT_DIR/setup_node.sh，請用 scp -r 把整個 hiba_dist 抓下來"
# 不可直接 source /etc/os-release：它的 NAME="Ubuntu" 會蓋掉 --name 設定的 NAME
OS_ID="$(. /etc/os-release 2>/dev/null; echo "${ID:-}")"
OS_PRETTY="$(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-}")"
case "$OS_ID" in
    ubuntu|debian) ok "系統：${OS_PRETTY:-$OS_ID}" ;;
    *) warn "這個腳本是為 Ubuntu 寫的，目前系統是 ${OS_PRETTY:-未知}，可能會失敗" ;;
esac
echo "  若要求輸入密碼，請輸入「這台虛擬機」$(whoami) 的 sudo 密碼："
sudo -v || fail "需要 sudo 權限"

if timeout 3 bash -c "echo > /dev/tcp/$ACCT/8091" 2>/dev/null; then
    ok "連得到 accounting server $ACCT:8091"
else
    fail "連不到 accounting server $ACCT:8091（虛擬機網卡要設成 NAT；Pi 上的 HiBA 要在執行）"
fi

LOCAL_IP="$(ip route get "$ACCT" 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1)"
[ -n "$NAME" ] || NAME="NewNode-${LOCAL_IP##*.}"
echo "  本機 IP: ${LOCAL_IP:-未知}   nodeName: $NAME"
[[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || fail "nodeName 只能用英數字和 . _ -：$NAME"

# ---------------------------------------------------------------------------
# 之後 ssh 到 Pi 用的設定（第 5 步註冊時用）；同一次執行共用一條連線，Pi 的登入密碼只要輸入一次
REMOTE_DIST="/home/$PI_USER/hiba_dist"
mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
SSH_OPTS=(-o ConnectTimeout=5 -o ControlMaster=auto -o "ControlPath=$HOME/.ssh/hiba-cm-%r@%h:%p" -o ControlPersist=20m)
pi_ssh_t() { ssh -t "${SSH_OPTS[@]}" "$PI_USER@$ACCT" "$@"; }

# 開機自動啟動：第一次（還沒有 hiba.service）才問；之後沿用，除非用 --autostart / --no-autostart 指定
HIBA_UNIT="/etc/systemd/system/hiba.service"
if [ "$AUTOSTART" = ask ]; then
    if [ -f "$HIBA_UNIT" ]; then
        AUTOSTART=keep
        ok "開機自動啟動：已設定，沿用（要取消請加 --no-autostart）"
    elif [ -t 0 ]; then
        read -rp "  之後重新開機要自動啟動 HiBA 嗎？[Y/n]: " ANS
        case "$ANS" in n|N|no|NO) AUTOSTART=off ;; *) AUTOSTART=on ;; esac
    else
        AUTOSTART=on
    fi
fi
case "$AUTOSTART" in
    on) ok "開機自動啟動：開啟（第 6 步建立 systemd 服務 hiba.service）" ;;
    off) ok "開機自動啟動：關閉（重開機後要手動執行 setup_node.sh）" ;;
esac

# ---------------------------------------------------------------------------
step "1. 安裝套件"
NEED=""
for p in tpm2-tools openssl python3 curl ca-certificates traceroute; do
    dpkg -s "$p" >/dev/null 2>&1 || NEED="$NEED $p"
done
if [ -n "$NEED" ]; then
    echo "  安裝:$NEED"
    wait_apt
    sudo apt-get $APT_LOCK update -qq || warn "apt-get update 有錯誤"
    # shellcheck disable=SC2086
    sudo DEBIAN_FRONTEND=noninteractive apt-get $APT_LOCK install -y -qq $NEED >/dev/null || fail "套件安裝失敗:$NEED"
fi
ok "tpm2-tools / openssl / python3 / curl / traceroute 已安裝"

JAVA_BIN="$(find_java)"
if [ -z "$JAVA_BIN" ]; then
    echo "  安裝 Java 21（openjdk-21-jre-headless）…"
    wait_apt
    if sudo DEBIAN_FRONTEND=noninteractive apt-get $APT_LOCK install -y -qq openjdk-21-jre-headless >/dev/null 2>&1; then
        J21="$(ls -d /usr/lib/jvm/java-21-openjdk-* 2>/dev/null | head -1)"
        [ -n "$J21" ] && [ ! -e "$JDK_LINK" ] && sudo mkdir -p /usr/jvm && sudo ln -s "$J21" "$JDK_LINK"
    else
        warn "apt 沒有 openjdk-21，改從 Adoptium 下載 Temurin 21.0.2+13"
        case "$(uname -m)" in
            x86_64) ARCH=x64 ;;
            aarch64) ARCH=aarch64 ;;
            *) fail "不支援的 CPU 架構 $(uname -m)" ;;
        esac
        TMPJ="$(mktemp -d)"
        INFO="$(curl -fsSL "https://api.adoptium.net/v3/assets/release_name/eclipse/jdk-21.0.2%2B13?architecture=$ARCH&image_type=jdk&os=linux&jvm_impl=hotspot&heap_size=normal")" \
            || fail "連不到 api.adoptium.net"
        read -r LINK SUM < <(python3 -c '
import json, sys
d = json.loads(sys.stdin.read())
d = d[0] if isinstance(d, list) else d
p = d["binaries"][0]["package"]
print(p["link"], p["checksum"])' <<<"$INFO") || fail "看不懂 Adoptium 的回應"
        curl -fL --progress-bar -o "$TMPJ/jdk.tar.gz" "$LINK" || fail "下載 JDK 失敗"
        echo "$SUM  $TMPJ/jdk.tar.gz" | sha256sum -c --quiet || fail "JDK 檔案 sha256 不符，已停止"
        sudo mkdir -p "$JDK_LINK" && sudo tar -xzf "$TMPJ/jdk.tar.gz" -C "$JDK_LINK" --strip-components=1 \
            || fail "解壓 JDK 失敗"
        rm -rf "$TMPJ"
    fi
    JAVA_BIN="$(find_java)"
    [ -n "$JAVA_BIN" ] || fail "Java 21 安裝後仍找不到"
fi
[ -e "$JDK_LINK/bin/java" ] || { sudo mkdir -p /usr/jvm && sudo ln -s "$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")" "$JDK_LINK"; }
ok "Java 21：$JAVA_BIN"

# ---------------------------------------------------------------------------
step "2. 時區與對時"
sudo timedatectl set-timezone Asia/Taipei 2>/dev/null && ok "時區：Asia/Taipei" || warn "無法設定時區"
if systemctl list-unit-files systemd-timesyncd.service >/dev/null 2>&1; then
    # VMware NAT 走不通 ntp.ubuntu.com 的 IPv6，改用國家時間與頻率標準實驗室
    sudo mkdir -p /etc/systemd/timesyncd.conf.d
    printf '[Time]\nNTP=time.stdtime.gov.tw tock.stdtime.gov.tw\n' \
        | sudo tee /etc/systemd/timesyncd.conf.d/hiba.conf >/dev/null
    sudo timedatectl set-ntp true 2>/dev/null
    sudo systemctl restart systemd-timesyncd 2>/dev/null
    ok "NTP：time.stdtime.gov.tw"
else
    warn "沒有 systemd-timesyncd，請自行確認對時"
fi
SKEW_T="$(python3 - "$ACCT" <<'EOF' 2>/dev/null
import json, sys, time, urllib.request
t0 = time.time()
d = json.load(urllib.request.urlopen(f"http://{sys.argv[1]}:8092/api/nodes", timeout=5))
t1 = time.time()
srv = d["generatedAt"] / 1000
print(round((t0 + t1) / 2 - srv), int(srv + (t1 - t0) / 2))
EOF
)"
if [ -n "$SKEW_T" ]; then
    read -r SKEW SRV_T <<<"$SKEW_T"
    if [ "${SKEW#-}" -gt 5 ]; then
        sudo date -s "@$SRV_T" >/dev/null && ok "本機原本差 ${SKEW} 秒，已改成 accounting server 的時間"
    else
        ok "與 accounting server 相差 ${SKEW} 秒"
    fi
else
    warn "讀不到 accounting server 的時間（http://$ACCT:8092/api/nodes），略過對時檢查"
fi

# ---------------------------------------------------------------------------
step "3. TPM"
SWTPM_STATE="/var/lib/swtpm-hiba"
SWTPM_UNIT="/etc/systemd/system/swtpm-hiba.service"
if [ ! -e /dev/tpmrm0 ]; then
    # BIOS 開機的 VMware 虛擬機看不到 vTPM：和 227 / Pi 一樣改用 swtpm 軟體 TPM（tpm_vtpm_proxy）
    echo "  沒有硬體 TPM，建立軟體 TPM（swtpm-hiba.service）"
    sudo modprobe tpm_vtpm_proxy 2>/dev/null \
        || fail "核心沒有 tpm_vtpm_proxy 模組（modinfo tpm_vtpm_proxy 查看），無法使用軟體 TPM"
    if ! command -v swtpm >/dev/null; then
        wait_apt
        sudo DEBIAN_FRONTEND=noninteractive apt-get $APT_LOCK install -y -qq swtpm >/dev/null || fail "安裝 swtpm 失敗"
    fi
    if [ -d "$SWTPM_STATE" ] && [ -n "$(sudo ls -A "$SWTPM_STATE" 2>/dev/null)" ]; then
        warn "$SWTPM_STATE 已有資料，沿用（這就是本機 TPM 身分；若虛擬機是複製來的，身分會和來源重複）"
    fi
    sudo mkdir -p "$SWTPM_STATE" && sudo chmod 700 "$SWTPM_STATE"
    # Ubuntu 的 swtpm 有 AppArmor 設定檔，只准寫 libvirt 的目錄；把我們的狀態目錄加進 local 例外
    AA_PROFILE="$(grep -lE '^\s*profile swtpm ' /etc/apparmor.d/* 2>/dev/null | head -1)"
    if [ -n "$AA_PROFILE" ]; then
        AA_LOCAL="/etc/apparmor.d/local/$(basename "$AA_PROFILE")"
        if ! sudo grep -qs "$SWTPM_STATE" "$AA_LOCAL"; then
            sudo mkdir -p /etc/apparmor.d/local
            printf '%s/ rw,\n%s/** rwk,\n' "$SWTPM_STATE" "$SWTPM_STATE" | sudo tee -a "$AA_LOCAL" >/dev/null
        fi
        grep -q "local/$(basename "$AA_PROFILE")" "$AA_PROFILE" \
            || warn "AppArmor 設定檔 $AA_PROFILE 沒有引用 $AA_LOCAL，例外可能無效"
        sudo apparmor_parser -r "$AA_PROFILE" && ok "AppArmor：允許 swtpm 寫入 $SWTPM_STATE"
    fi
    sudo tee "$SWTPM_UNIT" >/dev/null <<EOF
[Unit]
Description=Software TPM for HiBA (vtpm proxy)
After=systemd-modules-load.service

[Service]
Type=forking
ExecStartPre=/sbin/modprobe tpm_vtpm_proxy
ExecStart=/usr/bin/swtpm chardev --vtpm-proxy --tpm2 --tpmstate dir=$SWTPM_STATE --daemon
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF
    sudo systemctl daemon-reload
    sudo systemctl reset-failed swtpm-hiba.service 2>/dev/null
    sudo systemctl enable swtpm-hiba.service >/dev/null 2>&1
    sudo systemctl restart swtpm-hiba.service
    for _ in $(seq 10); do [ -e /dev/tpmrm0 ] && break; sleep 1; done
    if [ ! -e /dev/tpmrm0 ]; then
        echo "  ✘ swtpm 啟動後仍沒有 /dev/tpmrm0，相關紀錄："
        sudo journalctl -u swtpm-hiba.service -n 15 --no-pager | sed 's/^/      /'
        sudo dmesg | grep -iE "tpm|apparmor.*swtpm" | tail -n 5 | sed 's/^/      /'
        exit 1
    fi
    sudo udevadm settle 2>/dev/null
    ok "軟體 TPM 已啟動，開機會自動執行（身分存在 $SWTPM_STATE，請勿刪除）"
fi
TPM_GROUP="$(stat -c %G /dev/tpmrm0)"
if [ "$TPM_GROUP" = root ]; then
    getent group tss >/dev/null || sudo groupadd -r tss
    echo 'KERNEL=="tpm[0-9]*", GROUP="tss", MODE="0660"
KERNEL=="tpmrm[0-9]*", GROUP="tss", MODE="0660"' | sudo tee /etc/udev/rules.d/60-hiba-tpm.rules >/dev/null
    sudo udevadm control --reload && sudo udevadm trigger --subsystem-match=tpmrm --subsystem-match=tpm
    sleep 1
    TPM_GROUP="$(stat -c %G /dev/tpmrm0)"
fi
if [ -f "$SWTPM_UNIT" ]; then
    # 軟體 TPM 非正常關機（斷電、強制關 VM）會算一次授權失敗，預設 3 次就鎖住、HiBA 簽章全部失敗
    if sudo tpm2_getcap properties-variable 2>/dev/null | grep -qE 'inLockout:\s*1'; then
        sudo tpm2_dictionarylockout --clear-lockout >/dev/null 2>&1 && warn "軟體 TPM 原本被鎖定（字典攻擊保護），已解除"
    fi
    if sudo tpm2_dictionarylockout --setup-parameters --max-tries=1000 --recovery-time=60 \
        --lockout-recovery-time=60 >/dev/null 2>&1; then
        ok "軟體 TPM 字典攻擊保護：失敗 1000 次才鎖定、每 60 秒恢復 1 次"
    else
        warn "無法調整軟體 TPM 的字典攻擊保護（自動更新程式之後會再試）"
    fi
fi
if id -nG "$(whoami)" | tr ' ' '\n' | grep -qx "$TPM_GROUP"; then
    ok "$(whoami) 已在 $TPM_GROUP 群組"
else
    sudo usermod -aG "$TPM_GROUP" "$(whoami)" && ok "已把 $(whoami) 加入 $TPM_GROUP 群組（重新登入後生效，本次腳本會自動處理）"
fi

tpmrun() {
    if [ -r /dev/tpmrm0 ] && [ -w /dev/tpmrm0 ]; then
        TPM2TOOLS_TCTI="device:/dev/tpmrm0" "$@"
    else
        # 剛加入 tss 群組還沒生效 → 用 sudo；tpm2-tools 以 root 寫出的檔案是 600，要改回本人才讀得到
        local rc=0
        sudo env TPM2TOOLS_TCTI="device:/dev/tpmrm0" "$@" || rc=$?
        sudo chown -R "$(id -u):$(id -g)" "$WORK" 2>/dev/null
        return "$rc"
    fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
KEY_CREATED=0
if tpmrun tpm2_getcap handles-persistent 2>/dev/null | grep -qi "$HANDLE"; then
    ok "簽章金鑰 $HANDLE 已存在，沿用"
else
    tpmrun tpm2_createprimary -C o -c "$WORK/primary.ctx" --quiet || fail "tpm2_createprimary 失敗"
    tpmrun tpm2_create -C "$WORK/primary.ctx" -G rsa2048:rsassa-sha256 \
        -u "$WORK/sign.pub" -r "$WORK/sign.priv" --quiet || fail "tpm2_create 失敗"
    tpmrun tpm2_load -C "$WORK/primary.ctx" -u "$WORK/sign.pub" -r "$WORK/sign.priv" \
        -c "$WORK/sign.ctx" --quiet || fail "tpm2_load 失敗"
    tpmrun tpm2_evictcontrol -C o -c "$WORK/sign.ctx" "$HANDLE" >/dev/null || fail "tpm2_evictcontrol 失敗"
    tpmrun tpm2_flushcontext -t >/dev/null 2>&1 || true
    KEY_CREATED=1
    ok "已建立簽章金鑰 $HANDLE（RSA 2048 / RSASSA-SHA256）"
fi
tpmrun tpm2_readpublic -c "$HANDLE" -f pem -o "$WORK/pub.pem" >/dev/null || fail "讀不到 $HANDLE 的公鑰"
PUB="$(grep -v -- '-----' "$WORK/pub.pem" | tr -d '\n')"
case "$PUB" in MIIBIjAN*) ;; *) fail "簽章公鑰格式不對（應為 RSA 2048）" ;; esac

echo "hiba tpm test" > "$WORK/msg"
if tpmrun tpm2_sign -c "$HANDLE" -g sha256 -f plain -o "$WORK/sig" "$WORK/msg" 2>/dev/null \
    && openssl dgst -sha256 -verify "$WORK/pub.pem" -signature "$WORK/sig" "$WORK/msg" >/dev/null 2>&1; then
    ok "簽章測試通過"
else
    fail "用 $HANDLE 簽章後驗證失敗"
fi

# 與 HiBA TPMService.getEKFingerprint() 相同：EK 公鑰 DER 的 SHA-256，大寫
tpmrun tpm2_createek -c "$WORK/ek.ctx" -G rsa >/dev/null 2>&1 || fail "tpm2_createek 失敗"
tpmrun tpm2_readpublic -c "$WORK/ek.ctx" -f pem -o "$WORK/ek.pem" >/dev/null || fail "讀不到 EK 公鑰"
tpmrun tpm2_flushcontext "$WORK/ek.ctx" >/dev/null 2>&1 || true
EK="$(openssl rsa -pubin -in "$WORK/ek.pem" -outform DER 2>/dev/null | sha256sum | awk '{print toupper($1)}')"
[[ "$EK" =~ ^[0-9A-F]{64}$ ]] || fail "EK 指紋計算失敗"
ok "EK 指紋：$EK"

API="$(api_json)"
if [ -n "$API" ]; then
    CLONE="$(python3 -c '
import json, sys
d = json.loads(sys.argv[1])
for n in d.get("nodes", []):
    if n.get("ek") == sys.argv[2] and n.get("ip") != sys.argv[3] and n.get("status") == "online":
        print("%s（%s）" % (n["name"], n["ip"]))
        break' "$API" "$EK" "$LOCAL_IP")"
    [ -z "$CLONE" ] || fail "在線節點 $CLONE 的 EK 和這台一樣：這台虛擬機多半是複製來的，TPM 身分重複。請移除 vTPM、再新增一個新的 vTPM 後重跑。"
fi

# ---------------------------------------------------------------------------
step "4. 建立 $DIR"
mkdir -p "$DIR"/{logs,files,request,response,receiveFile,uploadFile,resource,service,device,scripts}
if [ -f "$DIR/env.json" ]; then
    ok "已有 $DIR/env.json，保留不覆蓋"
else
    TPL="$SCRIPT_DIR/template"
    [ -f "$TPL/env.json" ] || fail "找不到 $TPL/env.json。請先在 Pi 上建立範本（見 hiba_dist/README 或問 Claude）"
    cp -rn "$TPL"/. "$DIR"/
    chmod 600 "$DIR/env.json"
    python3 - "$DIR/env.json" "$NAME" <<'EOF' || fail "修改 env.json 失敗"
import json, sys
p, name = sys.argv[1], sys.argv[2]
d = json.load(open(p))
h = d["HiBANetwork"]
h["nodeName"] = name
h["isAccountingServer"] = False
h["autoGetNetworkInterface"] = True
h["interfaceAddress"] = ""
h["multicastInterface"] = ""
json.dump(d, open(p, "w"), indent=4, ensure_ascii=False)
EOF
    ok "已從範本建立設定檔（nodeName=$NAME）"
fi
printf 'EK=%s\nPUB=%s\nHANDLE=%s\n' "$EK" "$PUB" "$HANDLE" > "$DIR/tpm_registration.txt"
ok "EK 與公鑰存到 $DIR/tpm_registration.txt"

RC="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['HiBANetwork'].get('reverseChannel', ''))" "$DIR/env.json")"
if [ "$RC" = "True" ] && [ ! -f "/home/$(whoami)/.ssh/id_rsa.pub" ]; then
    mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
    ssh-keygen -t rsa -b 3072 -N '' -q -f "$HOME/.ssh/id_rsa" && ok "已建立 ~/.ssh/id_rsa（reverseChannel 需要）"
fi

# ---------------------------------------------------------------------------
step "5. 上鏈註冊 TPM"
ek_registered() {  # 印出 yes / no / unknown
    python3 -c '
import json, sys
try:
    d = json.loads(sys.argv[1])
except Exception:
    print("unknown"); sys.exit()
if not d.get("chainStatus", {}).get("ok"):
    print("unknown"); sys.exit()
ek = sys.argv[2]
hit = any(n.get("ek") == ek and n.get("chain", {}).get("registered") for n in d.get("nodes", [])) \
    or any(x.get("ek") == ek for x in d.get("registeredNeverSeen", []))
print("yes" if hit else "no")' "$(api_json)" "$EK"
}

REG="$(ek_registered)"
REG_CMD="sudo bash $REMOTE_DIST/register_node.sh $EK $PUB"
if [ "$REG" = yes ] && [ "$KEY_CREATED" = 0 ]; then
    ok "這台的 EK 已經註冊在鏈上"
else
    [ "$REG" = yes ] && echo "  EK 已註冊，但簽章金鑰是剛建立的，要更新鏈上的公鑰"
    if [ "$DO_REGISTER" = 1 ]; then
        echo "  ssh 到 Pi 註冊：請輸入 Pi 的 sudo 密碼（若前面的 ssh 連線已斷，會先問 Pi 上 $PI_USER 的登入密碼）"
        if pi_ssh_t "$REG_CMD"; then
            ok "Pi 上的註冊完成"
        else
            warn "ssh 註冊失敗。請在 Pi 上手動執行（整行複製）："
            echo; echo "    $REG_CMD"; echo
        fi
    else
        echo "  請在 Pi 上執行（整行複製）："
        echo; echo "    $REG_CMD"; echo
    fi
    echo "  等待 accounting server 讀到註冊（每 60 秒讀一次鏈，最多等 ${REG_WAIT_SEC} 秒）…"
    for i in $(seq 0 10 "$REG_WAIT_SEC"); do
        REG="$(ek_registered)"
        [ "$REG" = yes ] && break
        [ "$i" -gt 0 ] && echo "  …已等 ${i} 秒"
        sleep 10
    done
    if [ "$REG" = yes ]; then
        ok "accounting server 已看到這台的 EK"
    else
        warn "還沒看到註冊（狀態：$REG）。若已註冊，稍等 1 分鐘再執行下方的 setup_node.sh"
        DO_START=0
    fi
fi

# ---------------------------------------------------------------------------
step "6. 啟動 HiBA"
if [ "$AUTOSTART" = on ]; then
    WANTS_SWTPM=""
    [ -f "$SWTPM_UNIT" ] && WANTS_SWTPM="swtpm-hiba.service"
    # 以本人身分執行，SupplementaryGroups 讓它不必重新登入就能用 TPM；啟動前最多等 60 秒讓 /dev/tpmrm0 出現
    sudo tee "$HIBA_UNIT" >/dev/null <<EOF
[Unit]
Description=HiBA node
Wants=network-online.target $WANTS_SWTPM
After=network-online.target time-sync.target $WANTS_SWTPM

[Service]
Type=simple
User=$(whoami)
SupplementaryGroups=$TPM_GROUP
WorkingDirectory=$DIR
ExecStartPre=/usr/bin/timeout 60 /bin/sh -c 'until [ -e /dev/tpmrm0 ]; do sleep 1; done'
ExecStart=$JDK_LINK/bin/java -DLOG_LEVEL=INFO -DTPM_LOG_LEVEL=WARN -jar hiba-core-0.0.1.jar
StandardOutput=append:$DIR/logs/startup.log
StandardError=inherit
# always：HiBA 遇到錯誤時常以 exit code 0 結束（例如連不到 accounting server），on-failure 不會重啟它
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
    sudo systemctl daemon-reload
    sudo systemctl enable hiba.service >/dev/null 2>&1 && ok "已建立並啟用 hiba.service（開機自動啟動、程式結束 10 秒後自動重啟）"
elif [ "$AUTOSTART" = off ] && [ -f "$HIBA_UNIT" ]; then
    sudo systemctl disable --now hiba.service >/dev/null 2>&1
    sudo rm -f "$HIBA_UNIT" && sudo systemctl daemon-reload
    ok "已移除 hiba.service（不再開機自動啟動）"
fi
SETUP_CMD="bash $(printf %q "$SCRIPT_DIR/setup_node.sh") --name $(printf %q "$NAME") --acct $(printf %q "$ACCT") --dir $(printf %q "$DIR")"
if [ "$DO_START" = 1 ]; then
    if [ -r /dev/tpmrm0 ] && [ -w /dev/tpmrm0 ]; then
        bash -c "$SETUP_CMD"
    else
        # 剛加入群組、還沒重新登入：用 sg 讓 HiBA 以 tss 群組身分執行
        sg "$TPM_GROUP" -c "$SETUP_CMD"
    fi
else
    echo "  之後啟動（若剛加入 $TPM_GROUP 群組，請先登出再登入）："
    echo; echo "    $SETUP_CMD"; echo
fi
