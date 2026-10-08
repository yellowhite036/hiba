array=($(ls /sys/class/net/))

targetIP=$1

for ((i=0; i < ${#array[@]}; i++))
do
    if ping -I ${array[$i]} -q -c 1 -W 1 $targetIP; then
        echo ${array[$i]} "online"
    fi
done
