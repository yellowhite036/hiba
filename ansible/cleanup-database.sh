#!/bin/bash

# HiBA Database Cleanup Script
# This script removes all database files (blockchain_manager.*) from all machines in the hiba_service group

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLAYBOOK_PATH="$SCRIPT_DIR/playbooks/cleanup-database.yaml"
INVENTORY_PATH="$SCRIPT_DIR/hosts.ini"

echo "=========================================="
echo "HiBA Database Cleanup Script"
echo "=========================================="
echo "This will remove ALL database files from ALL machines"
echo "Database pattern: blockchain_manager.*"
echo

# Confirmation prompt
read -p "Are you sure you want to proceed? This action cannot be undone. (yes/no): " confirm
if [[ $confirm != "yes" ]]; then
    echo "Operation cancelled."
    exit 0
fi

echo
echo "Starting database cleanup across all machines..."
echo

# Run the Ansible playbook
if ansible-playbook -i "$INVENTORY_PATH" "$PLAYBOOK_PATH"; then
    echo
    echo "=========================================="
    echo "Database cleanup completed successfully!"
    echo "=========================================="
else
    echo
    echo "=========================================="
    echo "Database cleanup failed!"
    echo "Please check the error messages above."
    echo "=========================================="
    exit 1
fi