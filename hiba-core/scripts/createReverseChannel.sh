#!/bin/bash

clientName="";
clientIP="";
port=""

if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]
then
    #echo "Input error, please follow the input format."
    #echo "input format: bash reverseTunnel.sh 'clientName' 'clientIP'"
    echo "false"
	exit
else
    clientName=$1
    clientIP=$2
    port=$3
fi

if ping -q -c 1 -W 1 $clientIP
then
    ssh -fCNR $port:localhost:8088 $clientName@$clientIP
	if [ $? = 0 ]
	then
        echo "true"
	fi
else
    echo "false"
fi
