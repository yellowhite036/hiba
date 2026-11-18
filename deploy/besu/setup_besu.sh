#!/bin/bash
# 在這台機器（Pi）上用 Docker 建立 Hyperledger Besu 私有鏈（QBFT 共識），取代 Anvil 測試鏈。
#
#   bash setup_besu.sh                 依記憶體自動決定驗證節點數（>= 6 GB 用 4 個，否則 1 個）
#   bash setup_besu.sh --nodes 4       指定驗證節點數（1 或 4；4 個時壞 1 個仍能出塊）
#   bash setup_besu.sh --chain-id N    指定 Chain ID（預設 20261117，不要用 Anvil 的 31337）
#   bash setup_besu.sh --dir DIR       鏈的資料目錄（預設 ~/besu）
#   bash setup_besu.sh --status        只顯示目前狀態
#
# 完成後：
#   - 節點 1 的 JSON-RPC 開在 http://<本機IP>:8545（HiBA 與部署合約都連它）
#   - 容器設為 restart: unless-stopped，Docker 開機自動啟動時鏈也會自動起來
#   - 鏈的資料存在 DIR/node*/data，重啟不會消失（和 Anvil 不同）
# 已經建立過（DIR/genesis.json 存在）就不會重新產生，只會啟動；要重建請先自行備份並刪除 DIR。
set -uo pipefail

NODES=""
CHAIN_ID=20261117
DIR="$HOME/besu"
STATUS_ONLY=0
BESU_IMAGE="${BESU_IMAGE:-hyperledger/besu:latest}"
HEAP="${BESU_HEAP:-768m}"
SUBNET_PREFIX="172.30.0"

ok()   { echo "  ✔ $*"; }
warn() { echo "  ⚠ $*"; }
fail() { echo "  ✘ $*"; exit 1; }
step() { echo; echo "== $* =="; }

while [ $# -gt 0 ]; do
    case "$1" in
        --nodes) NODES="$2"; shift 2 ;;
        --chain-id) CHAIN_ID="$2"; shift 2 ;;
        --dir) DIR="$2"; shift 2 ;;
        --status) STATUS_ONLY=1; shift ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) fail "不認得的選項: $1" ;;
    esac
done

compose() { (cd "$DIR" && docker compose "$@"); }

rpc() {
    curl -s -m 5 -H 'Content-Type: application/json' \
        -d "{\"jsonrpc\":\"2.0\",\"method\":\"$1\",\"params\":${2:-[]},\"id\":1}" http://127.0.0.1:8545
}

block_number() {
    rpc eth_blockNumber | python3 -c 'import json,sys; print(int(json.load(sys.stdin)["result"],16))' 2>/dev/null
}

show_status() {
    step "狀態"
    if [ -f "$DIR/docker-compose.yml" ]; then
        compose ps
    else
        echo "  還沒建立（$DIR/docker-compose.yml 不存在）"
        return
    fi
    local b
    b="$(block_number)"
    if [ -n "$b" ]; then
        ok "JSON-RPC 正常，最新區塊 $b，Chain ID $(rpc eth_chainId | python3 -c 'import json,sys; print(int(json.load(sys.stdin)["result"],16))' 2>/dev/null)"
        echo "  驗證節點：$(rpc qbft_getValidatorsByBlockNumber '["latest"]' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["result"]))' 2>/dev/null) 個"
    else
        warn "http://127.0.0.1:8545 沒有回應"
    fi
}

if [ "$STATUS_ONLY" = 1 ]; then
    show_status
    exit 0
fi

# ---------------------------------------------------------------------------
step "1. 檢查環境"
command -v docker >/dev/null || fail "沒有 Docker。安裝：curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker \$USER（重新登入後再執行）"
docker compose version >/dev/null 2>&1 || fail "沒有 docker compose 外掛（Docker 20.10 以上內建；或 sudo apt install docker-compose-plugin）"
docker info >/dev/null 2>&1 || fail "目前帳號不能使用 Docker：sudo usermod -aG docker \$USER，重新登入後再執行"
command -v python3 >/dev/null || fail "需要 python3"
command -v curl >/dev/null || fail "需要 curl"
ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)、架構 $(uname -m)"

MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
if [ -z "$NODES" ]; then
    if [ "$MEM_MB" -ge 6000 ]; then NODES=4; else NODES=1; fi
    ok "記憶體 ${MEM_MB} MB → 使用 $NODES 個驗證節點（可用 --nodes 指定）"
fi
case "$NODES" in
    1|4) ;;
    *) fail "--nodes 只支援 1 或 4" ;;
esac
[ "$NODES" = 4 ] && [ "$MEM_MB" -lt 4000 ] && warn "記憶體只有 ${MEM_MB} MB，4 個節點可能不夠；不穩定時改用 --nodes 1"

if ! docker image inspect "$BESU_IMAGE" >/dev/null 2>&1; then
    echo "  下載 $BESU_IMAGE …"
    docker pull "$BESU_IMAGE" >/dev/null || fail "下載 $BESU_IMAGE 失敗（確認網路，以及這個版本有 $(uname -m) 的映像）"
fi
docker run --rm "$BESU_IMAGE" --version 2>/dev/null | head -1 | sed 's/^/  /'

# ---------------------------------------------------------------------------
step "2. 產生創世區塊與節點金鑰"
mkdir -p "$DIR"
if [ -f "$DIR/genesis.json" ]; then
    ok "$DIR/genesis.json 已存在，沿用（不會重建鏈）"
    NODES=$(ls -d "$DIR"/node*/ 2>/dev/null | wc -l)
else
    cat > "$DIR/qbftConfigFile.json" <<EOF
{
  "genesis": {
    "config": {
      "chainId": $CHAIN_ID,
      "berlinBlock": 0,
      "londonBlock": 0,
      "zeroBaseFee": true,
      "contractSizeLimit": 49152,
      "qbft": { "blockperiodseconds": 2, "epochlength": 30000, "requesttimeoutseconds": 4 }
    },
    "nonce": "0x0",
    "timestamp": "0x58ee40ba",
    "gasLimit": "0x1fffffffffffff",
    "difficulty": "0x1",
    "mixHash": "0x63746963616c2062797a616e74696e65206661756c7420746f6c6572616e6365",
    "coinbase": "0x0000000000000000000000000000000000000000",
    "alloc": {}
  },
  "blockchain": { "nodes": { "generate": true, "count": $NODES } }
}
EOF
    rm -rf "$DIR/networkFiles"
    docker run --rm --user "$(id -u):$(id -g)" -v "$DIR":/work -w /work "$BESU_IMAGE" \
        operator generate-blockchain-config --config-file=qbftConfigFile.json --to=networkFiles --private-key-file-name=key \
        >/dev/null || fail "產生設定失敗"
    cp "$DIR/networkFiles/genesis.json" "$DIR/genesis.json"
    i=1
    for k in "$DIR"/networkFiles/keys/*/; do
        mkdir -p "$DIR/node$i/data"
        cp "$k/key" "$k/key.pub" "$DIR/node$i/"
        chmod 600 "$DIR/node$i/key"
        i=$((i + 1))
    done
    ok "Chain ID $CHAIN_ID、$NODES 個驗證節點，金鑰在 $DIR/node*/key（請勿外流）"
fi

# ---------------------------------------------------------------------------
step "3. 產生 docker-compose.yml"
PUB1="$(sed 's/^0x//' "$DIR/node1/key.pub")"
BOOT="enode://${PUB1}@${SUBNET_PREFIX}.11:30303"
{
    echo "# 由 setup_besu.sh 產生"
    echo "services:"
    for i in $(seq 1 "$NODES"); do
        ip="${SUBNET_PREFIX}.1${i}"
        echo "  node$i:"
        echo "    image: $BESU_IMAGE"
        echo "    container_name: besu-node$i"
        echo "    restart: unless-stopped"
        echo "    user: \"$(id -u):$(id -g)\""
        echo "    environment:"
        echo "      - BESU_OPTS=-Xmx$HEAP"
        echo "    volumes:"
        echo "      - ./genesis.json:/config/genesis.json:ro"
        echo "      - ./node$i/key:/config/key:ro"
        echo "      - ./node$i/data:/data"
        echo "    networks:"
        echo "      besu:"
        echo "        ipv4_address: $ip"
        if [ "$i" = 1 ]; then
            echo "    ports:"
            echo "      - \"8545:8545\""
        fi
        echo "    command:"
        echo "      - --data-path=/data"
        echo "      - --genesis-file=/config/genesis.json"
        echo "      - --node-private-key-file=/config/key"
        echo "      - --min-gas-price=0"
        echo "      - --p2p-host=$ip"
        echo "      - --p2p-port=30303"
        echo "      - --host-allowlist=*"
        if [ "$i" = 1 ]; then
            echo "      - --rpc-http-enabled"
            echo "      - --rpc-http-host=0.0.0.0"
            echo "      - --rpc-http-port=8545"
            echo "      - --rpc-http-api=ETH,NET,QBFT,WEB3,TXPOOL"
            echo "      - --rpc-http-cors-origins=*"
        else
            echo "      - --bootnodes=$BOOT"
        fi
    done
    echo "networks:"
    echo "  besu:"
    echo "    driver: bridge"
    echo "    ipam:"
    echo "      config:"
    echo "        - subnet: ${SUBNET_PREFIX}.0/24"
} > "$DIR/docker-compose.yml"
ok "$DIR/docker-compose.yml"

# ---------------------------------------------------------------------------
step "4. 啟動"
compose up -d || fail "docker compose up 失敗"
echo "  等待出塊（最多 120 秒）…"
FIRST=""
for _ in $(seq 60); do
    sleep 2
    b="$(block_number)"
    [ -z "$b" ] && continue
    [ -z "$FIRST" ] && FIRST="$b"
    if [ "$b" -gt "$FIRST" ] 2>/dev/null; then
        ok "正在出塊（區塊 $b）"
        break
    fi
done
[ -n "$FIRST" ] || { compose logs --tail 30 node1; fail "JSON-RPC 沒有回應，請看上面的紀錄"; }

systemctl is-enabled --quiet docker 2>/dev/null || warn "Docker 沒有設定開機自動啟動：sudo systemctl enable docker"

show_status
IP="$(hostname -I | awk '{print $1}')"
echo
echo "下一步：在有 Foundry 專案的機器（例如 192.168.1.60）部署合約："
echo "  bash deploy_contracts.sh --rpc http://$IP:8545"
