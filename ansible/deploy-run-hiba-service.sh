#!/bin/bash

# HiBA Service Deploy Script
# This script deploys the HiBA service with flexible host selection
# Default: Deploy to all hosts, or specify specific hosts

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INVENTORY_PATH="$SCRIPT_DIR/hosts.ini"
DEPLOY_PLAYBOOK="$SCRIPT_DIR/playbooks/deploy-run-hiba-service.yaml"
STOP_PLAYBOOK="$SCRIPT_DIR/playbooks/stop-hiba-service.yaml"

echo "=========================================="
echo "HiBA Service Deploy Script"
echo "=========================================="

# Parse arguments
TARGET_HOSTS=()
ALL_HOSTS=true

show_help() {
    echo "Usage: $0 [hostname1 hostname2 ...] [--help|-h]"
    echo ""
    echo "Parameters:"
    echo "  hostname(s)         Specific host(s) to deploy to (from hosts.ini)"
    echo "                      If no hostnames provided, deploys to ALL hosts"
    echo ""
    echo "Options:"
    echo "  --help, -h          Show this help message"
    echo ""
    echo "Available hosts:"
    grep -E "^[a-zA-Z]" "$INVENTORY_PATH" | grep -v "^\[" | awk '{print "  " $1}'
    echo ""
    echo "Examples:"
    echo "  $0                              # Deploy to ALL hosts"
    echo "  $0 node1                        # Deploy to node1 only"
    echo "  $0 node1 node2                  # Deploy to node1 and node2"
    echo "  $0 accounting-server            # Deploy to accounting-server"
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --help|-h)
            show_help
            exit 0
            ;;
        *)
            # Check if this is a valid hostname
            if grep -q "^$1 " "$INVENTORY_PATH"; then
                TARGET_HOSTS+=("$1")
                ALL_HOSTS=false
            else
                echo "Error: Host '$1' not found in inventory file."
                echo "Available hosts:"
                grep -E "^[a-zA-Z]" "$INVENTORY_PATH" | grep -v "^\[" | awk '{print "  " $1}'
                exit 1
            fi
            shift
            ;;
    esac
done

# Determine target description
if [[ "$ALL_HOSTS" == "true" ]]; then
    TARGET_DESCRIPTION="ALL hosts"
    LIMIT_PARAM=""
    WARNING_MESSAGE="This will deploy to ALL machines in the inventory!"
else
    TARGET_DESCRIPTION="hosts: ${TARGET_HOSTS[*]}"
    LIMIT_PARAM="--limit $(IFS=,; echo "${TARGET_HOSTS[*]}")"
    if [[ ${#TARGET_HOSTS[@]} -eq 1 ]]; then
        WARNING_MESSAGE="This will deploy to host: ${TARGET_HOSTS[0]}"
    else
        WARNING_MESSAGE="This will deploy to hosts: ${TARGET_HOSTS[*]}"
    fi
fi

echo "Target: $TARGET_DESCRIPTION"
echo "$WARNING_MESSAGE"
echo

# Step 1: Stop service before deploy
echo "Stopping HiBA service on $TARGET_DESCRIPTION before deploy..."
echo "Running stop playbook..."

if [[ "$ALL_HOSTS" == "true" ]]; then
    STOP_CMD="ansible-playbook -i \"$INVENTORY_PATH\" \"$STOP_PLAYBOOK\""
else
    STOP_CMD="ansible-playbook -i \"$INVENTORY_PATH\" \"$STOP_PLAYBOOK\" $LIMIT_PARAM"
fi

if eval "$STOP_CMD"; then
    echo
    echo "Stop service on $TARGET_DESCRIPTION completed successfully!"
    echo
else
    echo
    echo "=========================================="
    echo "Stop service on $TARGET_DESCRIPTION failed!"
    echo "Please check the error messages above."
    echo "=========================================="
    exit 1
fi

# Step 2: Deploy and run service
echo "Deploying HiBA service to $TARGET_DESCRIPTION..."
echo "Running deploy playbook..."

if [[ "$ALL_HOSTS" == "true" ]]; then
    DEPLOY_CMD="ansible-playbook -i \"$INVENTORY_PATH\" \"$DEPLOY_PLAYBOOK\""
else
    DEPLOY_CMD="ansible-playbook -i \"$INVENTORY_PATH\" \"$DEPLOY_PLAYBOOK\" $LIMIT_PARAM"
fi

if eval "$DEPLOY_CMD"; then
    echo
    echo "=========================================="
    echo "Deploy to $TARGET_DESCRIPTION completed successfully!"
    echo "=========================================="
else
    echo
    echo "=========================================="
    echo "Deploy to $TARGET_DESCRIPTION failed!"
    echo "Please check the error messages above."
    echo "=========================================="
    exit 1
fi