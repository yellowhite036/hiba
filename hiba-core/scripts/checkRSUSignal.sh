#!/bin/bash
nmcli -t -f SSID,SIGNAL dev wifi list | awk -F: -v threshold=$1 '$2 > threshold'