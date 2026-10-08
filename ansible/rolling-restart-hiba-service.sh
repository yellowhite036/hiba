#!/bin/bash

# HiBA Service Rolling Restart Script
# This script performs a rolling restart to avoid Sepolia RPC rate limiting

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INVENTORY_PATH="$SCRIPT_DIR/hosts.ini"
ROLLING_RESTART_PLAYBOOK="$SCRIPT_DIR/playbooks/rolling-restart-hiba-service.yaml"
CLEANUP_PLAYBOOK="$SCRIPT_DIR/playbooks/cleanup-database.yaml"

# Default values
CLEANUP_DB=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --cleanup-db|-c)
            CLEANUP_DB=true
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --cleanup-db, -c        Clean up database files before restarting"
            echo "  --help, -h              Show this help message"
            echo ""
            echo "This script uses predefined batches with 20s delays to avoid Sepolia RPC rate limiting"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

echo "=========================================="
echo "HiBA Service Rolling Restart Script"
echo "=========================================="
echo "Database cleanup: $([ "$CLEANUP_DB" = true ] && echo "enabled" || echo "disabled")"
echo ""

if [[ "$CLEANUP_DB" == "true" ]]; then
    echo "WARNING: This will remove ALL database files from ALL machines!"
    echo "Database pattern: blockchain_manager.*"
    echo
    read -p "Do you want to clean up the database before restarting? (yes/no) [default: no]: " confirm_cleanup
    confirm_cleanup=${confirm_cleanup:-no}
    
    if [[ $confirm_cleanup == "yes" ]]; then
        echo
        echo "Step 1/2: Cleaning up database files..."
        echo "Running database cleanup playbook..."
        
        if ansible-playbook -i "$INVENTORY_PATH" "$CLEANUP_PLAYBOOK"; then
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
    echo "Step 2/2: Performing rolling restart..."
else
    echo "Performing rolling restart..."
fi

echo "Starting rolling restart with predefined batches and 20s delays..."
echo "Batch strategy:"
echo "  - accounting-server (starts first)"
echo "  - node1, node2 (batch 1)"
echo "  - node3, node4, node5, node6 (batch 2)" 
echo "  - node7, node8, node9 (batch 3)"

if ansible-playbook -i "$INVENTORY_PATH" "$ROLLING_RESTART_PLAYBOOK"; then
    echo
    echo "=========================================="
    if [[ "$CLEANUP_DB" == "true" ]]; then
        echo "Database cleanup and rolling restart completed successfully!"
    else
        echo "Rolling restart completed successfully!"
    fi
    echo "All nodes should now be running with staggered startup times"
    echo "=========================================="
else
    echo
    echo "=========================================="
    echo "Rolling restart failed!"
    echo "Please check the error messages above."
    echo "=========================================="
    exit 1
fi
