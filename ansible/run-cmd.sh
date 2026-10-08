#!/bin/bash
# Execute arbitrary command on all hiba_service nodes

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INVENTORY_PATH="$SCRIPT_DIR/hosts.ini"

show_help() {
    echo "=========================================="
    echo "Run Command on All Nodes"
    echo "=========================================="
    echo ""
    echo "Usage: $0 [OPTIONS] \"command\""
    echo ""
    echo "Options:"
    echo "  --node, -n NODE     Execute on specific node (default: all nodes)"
    echo "  --sudo, -s          Execute command with sudo privileges"
    echo "  --help, -h          Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 \"uptime\"                                    # Run on ALL nodes"
    echo "  $0 --node node1 \"uptime\"                       # Run on node1 only"
    echo "  $0 -n node2 \"df -h\"                            # Check disk on node2"
    echo "  $0 \"ps aux | grep hiba\"                       # Find hiba processes on all nodes"
    echo "  $0 --sudo \"systemctl status hiba-service\"     # Check service status (all nodes, sudo)"
    echo "  $0 -n accounting-server -s \"ntpdate time.stdtime.gov.tw\"  # Sync time on accounting-server"
    echo ""
    echo "Available hosts:"
    grep -E "^[a-zA-Z]" "$INVENTORY_PATH" | grep -v "^\[" | awk '{print "  " $1}'
}

# Parse arguments
USE_SUDO=false
TARGET_NODE=""

if [[ $# -eq 0 ]]; then
    echo "Error: No command specified."
    echo ""
    show_help
    exit 1
fi

while [[ $# -gt 0 ]]; do
    case $1 in
        --help|-h)
            show_help
            exit 0
            ;;
        --sudo|-s)
            USE_SUDO=true
            shift
            ;;
        --node|-n)
            if [[ -z "$2" || "$2" == -* ]]; then
                echo "Error: --node requires a hostname argument."
                exit 1
            fi
            TARGET_NODE="$2"
            # Validate hostname exists in inventory
            if ! grep -q "^$TARGET_NODE " "$INVENTORY_PATH"; then
                echo "Error: Host '$TARGET_NODE' not found in inventory file."
                echo "Available hosts:"
                grep -E "^[a-zA-Z]" "$INVENTORY_PATH" | grep -v "^\[" | awk '{print "  " $1}'
                exit 1
            fi
            shift 2
            ;;
        *)
            # This is the command
            break
            ;;
    esac
done

if [[ $# -eq 0 ]]; then
    echo "Error: No command specified after options."
    echo ""
    show_help
    exit 1
fi

cd "$SCRIPT_DIR"

# Determine target
if [[ -n "$TARGET_NODE" ]]; then
    TARGET="$TARGET_NODE"
else
    TARGET="hiba_service"
fi

# Build ansible command
ANSIBLE_CMD="ansible $TARGET -i hosts.ini -m shell -a \"$*\""

if [[ "$USE_SUDO" == "true" ]]; then
    ANSIBLE_CMD="$ANSIBLE_CMD --become --ask-become-pass"
fi

# Execute
eval "$ANSIBLE_CMD"
