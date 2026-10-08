#!/bin/bash

# HiBA Service Restart Script
# This script restarts the HiBA service with flexible host selection and optional database cleanup
# Default: Restart all hosts, or specify specific hosts

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INVENTORY_PATH="$SCRIPT_DIR/hosts.ini"
RESTART_PLAYBOOK="$SCRIPT_DIR/playbooks/restart-hiba-service.yaml"
CLEANUP_PLAYBOOK="$SCRIPT_DIR/playbooks/cleanup-database.yaml"

echo "=========================================="
echo "HiBA Service Restart Script"
echo "=========================================="

# Parse arguments
TARGET_HOSTS=()
CLEANUP_DB=false
ALL_HOSTS=true

show_help() {
    echo "Usage: $0 [hostname1 hostname2 ...] [--cleanup-db|-c] [--help|-h]"
    echo ""
    echo "Parameters:"
    echo "  hostname(s)         Specific host(s) to restart (from hosts.ini)"
    echo "                      If no hostnames provided, restarts ALL hosts"
    echo ""
    echo "Options:"
    echo "  --cleanup-db, -c    Clean up database files before restarting service"
    echo "  --help, -h          Show this help message"
    echo ""
    echo "Available hosts:"
    grep -E "^[a-zA-Z]" "$INVENTORY_PATH" | grep -v "^\[" | awk '{print "  " $1}'
    echo ""
    echo "Examples:"
    echo "  $0                              # Restart ALL hosts"
    echo "  $0 --cleanup-db                 # Clean database and restart ALL hosts"
    echo "  $0 node1                        # Restart node1 only"
    echo "  $0 node1 node2                  # Restart node1 and node2"
    echo "  $0 node1 node2 --cleanup-db     # Clean database and restart node1, node2"
    echo "  $0 accounting-server -c         # Clean database and restart accounting-server"
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --cleanup-db|-c)
            CLEANUP_DB=true
            shift
            ;;
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
    WARNING_MESSAGE="This will affect ALL machines in the inventory!"
else
    TARGET_DESCRIPTION="hosts: ${TARGET_HOSTS[*]}"
    LIMIT_PARAM="--limit $(IFS=,; echo "${TARGET_HOSTS[*]}")"
    if [[ ${#TARGET_HOSTS[@]} -eq 1 ]]; then
        WARNING_MESSAGE="This will affect host: ${TARGET_HOSTS[0]}"
    else
        WARNING_MESSAGE="This will affect hosts: ${TARGET_HOSTS[*]}"
    fi
fi

echo "Target: $TARGET_DESCRIPTION"
if [[ "$CLEANUP_DB" == "true" ]]; then
    echo "Database cleanup mode enabled"
fi

if [[ "$CLEANUP_DB" == "true" ]]; then
    echo
    echo "WARNING: This will remove ALL database files!"
    echo "Database pattern: blockchain_manager.*"
    echo "$WARNING_MESSAGE"
    echo
    read -p "Do you want to clean up the database before restarting? (yes/no) [default: no]: " confirm_cleanup
    
    if [[ $confirm_cleanup == "yes" ]]; then
        echo
        echo "Step 1/2: Cleaning up database files..."
        echo "Running database cleanup playbook on $TARGET_DESCRIPTION..."
        
        if [[ "$ALL_HOSTS" == "true" ]]; then
            CLEANUP_CMD="ansible-playbook -i \"$INVENTORY_PATH\" \"$CLEANUP_PLAYBOOK\""
        else
            CLEANUP_CMD="ansible-playbook -i \"$INVENTORY_PATH\" \"$CLEANUP_PLAYBOOK\" $LIMIT_PARAM"
        fi
        
        if eval "$CLEANUP_CMD"; then
            echo "Database cleanup completed successfully!"
        else
            echo "Database cleanup failed! Aborting restart."
            exit 1
        fi
    else
        echo "Database cleanup cancelled. Proceeding with restart only."
        CLEANUP_DB=false
    fi
fi

echo
if [[ "$CLEANUP_DB" == "true" ]]; then
    echo "Step 2/2: Restarting HiBA service on $TARGET_DESCRIPTION..."
else
    echo "Restarting HiBA service on $TARGET_DESCRIPTION..."
fi

echo "Running restart playbook..."

if [[ "$ALL_HOSTS" == "true" ]]; then
    RESTART_CMD="ansible-playbook -i \"$INVENTORY_PATH\" \"$RESTART_PLAYBOOK\""
else
    RESTART_CMD="ansible-playbook -i \"$INVENTORY_PATH\" \"$RESTART_PLAYBOOK\" $LIMIT_PARAM"
fi

if eval "$RESTART_CMD"; then
    echo
    echo "=========================================="
    if [[ "$CLEANUP_DB" == "true" ]]; then
        echo "Database cleanup and service restart on $TARGET_DESCRIPTION completed successfully!"
    else
        echo "Service restart on $TARGET_DESCRIPTION completed successfully!"
    fi
    echo "=========================================="
else
    echo
    echo "=========================================="
    echo "Service restart on $TARGET_DESCRIPTION failed!"
    echo "Please check the error messages above."
    echo "=========================================="
    exit 1
fi